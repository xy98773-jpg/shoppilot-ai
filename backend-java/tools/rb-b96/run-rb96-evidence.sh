#!/usr/bin/env bash
# =============================================================================
# B9.6（R74 回修）项目负责人【独立复核】脚本
#
# 与 ../b9.6/ （开发取证）互不替代：
#   开发证「修好了」（W1 差额 0 无头 / W2 调整重放带头 / W3 冲销重放带头）
#   本脚本证「没有修漏、也没有反向误杀、且相邻面未被带走」——故刻意加 5 组开发未覆盖的探针。
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器。
# 端口 3336 / 18116 —— 避开历史全部占用：
#   3319 / 3327–3335 与 18099 / 18107–18115。
#
# 组次：
#   P1  R74 正向复现：首次差额 0（target == on_hand）→ 201 + transaction_id null +
#       qty_delta 0.000 + 【无】Idempotent-Replayed + 不写流水 + #12 审计不增 + 余额不变
#   P2  反向防线：真重放【必须仍带头】（修 R74 不得退化成「重放也不带头」）
#   P3  R73 回归：冲销同键重放 200 + 同 transaction_id + 头 true
#   ---- 以下 5 组为开发 W1–W3 未覆盖、但 SSOT 明文或相邻面必查 ----
#   P4  同键异体（改 target）→ 409 INV-5003，且【错误响应不得带重放头】、余额不变
#   P5  差额 0 用【同一 key 再发一次】→ 仍 201 + txn null + 无头 + 不写流水 + 审计不增
#   P6  MANUAL_ADJUST（#11 通道）首次无头 / 重放带头 —— 开发只测了 COUNT_ADJUST
#   P7  错误响应无头：422 INV-5010（冲销 REVERSAL）+ 403 TEAM-2003（VIEWER）
#   P8  【探查】差额 0 不落幂等记录 ⇒ 余额变化后同 key 同 target 是否会真落账（契约 §6.3）
#   P9  R75 口径回归：同 key 改 reason 仍判重放（头 true、不新增流水）—— 本批不得收紧
#
# 用法：bash backend-java/tools/rb-b96/run-rb96-evidence.sh
# 产物：run-> evidence-rb96.txt（断言，入库）｜*.log（中间产物，gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4，否则中文断言字面量退化成 '?' 产生假 FAIL。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb96.txt"
APP_LOG="$OUT_DIR/evidence-rb96-app.log"
HDR_FILE="$OUT_DIR/evidence-rb96-headers.log"
MYSQL_CT="rb96-mysql"
MYSQL_PORT=3336
APP_PORT=18116
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"; : > "$APP_LOG"; : > "$HDR_FILE"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found (先执行 ./mvnw -B verify 或 package)"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "REVIEWER=项目负责人（独立复核，不采信交付自报）"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  pkill -f "server.port=$APP_PORT" 2>/dev/null || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- 独立 MySQL
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

# ---------------------------------------------------------------- 独立 app
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-rb96-app.log)"

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

