#!/usr/bin/env bash
# =============================================================================
# B9.6（R74：Idempotent-Replayed 头误标）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器。
# 端口 3335 / 18115 —— 刻意错开历史批次已用过的
#   3319 / 3327–3334 与 18099 / 18107–18114。
#
# 组次（对应回修单「四、门槛 · 真栈 3 组」）：
#   W1  首次差额 0（目标 == 当前余额 10.000）→ 201、transaction_id 为 null、
#       【无】Idempotent-Replayed 头、不写流水、#12 审计不增、余额不变
#       —— 这是 R74 的正向修复点：修复前该请求会被误标 true
#   W2  同键重放（有实际变更的那笔 10.000 → 6.000）→ 首次 201 无头；重放 201 +
#       同一 transaction_id + 头 true（★ 反向防线：修 R74 不得变成「重放也不带头」）
#   W3  冲销首次 → 200 无头；同键重放 → 200 + 头 true + 同一 transaction_id
#       （R73 回归不退化）
#
# 用法：bash backend-java/tools/b9.6/run-b96-evidence.sh
# 产物：backend-java/tools/b9.6/evidence-b96.txt          ← 断言日志（入库）
#       backend-java/tools/b9.6/evidence-b96-app.log      ← 应用 stdout（*.log 已 gitignore）
#       backend-java/tools/b9.6/evidence-b96-headers.log  ← 最近一次响应头（*.log 已 gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（B8 脚本第一版即因此丢了整段）。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b96.txt"
APP_LOG="$OUT_DIR/evidence-b96-app.log"
HDR_FILE="$OUT_DIR/evidence-b96-headers.log"
MYSQL_CT="b96-mysql"
MYSQL_PORT=3335
APP_PORT=18115
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found (先执行 ./mvnw -B verify 或 package)"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  pkill -f "server.port=$APP_PORT" 2>/dev/null || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- 起独立 MySQL
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$APP_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT, 独立容器 $MYSQL_CT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

# ---------------------------------------------------------------- 起独立 app
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b96-app.log)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

