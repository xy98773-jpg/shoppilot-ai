#!/usr/bin/env bash
# =============================================================================
# B15（M4-T03/T04）真栈 Negative 取证：
#   createRun → POST /internal/v1/analysis/runs → Python 骨架返回 404 AI-1003
#   → Java 映射为对外 502 ANL-7002（原码只进日志）
# -----------------------------------------------------------------------------
# 依据：任务书 V4 + R107（service-ai 的 analysis.py 仍是骨架、零端点）。
# 做法：独立 MySQL 容器 + 真 service-ai 容器（uvicorn，映射到宿主供本机调用）
#       + backend-java fat jar（Spring Boot 启动即执行全部 10 个 Flyway 迁移）。
# 为什么必须真栈：404 路径的「AI-1003 → ANL-7002」映射只有在两端真实 HTTP 交互时
#       才能被证明——单测里我们自己的假对端可以随便编造状态码。
# 生命周期：trap cleanup EXIT（容器与进程必被回收，不残留）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
MYSQL_NAME="rb-b15-mysql"
AI_NAME="rb-b15-ai"
MYSQL_PORT="${MYSQL_PORT:-3391}"
APP_PORT="${APP_PORT:-18350}"
AI_PORT="${AI_PORT:-18250}"
AI_IMAGE="shoppilot-service-ai:b15"
TOKEN="rb-b15-internal-token-0123456789"
OUT_DIR="$ROOT/backend-java/tools/rb-b15"
EVI="$OUT_DIR/evidence-rb-b15.txt"
mkdir -p "$OUT_DIR/storage"

PASS=0; FAIL=0
: > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }

chk(){
  local name="$1" exp="$2" act="$3"
  if [ "$exp" = "$act" ]; then
    PASS=$((PASS+1)); log "[PASS] $name => $act"
  else
    FAIL=$((FAIL+1)); log "[FAIL] $name => expected [$exp] but was [$act]"
  fi
}

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null
  docker rm -f "$AI_NAME" >/dev/null 2>&1
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

# ---------------------------------------------------------------- 前置：jar 必须存在
if [ ! -f "$JAR" ]; then
  log "FATAL: fat jar 不存在：$JAR"
  exit 4
fi
log "JAR=$(basename "$JAR")"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

# ---------------------------------------------------------------- 端口占用自检
log "=== 端口自检 ==="
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT"; do
  if netstat -ano | grep -E ":${p}[[:space:]]" >/dev/null 2>&1; then
    log "FATAL: 端口 $p 已被占用，换端口后重跑（MYSQL_PORT / APP_PORT / AI_PORT）"
    exit 6
  fi
  log "PORT_FREE $p"
done

# ---------------------------------------------------------------- 起 MySQL
log "=== 起独立 MySQL（$MYSQL_NAME，$MYSQL_PORT:3306）==="
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" \
  -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
MYSQL_READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1; then
    MYSQL_READY=1; log "MYSQL_READY=1"; break
  fi
  sleep 2
done
[ "$MYSQL_READY" = "1" ] || { log "FATAL: MySQL 未就绪"; exit 5; }

# ---------------------------------------------------------------- 构建并起 service-ai
log "=== 构建 service-ai 镜像（$AI_IMAGE）==="
if ! docker build -t "$AI_IMAGE" "$ROOT/service-ai" >"$OUT_DIR/ai-build.log" 2>&1; then
  log "FATAL: service-ai 镜像构建失败，见 $OUT_DIR/ai-build.log"
  tail -20 "$OUT_DIR/ai-build.log" | tee -a "$EVI"
  exit 7
fi
docker rm -f "$AI_NAME" >/dev/null 2>&1
docker run -d --name "$AI_NAME" -p "$AI_PORT:8000" \
  -e AI_INTERNAL_TOKEN="$TOKEN" \
  -e JAVA_BACKEND_BASE_URL="http://127.0.0.1:8080" \
  "$AI_IMAGE" >/dev/null 2>&1

AI_UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' \
        "http://127.0.0.1:$AI_PORT/internal/v1/health" || echo 000)
  if [ "$c" = "200" ]; then AI_UP=1; log "AI_UP=1 (after ${i}s)"; break; fi
  sleep 1
done
if [ "$AI_UP" != "1" ]; then
  log "FATAL: service-ai 未就绪"; docker logs "$AI_NAME" 2>&1 | tail -30 | tee -a "$EVI"; exit 5
fi

# ★ 先单独证明「对端确实零端点」：带正确 token 直接打内部 runs → 404 + AI-1003
sep; log "=== 对端边界（R107）：直打内部端点 ==="
DIRECT_BODY=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$AI_PORT/internal/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "X-Internal-Token: $TOKEN" -H "X-Request-Id: req_rb_b15_direct" \
  -d '{}' -w $'\n%{http_code}')