FAIL=0; NOTES=""
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi; }
note(){ log "NOTE $1 => $2"; NOTES="$NOTES
  - $1: $2"; }
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
body(){ echo "${1%|*}"; }
st(){ echo "$1" | sed 's/.*|//'; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jqs(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed 's/.*:"//;s/"$//'; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
adj(){ curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"; }
rev(){ curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$3/reverse" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" \
    -d "{\"reason\":\"$4\"}"; }
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
bal(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=$1"; }
txncount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
txncount_sku(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=$1"; }
rcount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1'"; }

OP=$(token operator01); OWN=$(token owner01); VW=$(token viewer01)
log "TOKENS op=${OP:0:8} own=${OWN:0:8} viewer=${VW:0:8}"
[ -n "$OP" ] && [ -n "$OWN" ] || { log "FATAL login failed"; exit 5; }

# ============================================================ P1 R74 正向复现
sep; log "P1 R74 正向：SKU-A 盘点 target 10.000 == 当前 10.000 → 201 + txn null + qty_delta 0.000 + 无头"
A0=$(bal 9002); T0=$(txncount); C12=$(cnt INVENTORY_COUNT_ADJUST)
log "P1-前置 balance=$A0 txn=$T0 #12=$C12"
R=$(adj "$OP" "rb96-p1-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"10.000\",\"reason\":\"盘点无差异\"}")
log "P1-http=$(st "$R")"; log "P1-body=$(body "$R")"; log "P1-replay_header=[$(hdr Idempotent-Replayed)]"
chk "P1-http"                 "201" "$(st "$R")"
chk "P1-code"                 "OK"  "$(code "$(body "$R")")"
chk "P1-transaction_id为null" ""    "$(jqn "$(body "$R")" transaction_id)"
chk "P1-qty_delta为0.000"     "0.000" "$(jqs "$(body "$R")" qty_delta)"
chk "P1-无Idempotent-Replayed" ""   "$(hdr Idempotent-Replayed)"
chk "P1-不写流水"              "$T0" "$(txncount)"
chk "P1-#12审计不增"           "$C12" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "P1-余额不变"              "$A0" "$(bal 9002)"

# ============================================================ P5 差额 0 同键再发
sep; log "P5 差额 0 用同一 key（rb96-p1-zero）再发一次 → 仍无头、不写流水、审计不增"
T1=$(txncount); C12B=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb96-p1-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"10.000\",\"reason\":\"盘点无差异\"}")
log "P5-http=$(st "$R") header=[$(hdr Idempotent-Replayed)] txn=[$(jqn "$(body "$R")" transaction_id)]"
chk "P5-http"                  "201" "$(st "$R")"
chk "P5-txn仍为null"           ""    "$(jqn "$(body "$R")" transaction_id)"
chk "P5-仍无重放头"            ""    "$(hdr Idempotent-Replayed)"
chk "P5-仍不写流水"            "$T1" "$(txncount)"
chk "P5-审计仍不增"            "$C12B" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "P5-余额仍不变"            "10.000" "$(bal 9002)"

# ============================================================ P9 R75 口径回归
sep; log "P9 R75 回归：SKU-A target 8.000 → 落账；同 key 改 reason、target 不变 → 仍判重放（头 true）"
C12C=$(cnt INVENTORY_COUNT_ADJUST); TA0=$(txncount_sku 9002)
R=$(adj "$OP" "rb96-p9-r75" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"8.000\",\"reason\":\"首次原因\"}")
P9TXN=$(jqn "$(body "$R")" transaction_id)
log "P9-首次http=$(st "$R") txn=$P9TXN header=[$(hdr Idempotent-Replayed)]"
chk "P9-首次http"      "201" "$(st "$R")"
chk "P9-首次无头"      ""    "$(hdr Idempotent-Replayed)"
chk "P9-首次余额"      "8.000" "$(bal 9002)"
R=$(adj "$OP" "rb96-p9-r75" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"8.000\",\"reason\":\"改了个原因\"}")
log "P9-改reason重放 http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)]"
chk "P9-同txn"          "$P9TXN" "$(jqn "$(body "$R")" transaction_id)"
chk "P9-仍判重放带头"   "true"   "$(hdr Idempotent-Replayed)"
chk "P9-不新增流水"     "$((TA0 + 1))" "$(txncount_sku 9002)"
chk "P9-审计只+1"       "$((C12C + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
note "P9 R75 现状" "同 key 改 reason 仍返 200/201 判为重放（比对不含 reason）—— 与本批回修单「不得收紧」一致，R75 维持登记不修"

# ============================================================ P8 幂等记录缺失探查
sep; log "P8 【探查】差额 0 不落幂等记录 ⇒ 余额变化后同 key 同 target 是否真落账（§6.3 同键不重复执行）"
# SKU-A 当前 8.000。先做一次差额 0（key=rb96-p8）
P8T0=$(txncount_sku 9002); P8C=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb96-p8-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"8.000\",\"reason\":\"无差异盘点\"}")
log "P8-a 差额0 http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)] bal=$(bal 9002)"
chk "P8a-差额0无头"    ""      "$(hdr Idempotent-Replayed)"
chk "P8a-txn为null"    ""      "$(jqn "$(body "$R")" transaction_id)"
chk "P8a-无幂等记录"   "0"     "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='rb96-p8-zero'")"
# 用 MANUAL_ADJUST 把余额从 8 改成 5
R=$(adj "$OWN" "rb96-p8-mv" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"-3.000\",\"reason\":\"制造余额差\"}")
log "P8-b 手工调整 http=$(st "$R") bal=$(bal 9002)"
chk "P8b-余额变5"      "5.000" "$(bal 9002)"
# 再发同一 key、同一 target（请求体与 P8-a 完全一致）
R=$(adj "$OP" "rb96-p8-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"8.000\",\"reason\":\"无差异盘点\"}")
P8HTTP=$(st "$R"); P8TXN=$(jqn "$(body "$R")" transaction_id); P8HDR=$(hdr Idempotent-Replayed)
log "P8-c 同键同体重发 http=$P8HTTP txn=[$P8TXN] header=[$P8HDR] bal=$(bal 9002) 流水数=$(txncount_sku 9002) #12=$(cnt INVENTORY_COUNT_ADJUST)"
chk "P8c-http"         "201"   "$P8HTTP"
if [ -n "$P8TXN" ]; then
  note "P8 幂等键未落库（潜在缺陷）" "首次差额 0 不写流水亦不落幂等记录；余额变化后同 key 同请求体【真落账】（txn=$P8TXN，余额 $(bal 9002)，流水 $(txncount_sku 9002) 条）。违反 API_CONTRACT §6.3「同幂等键 + 请求体一致 → 返回首次结果、不重复执行」。非本批引入（B6 差额 0 分支固有），建议登记新 R 项"
else
  note "P8 幂等键未落库" "同键重发仍走差额 0 分支（未落账），txn=null、无头；仍无幂等记录，属契约灰区"
fi

# ============================================================ P4 同键异体
sep; log "P4 同键异体：SKU-B 首次 target 9.000 落账，同 key 改 target 7.000 → 409 INV-5003 且【无】重放头"
CB0=$(bal 9003); CB12=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb96-p4-diff" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"9.000\",\"reason\":\"首盘\"}")
log "P4-首次http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)]"
chk "P4-首次http"     "201"   "$(st "$R")"
chk "P4-首次余额"     "9.000" "$(bal 9003)"
R=$(adj "$OP" "rb96-p4-diff" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"首盘\"}")
log "P4-异体http=$(st "$R") code=$(code "$(body "$R")") header=[$(hdr Idempotent-Replayed)]"
chk "P4-异体http"       "409"       "$(st "$R")"
chk "P4-异体错误码"     "INV-5003"  "$(code "$(body "$R")")"
chk "P4-错误响应无头"   ""          "$(hdr Idempotent-Replayed)"
chk "P4-余额不变"       "9.000"     "$(bal 9003)"
chk "P4-审计不增"       "$((CB12 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"

# ============================================================ P2 反向防线（真重放必须带头）
sep; log "P2 反向防线：COUNT_ADJUST 真重放必须带 Idempotent-Replayed: true"
R=$(adj "$OP" "rb96-p2-replay" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"6.000\",\"reason\":\"月度盘点\"}")
P2TXN=$(jqn "$(body "$R")" transaction_id)
log "P2-首次http=$(st "$R") txn=$P2TXN header=[$(hdr Idempotent-Replayed)]"
chk "P2-首次无头"   ""     "$(hdr Idempotent-Replayed)"
chk "P2-首次有txn"  "1"    "$([ -n "$P2TXN" ] && echo 1 || echo 0)"
R=$(adj "$OP" "rb96-p2-replay" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"6.000\",\"reason\":\"月度盘点\"}")
log "P2-重放http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)]"
chk "P2-重放http"   "201"   "$(st "$R")"
chk "P2-同txn"      "$P2TXN" "$(jqn "$(body "$R")" transaction_id)"
chk "P2-重放头true" "true"  "$(hdr Idempotent-Replayed)"
chk "P2-余额不再变" "6.000" "$(bal 9003)"

# ============================================================ P6 MANUAL_ADJUST 头
sep; log "P6 MANUAL_ADJUST（#11 通道）：首次无头；同键重放带头 + 同 txn"
CM0=$(cnt INVENTORY_MANUAL_ADJUST); TB0=$(txncount_sku 9003)
R=$(adj "$OWN" "rb96-p6-manual" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"2.000\",\"reason\":\"手工补差\"}")
P6TXN=$(jqn "$(body "$R")" transaction_id)
log "P6-首次http=$(st "$R") txn=$P6TXN header=[$(hdr Idempotent-Replayed)] bal=$(bal 9003)"
chk "P6-首次http"    "201"   "$(st "$R")"
chk "P6-首次无头"    ""      "$(hdr Idempotent-Replayed)"
chk "P6-首次余额"    "8.000" "$(bal 9003)"
chk "P6-#11审计+1"   "$((CM0 + 1))" "$(cnt INVENTORY_MANUAL_ADJUST)"
R=$(adj "$OWN" "rb96-p6-manual" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"2.000\",\"reason\":\"手工补差\"}")
log "P6-重放http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)]"
chk "P6-重放http"    "201"   "$(st "$R")"
chk "P6-同txn"       "$P6TXN" "$(jqn "$(body "$R")" transaction_id)"
chk "P6-重放头true"  "true"  "$(hdr Idempotent-Replayed)"
chk "P6-流水只+1"    "$((TB0 + 1))" "$(txncount_sku 9003)"
chk "P6-审计仍+1"    "$((CM0 + 1))" "$(cnt INVENTORY_MANUAL_ADJUST)"

# ============================================================ P3 冲销重放（R73 回归）
sep; log "P3 R73 回归：冲销 seed-init-9005（SKU-C 10.000 → 0.000）首次 200 无头；同键重放 200 + 头 true"
ORIG=$(txnid seed-init-9005); RC0=$(rcount); C13=$(cnt INVENTORY_REVERSE)
log "P3-原流水id=$ORIG"
R=$(rev "$OWN" "rb96-p3-rev" "$ORIG" "导入错误")
P3TXN=$(jqn "$(body "$R")" transaction_id)
log "P3-首次http=$(st "$R") txn=$P3TXN header=[$(hdr Idempotent-Replayed)] bal=$(bal 9005)"
chk "P3-首次http"    "200"   "$(st "$R")"
chk "P3-首次无头"    ""      "$(hdr Idempotent-Replayed)"
chk "P3-首次余额0"   "0.000" "$(bal 9005)"
chk "P3-REVERSAL+1"  "$((RC0 + 1))" "$(rcount)"
chk "P3-#13审计+1"   "$((C13 + 1))" "$(cnt INVENTORY_REVERSE)"
R=$(rev "$OWN" "rb96-p3-rev" "$ORIG" "导入错误")
log "P3-重放http=$(st "$R") txn=[$(jqn "$(body "$R")" transaction_id)] header=[$(hdr Idempotent-Replayed)]"
chk "P3-重放http"    "200"   "$(st "$R")"
chk "P3-同txn"       "$P3TXN" "$(jqn "$(body "$R")" transaction_id)"
chk "P3-重放头true"  "true"  "$(hdr Idempotent-Replayed)"
chk "P3-REVERSAL仍1" "$((RC0 + 1))" "$(rcount)"
chk "P3-审计仍1条"   "$((C13 + 1))" "$(cnt INVENTORY_REVERSE)"

# ============================================================ P7 错误响应不得带重放头
sep; log "P7 错误响应不得带重放头：422 INV-5010（冲销 REVERSAL 流水）+ 403 TEAM-2003（VIEWER）"
R=$(rev "$OWN" "rb96-p7-reversal" "$P3TXN" "再冲一次")
log "P7-422 http=$(st "$R") code=$(code "$(body "$R")") header=[$(hdr Idempotent-Replayed)]"
chk "P7-5010http"   "422"      "$(st "$R")"
chk "P7-5010码"     "INV-5010" "$(code "$(body "$R")")"
chk "P7-5010无头"   ""         "$(hdr Idempotent-Replayed)"
R=$(adj "$VW" "rb96-p7-viewer" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"1.000\",\"reason\":\"越权试探\"}")
log "P7-403 http=$(st "$R") code=$(code "$(body "$R")") header=[$(hdr Idempotent-Replayed)]"
chk "P7-403http"    "403"      "$(st "$R")"
chk "P7-403码"      "TEAM-2003" "$(code "$(body "$R")")"
chk "P7-403无头"    ""         "$(hdr Idempotent-Replayed)"
chk "P7-越权未改余额" "8.000"  "$(bal 9003)"

# ============================================================ 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
log "RB96_CHECK_TOTAL=$TOTAL"
log "RB96_FAIL_COUNT=$FAIL"
log "RB96_NOTE_COUNT=$(grep -c '^NOTE ' "$LOG")"
[ "$FAIL" = "0" ] && log "RB96_RESULT=ALL PASS" || log "RB96_RESULT=HAS FAILURES$FAILED_LIST"
log "审计全景 team7：COUNT_ADJUST=$(cnt INVENTORY_COUNT_ADJUST) MANUAL_ADJUST=$(cnt INVENTORY_MANUAL_ADJUST) REVERSE=$(cnt INVENTORY_REVERSE)"
log "余额终态：SKU-A=$(bal 9002) SKU-B=$(bal 9003) SKU-C=$(bal 9005)"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