MYSQL_FLAGS=(--default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot)
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot < "$EVI/seed-b95.sql" >> "$APP_LOG" 2>&1 \
  && log "SEED_OK (seed-b95.sql：团队 7，仓库 9004，SKU-A=9002 / SKU-B=9003 / SKU-C=9005 各 10.000)"

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
adj(){ # bearer idem json -> body|http（响应头落 $HDR_FILE）
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"
}
rev(){ # bearer idem txnId reason -> body|http（响应头落 $HDR_FILE）
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$3/reverse" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" \
    -d "{\"reason\":\"$4\"}"
}
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1'"; }
bal(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=$1"; }
txncount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
txncount_sku(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=$1"; }
rcount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'"; }

OP=$(token operator01); OWN=$(token owner01)
log "TOKENS op=${OP:0:8} own=${OWN:0:8}"

# ============================================================== W1 R74 差额 0 不带重放头
sep; log "W1 R74 首次差额 0：SKU-A 目标 10.000 == 当前 10.000 → 201 + transaction_id null + 【无】重放头"
BAL0=$(bal 9002); TXN0=$(txncount); A12_0=$(cnt INVENTORY_COUNT_ADJUST)
log "W1-前置 balance=$BAL0 txn=$TXN0 #12=$A12_0"
R=$(adj "$OP" "b96-w1-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"10.000\",\"reason\":\"盘点无差异\"}")
log "W1-http=$(st "$R")"
log "W1-body=$(body "$R")"
log "W1-replay_header=[$(hdr Idempotent-Replayed)]"
chk "W1-http"                  "201" "$(st "$R")"
chk "W1-transaction_id为null"  ""    "$(jqn "$(body "$R")" transaction_id)"
chk "W1-无Idempotent-Replayed" ""    "$(hdr Idempotent-Replayed)"
chk "W1-不写流水"              "$TXN0" "$(txncount)"
chk "W1-#12审计不增"           "$A12_0" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "W1-余额不变"              "$BAL0" "$(bal 9002)"

# ============================================================== W2 同键重放（有实际变更）
sep; log "W2 反向防线：SKU-B 10.000 → 6.000 首次无头；同键重放 201 + 同一 transaction_id + 头 true"
BALB0=$(bal 9003); A12_1=$(cnt INVENTORY_COUNT_ADJUST); SKUB0=$(txncount_sku 9003)
W2BODY="{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"6.000\",\"reason\":\"月度盘点\"}"
R=$(adj "$OP" "b96-w2-replay" "$W2BODY")
W2TXN=$(jqn "$(body "$R")" transaction_id)
log "W2-首次http=$(st "$R") txn=$W2TXN header=[$(hdr Idempotent-Replayed)]"
chk "W2-首次http"       "201"  "$(st "$R")"
chk "W2-首次有流水id"    "1"    "$([ -n "$W2TXN" ] && echo 1 || echo 0)"
chk "W2-首次无重放头"    ""     "$(hdr Idempotent-Replayed)"
chk "W2-首次余额"        "6.000" "$(bal 9003)"
chk "W2-首次#12审计+1"   "$((A12_1 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
R2=$(adj "$OP" "b96-w2-replay" "$W2BODY")
W2TXN2=$(jqn "$(body "$R2")" transaction_id)
log "W2-重放http=$(st "$R2") txn=$W2TXN2 header=[$(hdr Idempotent-Replayed)]"
chk "W2-重放http"        "201"   "$(st "$R2")"
chk "W2-同一流水id"      "$W2TXN" "$W2TXN2"
chk "W2-重放头true"      "true"  "$(hdr Idempotent-Replayed)"
chk "W2-余额不再变"      "6.000" "$(bal 9003)"
chk "W2-重放#12不增"     "$((A12_1 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "W2-SKU-B只有1条流水" "$((SKUB0 + 1))" "$(txncount_sku 9003)"

# ============================================================== W3 冲销首次 / 重放（R73 回归）
sep; log "W3 R73 回归：冲销 seed-init-9005（SKU-C 10.000 → 0.000）首次 200 无头；同键重放 200 + 头 true"
ORIG=$(txnid seed-init-9005)
A13_0=$(cnt INVENTORY_REVERSE); RC0=$(rcount)
R=$(rev "$OWN" "b96-w3-replay" "$ORIG" "导入错误")
W3TXN=$(jqn "$(body "$R")" transaction_id)
log "W3-首次http=$(st "$R") txn=$W3TXN header=[$(hdr Idempotent-Replayed)]"
chk "W3-首次http"      "200"   "$(st "$R")"
chk "W3-首次无重放头"   ""      "$(hdr Idempotent-Replayed)"
chk "W3-首次余额"      "0.000" "$(bal 9005)"
chk "W3-REVERSAL+1"   "$((RC0 + 1))" "$(rcount)"
chk "W3-#13审计+1"    "$((A13_0 + 1))" "$(cnt INVENTORY_REVERSE)"
R2=$(rev "$OWN" "b96-w3-replay" "$ORIG" "导入错误")
W3TXN2=$(jqn "$(body "$R2")" transaction_id)
log "W3-重放http=$(st "$R2") txn=$W3TXN2 header=[$(hdr Idempotent-Replayed)]"
chk "W3-重放http"      "200"   "$(st "$R2")"
chk "W3-同一流水id"    "$W3TXN" "$W3TXN2"
chk "W3-重放头true"    "true"  "$(hdr Idempotent-Replayed)"
chk "W3-REVERSAL仍1"   "$((RC0 + 1))" "$(rcount)"
chk "W3-#13审计仍1条"  "$((A13_0 + 1))" "$(cnt INVENTORY_REVERSE)"
chk "W3-余额只回退一次" "0.000" "$(bal 9005)"

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
log "B96_CHECK_TOTAL=$TOTAL"
log "B96_FAIL_COUNT=$FAIL"
[ "$FAIL" = "0" ] && log "B96_RESULT=ALL PASS" || log "B96_RESULT=HAS FAILURES$FAILED_LIST"
log "审计全景 team7：COUNT_ADJUST=$(cnt INVENTORY_COUNT_ADJUST) MANUAL_ADJUST=$(cnt INVENTORY_MANUAL_ADJUST) REVERSE=$(cnt INVENTORY_REVERSE)"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
