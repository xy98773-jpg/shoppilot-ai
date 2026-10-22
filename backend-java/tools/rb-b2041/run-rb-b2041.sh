#!/usr/bin/env bash
# =============================================================================
# M4-B20.4.1 开发自证探针（V2：R143 读时同步真栈验证）
# -----------------------------------------------------------------------------
# 目的：证明**关闭定时轮询**（ANALYSIS_POLL_ENABLED=false）时，仅靠
#   GET /api/v1/analysis/runs/{id} 的读时同步，就能把 run 推进到 SUCCEEDED 并落 analysis_items。
# 与项目负责人 rb-b204-review 的差异（刻意）：
#   - 不复用其脚本/端口/库；本目录独立（MySQL 3382 / app 18331 / service-ai 18330）；
#   - 不挂抓包反代，直接用 **service-ai 容器自身的 uvicorn access log** 证明出站调用发生
#     （服务端视角，比客户端抓包更硬）；
#   - 断言「出站 GET /internal/v1/analysis/runs/{id} 次数 == 我方轮询 GET 次数」——
#     若定时轮询偷偷开着，出站次数会多出来。
# 生命周期：trap cleanup（容器/进程全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b2041"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
MYSQL_NAME="rb-b2041-mysql"
AI_NAME="rb-b2041-ai"
MYSQL_PORT="${MYSQL_PORT:-3382}"
APP_PORT="${APP_PORT:-18331}"
AI_PORT="${AI_PORT:-18330}"
TOKEN="rb-b2041-internal-token-0123456789"
EVI="$OUT/evidence-b2041.txt"
BODY="$OUT/body.json"
PASS=0; FAIL=0
mkdir -p "$OUT"
: > "$EVI"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
jget(){ "$PY" -c "import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    print('<unparsable>'); raise SystemExit
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(d,dict) and k in d: d=d[k]
    elif isinstance(d,list) and k.lstrip('-').isdigit(): d=d[int(k)]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: fat jar 不存在：$JAR（先跑 package）"; exit 4; }
log "JAR_SIZE=$(stat -c%s "$JAR")"
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT"

# ---------------------------------------------------------------- MySQL
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
READY=0
for _ in $(seq 1 90); do
  docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
[ "$READY" = 1 ] || { log "FATAL: MySQL 未就绪"; exit 5; }
log "MYSQL_READY=1"

# ---------------------------------------------------------------- 真实 service-ai
docker rm -f "$AI_NAME" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 docker run -d --name "$AI_NAME" -p "$AI_PORT:8000" \
  -v "$ROOT/service-ai:/app" -w /app -e PYTHONPATH=/app/src -e AI_INTERNAL_TOKEN="$TOKEN" \
  shoppilot-dev-service-ai:latest uvicorn shoppilot_ai.app.main:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1
AUP=0
for _ in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { AUP=1; break; }
  sleep 1
done
[ "$AUP" = 1 ] || { log "FATAL: service-ai 未就绪"; docker logs "$AI_NAME" 2>&1 | tail -20 | tee -a "$EVI"; exit 5; }
log "SERVICE_AI_UP=1 (real container, port $AI_PORT)"

# ---------------------------------------------------------------- app（显式关闭定时轮询）
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b2041-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
# ★★ V2 的核心前置：关掉定时轮询/超时扫描，只留读时同步
export ANALYSIS_POLL_ENABLED=false
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -jar "$JAR" --server.port="$APP_PORT" >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 150); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT)  ANALYSIS_POLL_ENABLED=false"

q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 shoppilot -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }

# ---------------------------------------------------------------- 种子 + 登录
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  < "$OUT/seed.sql" 2>"$OUT/seed.err"
chk "种子导入成功" "0" "$?"
chk "迁移版本数 10" "10" "$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;")"

curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" -d '{"username":"operator01","password":"1234"}' > "$BODY"
T_OP=$(jget "data.accessToken")
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && [ "$T_OP" != "<unparsable>" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 创建 run
sep; log "=== 创建 run（ALL_ACTIVE / enable_llm=false，避免真实 LLM 费用）==="
BODY_REQ='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb-b2041-readsync-1" -d "$BODY_REQ")
log "CREATE HTTP $CODE"
log "CREATE BODY $(cat "$BODY")"
chk "创建 202" "202" "$CODE"
RID=$(jget "data.run_id")
log "run_id=$RID  创建后状态=$(jget data.status)/progress=$(jget data.progress)/step=$(jget data.current_step)"
chk "创建后未落终态（RUNNING，待读时同步推进）" "RUNNING" "$(jget data.status)"

# ---------------------------------------------------------------- 只靠 GET /runs/{id} 轮询
sep; log "=== 只靠 GET /runs/{id} 轮询推进（不启动定时轮询）==="
POLLS=0; ST=""; LAST_PROGRESS=""
for i in $(seq 1 60); do
  POLLS=$((POLLS+1))
  curl -s --noproxy '*' -o "$BODY" -H "Authorization: Bearer $T_OP" \
    "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID" >/dev/null
  ST=$(jget data.status); PR=$(jget data.progress); STEP=$(jget data.current_step)
  if [ "$PR" != "$LAST_PROGRESS" ] || [ "$i" = 1 ]; then
    log "  poll#$POLLS status=$ST progress=$PR step=$STEP"
    LAST_PROGRESS="$PR"
  fi
  case "$ST" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) break;; esac
  sleep 2
done
log "轮询次数=$POLLS 最终 status=$ST"
chk "最终 SUCCEEDED（读时同步推进）" "SUCCEEDED" "$ST"
chk "最终 progress=100" "100" "$(jget data.progress)"

# ---------------------------------------------------------------- DB 终态 + 落库
sep; log "=== DB 终态与落库 ==="
chk "DB status" "SUCCEEDED" "$(q "SELECT status FROM analysis_runs WHERE id=$RID;")"
chk "DB progress" "100" "$(q "SELECT progress FROM analysis_runs WHERE id=$RID;")"
ITEMS=$(q "SELECT COUNT(*) FROM analysis_items WHERE run_id=$RID;")
TOTAL=$(q "SELECT total_skus FROM analysis_runs WHERE id=$RID;")
log "analysis_items 行数=$ITEMS ；total_skus=$TOTAL（数据不足的 SKU 可能不出条目，故只断言 >0）"
chk "analysis_items > 0" "1" "$([ "${ITEMS:-0}" -gt 0 ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- 出站证据（service-ai 服务端日志）
sep; log "=== 出站调用证据（service-ai uvicorn access log）==="
AI_LOG="$OUT/ai-access.log"
docker logs "$AI_NAME" > "$AI_LOG" 2>&1
log "--- GET /internal/v1/analysis/runs/{id}（状态查询，前 6 行）---"
grep -E "GET /internal/v1/analysis/runs/[0-9]+ " "$AI_LOG" | head -6 | tee -a "$EVI"
OUTBOUND_GET=$(grep -cE "GET /internal/v1/analysis/runs/[0-9]+ " "$AI_LOG")
log "出站状态查询次数=$OUTBOUND_GET ；我方轮询 GET 次数=$POLLS"
chk "出站状态查询次数 == 轮询次数（证明无定时轮询掺入）" "$POLLS" "$OUTBOUND_GET"
log "--- 其余出站调用（validate / create / result / cancel）---"
grep -E "(POST|GET) /internal/v1/analysis" "$AI_LOG" \
  | grep -vE "GET /internal/v1/analysis/runs/[0-9]+ " | head -6 | tee -a "$EVI"
# ★ uvicorn 的 access log 会把冒号编码成 %3A（"analysis%3Avalidate"），两种形态都算命中
chk "出现 POST /internal/v1/analysis:validate" "1" "$(grep -cE 'POST /internal/v1/analysis(%3A|:)validate' "$AI_LOG")"
chk "出现 POST /internal/v1/analysis/runs " "1" "$(grep -cE 'POST /internal/v1/analysis/runs HTTP' "$AI_LOG")"
chk "出现 GET  .../result（COMPLETED 后取结果）" "1" "$(grep -cE 'GET /internal/v1/analysis/runs/[0-9]+/result' "$AI_LOG")"

# ---------------------------------------------------------------- 反向证据：定时任务未装配
sep; log "=== 反向证据：定时轮询未装配 ==="
chk "app.log 无轮询扫描日志" "0" "$(grep -c '分析运行扫描\|AnalysisRunPollTask' "$APP_LOG")"

sep
log "RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ] && log "ALL PASS" || log "HAS FAILURE"
exit 0