log "DIRECT_RESPONSE=$(echo "$DIRECT_BODY" | tr '\n' ' ')"
chk "直打内部 runs 的 HTTP" "404" "$(echo "$DIRECT_BODY" | tail -1)"
chk "直打内部 runs 的 code" "AI-1003" "$(echo "$DIRECT_BODY" | head -1 | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"

# ---------------------------------------------------------------- 起应用
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b15-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT_DIR/storage"
APP_LOG="$OUT_DIR/app.log"
: > "$APP_LOG"

java -jar "$JAR" --server.port="$APP_PORT" >> "$APP_LOG" 2>&1 &
APP_PID=$!

JAVA_UP=0
for i in $(seq 1 120); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  if [ "$c" = "200" ]; then JAVA_UP=1; log "JAVA_UP=1 (after ${i}s)"; break; fi
  sleep 1
done
if [ "$JAVA_UP" != "1" ]; then
  log "FATAL: 应用未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5
fi

# ---------------------------------------------------------------- 种子 + 登录
log "=== 灌种子（seed-b7.sql：团队 7 / operator01 / SKU 9002 / 仓 9004）==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  < "$ROOT/backend-java/tools/evi/seed-b7.sql" 2>/dev/null
log "SEED_DONE"

LOGIN_BODY=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"operator01","password":"S3cret!Passw0rd"}')
TOKEN_JWT=$(echo "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
chk "登录拿到 accessToken" "1" "$([ -n "$TOKEN_JWT" ] && echo 1 || echo 0)"
if [ -z "$TOKEN_JWT" ]; then
  log "FATAL: 登录失败：$LOGIN_BODY"; exit 5
fi

# ---------------------------------------------------------------- ★ V4：createRun
sep; log "=== V4：POST /api/v1/analysis/runs（对端 404 AI-1003）==="
RUN_BODY='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9004,'\
'"date_from":"2026-09-16","date_to":"2026-10-13",'\
'"params":{"window_days":28,"min_days":7,"enable_llm":false},'\
'"algorithm_version":"avg-ma-3sigma-v1","prompt_version":"explain_risk.v1"}'

RESP=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_JWT" \
  -H "Idempotency-Key: rb-b15-v4-001" \
  -d "$RUN_BODY" -w $'\n%{http_code}')
RESP_BODY=$(echo "$RESP" | head -n -1)
RESP_STATUS=$(echo "$RESP" | tail -1)
log "RESP_STATUS=$RESP_STATUS"
log "RESP_BODY=$RESP_BODY"

chk "对外 HTTP" "502" "$RESP_STATUS"
chk "对外错误码" "ANL-7002" "$(echo "$RESP_BODY" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"
chk "响应体不含 AI-1003（不外泄原码）" "0" "$(echo "$RESP_BODY" | grep -c 'AI-1003')"
chk "响应体不含任何 AI- 码" "0" "$(echo "$RESP_BODY" | grep -c 'AI-')"

# ---------------------------------------------------------------- DB 状态补偿
sep; log "=== DB：run 必须落 FAILED，不留 PENDING ==="
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N -e "$1" 2>/dev/null | tr -d '\r'; }
chk "run 行数" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")"
chk "status" "FAILED" "$(q "SELECT status FROM shoppilot.analysis_runs LIMIT 1;")"
chk "error_code" "ANL-7002" "$(q "SELECT error_code FROM shoppilot.analysis_runs LIMIT 1;")"
chk "error_message 非空" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE error_message IS NOT NULL AND error_message <> '';")"
chk "finished_at 非空" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE finished_at IS NOT NULL;")"
chk "timeout_at 非空（§13.4）" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE timeout_at IS NOT NULL;")"
chk "team_id 来自 JWT" "7" "$(q "SELECT team_id FROM shoppilot.analysis_runs LIMIT 1;")"
chk "run_no 形态（RUN-8位-4位）" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE run_no REGEXP '^RUN-[0-9]{8}-[0-9]{4}\$';")"
chk "审计 ANALYSIS_RUN_CREATE 条数（失败不得写成功创建）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE';")"
chk "审计 ANALYSIS_RUN_FINISH 条数" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH';")"
chk "审计 FINISH 结果" "FAILURE" "$(q "SELECT result FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH' LIMIT 1;")"

# ---------------------------------------------------------------- 日志：原码保留
sep; log "=== 日志：AI 原码 / HTTP / requestId 只进服务端日志 ==="
chk "app 日志含 AI-1003" "1" "$(grep -c 'AI-1003' "$APP_LOG")"
chk "app 日志含 externalCode=ANL-7002" "1" "$(grep -c 'externalCode=ANL-7002' "$APP_LOG" || true)"
log "--- 日志片段（grep AI-1003）---"
grep 'AI-1003' "$APP_LOG" | tail -3 | tee -a "$EVI"

sep
log "RB15_CHECK_TOTAL=$((PASS+FAIL))"
log "RB15_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB15_RESULT=ALL PASS"; else log "RB15_RESULT=HAS FAILURES"; fi
exit 0
