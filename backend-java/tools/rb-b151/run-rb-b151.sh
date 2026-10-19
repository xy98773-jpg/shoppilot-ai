#!/usr/bin/env bash
# =============================================================================
# B15.1（R108 / R109）真栈取证：契约级假对端返回 202 的成功路径 + 相邻回归
# -----------------------------------------------------------------------------
# 依据：回修任务书 §四 V2；R107（真 service-ai 的 analysis.py 仍零端点）⇒ 202 路径只能用
#       自建契约级假对端验证，它证明「Java 在契约对端下的行为」，不证明 Python 实现。
# 断言：
#   T1 同键同体 → 202；首次无重放头；重放 202 + Idempotent-Replayed: true + 同一 run_id
#   T2 同键仅改 date_from → 409 COM-0009（R108）
#   T3 CATEGORY category=A"B → 202，DB scope_json JSON_VALID=1 且 category 原值；
#      同键同体重放；改类目 → COM-0009（R109）
#   T4 同范围不同键 → 409 ANL-7001（回归）
#   T5 假对端 AI-6002 → 409 ANL-7012 + run FAILED + 0 PENDING + 响应无 AI- 码（回归）
# 生命周期：trap cleanup EXIT（MySQL 容器 / 应用 / 假对端全部回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
MYSQL_NAME="rb-b151-mysql"
MYSQL_PORT="${MYSQL_PORT:-3392}"
APP_PORT="${APP_PORT:-18370}"
AI_PORT="${AI_PORT:-18260}"
OUT_DIR="$ROOT/backend-java/tools/rb-b151"
EVI="$OUT_DIR/evidence-rb-b151.txt"
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
  [ -n "${AI_PID:-}" ] && kill "$AI_PID" 2>/dev/null; wait "$AI_PID" 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

if [ ! -f "$JAR" ]; then
  log "FATAL: fat jar 不存在：$JAR"; exit 4
fi
log "JAR=$(basename "$JAR")"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

log "=== 端口自检 ==="
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT"; do
  if netstat -ano | grep -E ":${p}[[:space:]]" | grep -q LISTENING; then
    log "FATAL: 端口 $p 已被占用"; exit 6
  fi
  log "PORT_FREE $p"
done

# ---------------------------------------------------------------- 假对端
log "=== 起契约级假对端（$AI_PORT）==="
AI_LOG="$OUT_DIR/fake-ai.log"
: > "$AI_LOG"
python -u "$OUT_DIR/fake_ai.py" "$AI_PORT" >> "$AI_LOG" 2>&1 &
AI_PID=$!
AI_UP=0
for i in $(seq 1 30); do
  if grep -q "FAKE_AI_LISTEN=$AI_PORT" "$AI_LOG" 2>/dev/null; then AI_UP=1; break; fi
  sleep 1
done
[ "$AI_UP" = "1" ] || { log "FATAL: 假对端未就绪"; tail -20 "$AI_LOG" | tee -a "$EVI"; exit 7; }
log "FAKE_AI_UP=1"

# ---------------------------------------------------------------- MySQL
log "=== 起独立 MySQL（$MYSQL_NAME，$MYSQL_PORT:3306）==="
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" \
  -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
MYSQL_READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1; then
    MYSQL_READY=1; break
  fi
  sleep 2
done
[ "$MYSQL_READY" = "1" ] || { log "FATAL: MySQL 未就绪"; exit 5; }
log "MYSQL_READY=1"

# ---------------------------------------------------------------- 应用
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b151-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT"
export AI_INTERNAL_TOKEN="rb-b151-internal-token"
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

docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  < "$ROOT/backend-java/tools/evi/seed-b7.sql" 2>/dev/null
log "SEED_DONE"

LOGIN_BODY=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" -d '{"username":"operator01","password":"S3cret!Passw0rd"}')
TOKEN_JWT=$(echo "$LOGIN_BODY" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
chk "登录" "1" "$([ -n "$TOKEN_JWT" ] && echo 1 || echo 0)"
[ -n "$TOKEN_JWT" ] || { log "FATAL: 登录失败：$LOGIN_BODY"; exit 5; }

# 每次调用后：LAST_STATUS / LAST_BODY / LAST_REPLAY
post_run(){
  LAST_STATUS=$(curl -s --noproxy '*' -X POST \
    "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $TOKEN_JWT" \
    -H "Idempotency-Key: $1" -d "$2" \
    -D "$OUT_DIR/last.headers" -o "$OUT_DIR/last.body" -w '%{http_code}')
  LAST_BODY=$(cat "$OUT_DIR/last.body")
  LAST_REPLAY=$(grep -i '^Idempotent-Replayed:' "$OUT_DIR/last.headers" 2>/dev/null | tr -d '\r' | awk '{print $2}')
}
code_of(){ echo "$1" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p'; }
runid_of(){ echo "$1" | grep -o '"run_id":[0-9]*' | head -1 | cut -d: -f2; }
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 \
       -e "USE shoppilot; $1" 2>/dev/null | tr -d '\r'; }

BODY_A='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9004,'\
'"date_from":"2026-09-16","date_to":"2026-10-13",'\
'"params":{"window_days":28,"min_days":7,"enable_llm":false},'\
'"algorithm_version":"avg-ma-3sigma-v1","prompt_version":"explain_risk.v1","model_name":"gpt-4o-mini"}'

# ---------------------------------------------------------------- T1 同键同体
sep; log "=== T1 同键同体 → 202 + Idempotent-Replayed ==="
post_run "rb151-idem" "$BODY_A"
R1_STATUS=$LAST_STATUS; R1_BODY=$LAST_BODY; R1_REPLAY=$LAST_REPLAY; R1_ID=$(runid_of "$R1_BODY")
log "R1_STATUS=$R1_STATUS R1_REPLAY=[${R1_REPLAY}] R1_BODY=$R1_BODY"

post_run "rb151-idem" "$BODY_A"
R2_STATUS=$LAST_STATUS; R2_BODY=$LAST_BODY; R2_REPLAY=$LAST_REPLAY; R2_ID=$(runid_of "$R2_BODY")
log "R2_STATUS=$R2_STATUS R2_REPLAY=[${R2_REPLAY}] R2_BODY=$R2_BODY"

chk "T1 首次 HTTP" "202" "$R1_STATUS"
chk "T1 首次无重放头" "" "$R1_REPLAY"
chk "T1 重放 HTTP" "202" "$R2_STATUS"
chk "T1 重放头" "true" "$R2_REPLAY"
chk "T1 同一 run_id" "$R1_ID" "$R2_ID"
chk "T1 库内 1 行" "1" "$(q "SELECT COUNT(*) FROM analysis_runs WHERE idempotency_key='rb151-idem';")"

# ---------------------------------------------------------------- T2 改 date_from
sep; log "=== T2 同键仅改 date_from → 409 COM-0009（R108）==="
BODY_B='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9004,'\
'"date_from":"2026-09-17","date_to":"2026-10-13",'\
'"params":{"window_days":28,"min_days":7,"enable_llm":false},'\
'"algorithm_version":"avg-ma-3sigma-v1","prompt_version":"explain_risk.v1","model_name":"gpt-4o-mini"}'
post_run "rb151-idem" "$BODY_B"
log "R3_STATUS=$LAST_STATUS R3_BODY=$LAST_BODY"
chk "T2 HTTP" "409" "$LAST_STATUS"
chk "T2 code" "COM-0009" "$(code_of "$LAST_BODY")"
chk "T2 未新建" "1" "$(q "SELECT COUNT(*) FROM analysis_runs WHERE idempotency_key='rb151-idem';")"

# ---------------------------------------------------------------- T3 CATEGORY 含引号
sep; log "=== T3 CATEGORY category=A\"B → 202 + 合法 JSON（R109）==="
BODY_C='{"scope_type":"CATEGORY","scope_json":{"category":"A\"B"},"warehouse_id":9004,'\
'"date_from":"2026-09-16","date_to":"2026-10-13","algorithm_version":"avg-ma-3sigma-v1",'\
'"prompt_version":"explain_risk.v1"}'
post_run "rb151-quote" "$BODY_C"
R4_STATUS=$LAST_STATUS; R4_BODY=$LAST_BODY; RID4=$(runid_of "$R4_BODY")
log "R4_STATUS=$R4_STATUS R4_BODY=$R4_BODY"
chk "T3 HTTP" "202" "$R4_STATUS"
chk "T3 scope_json JSON_VALID" "1" "$(q "SELECT JSON_VALID(scope_json) FROM analysis_runs WHERE id=$RID4;")"
chk "T3 category 原值" 'A"B' "$(q "SELECT JSON_UNQUOTE(JSON_EXTRACT(scope_json,'\$.category')) FROM analysis_runs WHERE id=$RID4;")"
chk "T3 响应无 AI- 码" "0" "$(echo "$R4_BODY" | grep -c 'AI-')"

post_run "rb151-quote" "$BODY_C"
chk "T3 同键同体重放" "true" "$LAST_REPLAY"

BODY_C2='{"scope_type":"CATEGORY","scope_json":{"category":"A\"C"},"warehouse_id":9004,'\
'"date_from":"2026-09-16","date_to":"2026-10-13","algorithm_version":"avg-ma-3sigma-v1",'\
'"prompt_version":"explain_risk.v1"}'
post_run "rb151-quote" "$BODY_C2"
log "R6_STATUS=$LAST_STATUS R6_BODY=$LAST_BODY"
chk "T3 改类目 HTTP" "409" "$LAST_STATUS"
chk "T3 改类目 code" "COM-0009" "$(code_of "$LAST_BODY")"

# ---------------------------------------------------------------- T4 ANL-7001
sep; log "=== T4 同范围不同键 → 409 ANL-7001（回归）==="
post_run "rb151-conflict" "$BODY_A"
log "R7_STATUS=$LAST_STATUS R7_BODY=$LAST_BODY"
chk "T4 HTTP" "409" "$LAST_STATUS"
chk "T4 code" "ANL-7001" "$(code_of "$LAST_BODY")"

# ---------------------------------------------------------------- T5 AI-6002
sep; log "=== T5 假对端 AI-6002 → 409 ANL-7012 + run FAILED（回归）==="
BODY_D='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9003]},"warehouse_id":9004,'\
'"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},'\
'"algorithm_version":"avg-ma-3sigma-v1","prompt_version":"explain_risk.v1","model_name":"trigger-ai-6002"}'
post_run "rb151-ai6002" "$BODY_D"
RB8=$LAST_BODY
log "R8_STATUS=$LAST_STATUS R8_BODY=$RB8"
chk "T5 HTTP" "409" "$LAST_STATUS"
chk "T5 code" "ANL-7012" "$(code_of "$RB8")"
chk "T5 响应无 AI- 码" "0" "$(echo "$RB8" | grep -c 'AI-')"
chk "T5 run 状态" "FAILED" "$(q "SELECT status FROM analysis_runs WHERE idempotency_key='rb151-ai6002';")"
chk "T5 run error_code" "ANL-7012" "$(q "SELECT error_code FROM analysis_runs WHERE idempotency_key='rb151-ai6002';")"
chk "T5 0 条 PENDING 悬挂" "0" "$(q "SELECT COUNT(*) FROM analysis_runs WHERE status='PENDING';")"
chk "T5 失败审计" "FAILURE" "$(q "SELECT result FROM audit_logs WHERE action='ANALYSIS_RUN_FINISH' AND biz_no=(SELECT run_no FROM analysis_runs WHERE idempotency_key='rb151-ai6002');")"

# ---------------------------------------------------------------- 出站与日志
sep; log "=== 假对端收包与日志 ==="
FAKE_COUNT=$(grep -c 'FAKE_AI POST' "$AI_LOG")
log "FAKE_AI_REQUESTS=$FAKE_COUNT"
# 只有「新建」才会真正发起：T1 首建 + T3 建 + T5 建 = 3；重放/异体/冲突都在 Java 侧拦截
chk "假对端收到 3 次发起（T1 + T3 + T5）" "3" "$FAKE_COUNT"
chk "日志记录 AI-6002 原码" "1" "$(grep -c 'AI-6002' "$APP_LOG" || true)"
log "--- 日志片段（AI-6002）---"
grep 'AI-6002' "$APP_LOG" | tail -2 | tee -a "$EVI"

sep
log "RB151_CHECK_TOTAL=$((PASS+FAIL))"
log "RB151_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB151_RESULT=ALL PASS"; else log "RB151_RESULT=HAS FAILURES"; fi
exit 0
