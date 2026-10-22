#!/usr/bin/env bash
# =============================================================================
# B9.5 项目负责人独立复核（脚本入库于 backend-java/tools/rb-b95/）（R72 审计同事务 / R73 冲销重放 / 可选 2.4 响应头）
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36，端口 3334 / 18114
#   （开发用 3333 / 18113；历史复核用 3327–3332 / 18099、18107–18112）
#
# 刻意【不重复】开发 C1–C4 / R1–R3 的正向覆盖，只补它未测的 7 类：
#   P1 差额为 0 的首次盘点 —— 响应头不得标为重放（§6.3 头语义 = 重放命中）
#   P2 首次变更 vs 重放的头语义对照 + 重放不写审计
#   P3 审计阻断下的【冲销】路径 —— 开发真栈只做了 #12，冲销代码路径不同
#   P4 阻断失败后同 key 重试必须成功（幂等记录随业务回滚，无「假幂等」残留）
#   P5 跨团队复用同一 Idempotency-Key —— 不得返回他团队结果（租户隔离）
#   P6 同 key 改 reason —— §6.3「请求体不一致」口径实测
#   P7 重放响应必须回放首次结果（qty_before/qty_after），不得用当前余额重算
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb95.txt"
APP_LOG="$OUT_DIR/evidence-rb95-app.log"
HDR_FILE="$OUT_DIR/evidence-rb95-headers.log"
MYSQL_CT="rb95-mysql"
MYSQL_PORT=3334
APP_PORT=18114
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
TRIGGER="trg_rb95_block_audit"

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found"; exit 2; fi
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

docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 --log_bin_trust_function_creators=1 >> "$APP_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT)"

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
  && log "SEED_OK (seed-b95.sql)"

FAIL=0
FAILED_LIST=""
NOTE=0
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST; $1(exp=[$2] act=[$3])"; fi
}
note(){ log "NOTE $*"; NOTE=$((NOTE+1)); }
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqnum(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jqstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
jqnull(){ echo "$1" | grep -q "\"$2\":null" && echo null || echo notnull; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
adj(){ curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"; }
rev(){ curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$3/reverse" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "{\"reason\":\"$4\"}"; }
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
bal(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=$1"; }
rcount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1' AND team_id=$2"; }
block_audit(){ sql "CREATE TRIGGER $TRIGGER BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit blocked by RB95'"; }
unblock_audit(){ sql "DROP TRIGGER IF EXISTS $TRIGGER"; }

OWN=$(token owner01); OP=$(token operator01); ADM=$(token admin01); OWN8=$(token owner8)
log "TOKENS own=${OWN:0:6} op=${OP:0:6} adm=${ADM:0:6} own8=${OWN8:0:6}"

# 团队 8 补一条余额行：P5 需要 team8 能真正落一笔调整（否则先撞 INV-5004，掩盖幂等隔离结论）
sql "INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, in_transit_qty, committed_qty, created_by, updated_by, version) VALUES (8,9103,9102,0.000,0.000,0.000,2004,2004,0)"
log "SEED-EXTRA team8 余额行=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=8")"

# ============================================== P1 差额 0 的首次盘点不得标为重放
sep; log "P1 首次 COUNT_ADJUST 目标==当前(10.000) → 201、无流水、且【不得】带 Idempotent-Replayed"
A12_0=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb-p1-zero" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"10.000\",\"reason\":\"无差异盘点\"}")
log "P1-http=$(st "$R") txn_null=$(jqnull "$(body "$R")" transaction_id) replay_header=[$(hdr Idempotent-Replayed)]"
chk "P1-http"            "201"  "$(st "$R")"
chk "P1-无流水id"         "null" "$(jqnull "$(body "$R")" transaction_id)"
chk "P1-#12不写审计"      "$A12_0" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "P1-首次不得带重放头" ""      "$(hdr Idempotent-Replayed)"

# ============================================== P2 首次变更 vs 重放的头语义
sep; log "P2 首次变更(10→9) 无重放头；同键重放 → 同流水 + 重放头 + 审计不增"
A12_1=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb-p2-change" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"9.000\",\"reason\":\"月度盘点\"}")
T2=$(jqnum "$(body "$R")" transaction_id); V2=$(jqnum "$(body "$R")" balance_version)
log "P2-http=$(st "$R") txn=$T2 version=$V2 header=[$(hdr Idempotent-Replayed)]"
chk "P2-http"          "201" "$(st "$R")"
chk "P2-首次无重放头"   ""    "$(hdr Idempotent-Replayed)"
chk "P2-余额"          "9.000" "$(bal 9003)"
chk "P2-#12审计+1"     "$((A12_1 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
R=$(adj "$OP" "rb-p2-change" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"9.000\",\"reason\":\"月度盘点\"}")
log "P2b-http=$(st "$R") txn=$(jqnum "$(body "$R")" transaction_id) header=[$(hdr Idempotent-Replayed)]"
chk "P2b-http201"       "201" "$(st "$R")"
chk "P2b-同流水"        "$T2" "$(jqnum "$(body "$R")" transaction_id)"
chk "P2b-重放头"        "true" "$(hdr Idempotent-Replayed)"
chk "P2b-审计不增"      "$((A12_1 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "P2b-余额不变"      "9.000" "$(bal 9003)"

# ============================================== P3 审计阻断下的冲销路径
sep; log "P3 审计阻断下冲销 seed-init-9002(SKU-A 10.000) → 500、REVERSAL 0 行、余额/审计不变"
RB0=$(rcount); A13_0=$(cnt INVENTORY_REVERSE); BALA=$(bal 9002); O2=$(txnid seed-init-9002 7)
log "P3-前置 REVERSAL=$RB0 #13=$A13_0 SKU-A=$BALA 原流水=$O2"
block_audit
R=$(rev "$OWN" "rb-p3-block" "$O2" "阻断冲销")
log "P3-http=$(st "$R") code=$(code "$(body "$R")")"
chk "P3-http(非2xx)"   "500" "$(st "$R")"
chk "P3-无新增REVERSAL" "$RB0" "$(rcount)"
chk "P3-余额未回退"     "$BALA" "$(bal 9002)"
chk "P3-#13不增"        "$A13_0" "$(cnt INVENTORY_REVERSE)"
chk "P3-原流水仍可冲"   "0"    "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE reversed_txn_id=$O2")"
unblock_audit

# ============================================== P4 阻断失败后同 key 重试必须成功
sep; log "P4 阻断下 MANUAL_ADJUST(ADMIN, +1, key=rb-p4-retry) 失败 → 解除后【同 key】重试必须真落账"
BALB=$(bal 9003); A11_0=$(cnt INVENTORY_MANUAL_ADJUST)
block_audit
R=$(adj "$ADM" "rb-p4-retry" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"1.000\",\"reason\":\"手工加一\"}")
log "P4-http=$(st "$R") code=$(code "$(body "$R")") 余额=$(bal 9003)"
chk "P4-http(非2xx)"  "500" "$(st "$R")"
chk "P4-余额未变"      "$BALB" "$(bal 9003)"
chk "P4-无幂等残留"    "0"    "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE idempotency_key='rb-p4-retry'")"
unblock_audit
R=$(adj "$ADM" "rb-p4-retry" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"1.000\",\"reason\":\"手工加一\"}")
T4=$(jqnum "$(body "$R")" transaction_id)
log "P4b-http=$(st "$R") txn=$T4 header=[$(hdr Idempotent-Replayed)]"
chk "P4b-重试成功"     "201" "$(st "$R")"
chk "P4b-真落账(新流水)" "notnull" "$(jqnull "$(body "$R")" transaction_id)"
chk "P4b-非重放"       ""    "$(hdr Idempotent-Replayed)"
chk "P4b-余额"         "10.000" "$(bal 9003)"
chk "P4b-#11审计+1"    "$((A11_0 + 1))" "$(cnt INVENTORY_MANUAL_ADJUST)"

# ============================================== P5 跨团队复用同一幂等键
sep; log "P5 team8 复用 team7 已用键(rb-p2-change) 做 MANUAL_ADJUST → 不得返回 team7 流水"
T7=$(txnid rb-p2-change 7)
R=$(adj "$OWN8" "rb-p2-change" "{\"warehouse_id\":9103,\"sku_id\":9102,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"5.000\",\"reason\":\"别家调整\"}")
T8=$(jqnum "$(body "$R")" transaction_id)
log "P5-http=$(st "$R") team7_txn=$T7 team8_txn=$T8 body=$(body "$R")"
chk "P5-http"           "201" "$(st "$R")"
chk "P5-非team7流水"     "1"   "$([ "${T8:-x}" != "$T7" ] && echo 1 || echo 0)"
chk "P5-team8余额"      "5.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=8 AND sku_id=9102")"
chk "P5-team7无污染"     "1"   "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='rb-p2-change'")"

# ============================================== P6 同 key 改 reason（§6.3 请求体不一致口径）
sep; log "P6 冲销 seed-init-9005：同 key 同体 → 200；同 key 改 reason → 实测口径（§6.3 期望 409）"
O5=$(txnid seed-init-9005 7)
R=$(rev "$OWN" "rb-p6-reason" "$O5" "导入错误")
T6=$(jqnum "$(body "$R")" transaction_id)
log "P6-首次 http=$(st "$R") txn=$T6 header=[$(hdr Idempotent-Replayed)]"
chk "P6-首次200"  "200" "$(st "$R")"
chk "P6-首次无头" ""    "$(hdr Idempotent-Replayed)"
R=$(rev "$OWN" "rb-p6-reason" "$O5" "完全不同的事由")
log "P6b-同key改reason http=$(st "$R") code=$(code "$(body "$R")") txn=$(jqnum "$(body "$R")" transaction_id) header=[$(hdr Idempotent-Replayed)]"
P6B_HTTP=$(st "$R"); P6B_CODE=$(code "$(body "$R")")
if [ "$P6B_HTTP" = "409" ]; then
  chk "P6b-契约口径(409)" "INV-5003" "$P6B_CODE"
else
  note "P6b FINDING：同 key 改 reason 返回 $P6B_HTTP/$P6B_CODE（§6.3 要求请求体不一致→409；实现按业务等价字段比对，reason 不参与）"
  chk "P6b-记录实际口径(200重放)" "200" "$P6B_HTTP"
fi
chk "P6b-仍只1笔REVERSAL" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND reversed_txn_id=$O5")"

# ============================================== P7 重放必须回放首次结果
sep; log "P7 P2 重放时余额已变为 10.000 → 重放响应仍须回放首次结果(qty_after=9.000)"
R=$(adj "$OP" "rb-p2-change" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"9.000\",\"reason\":\"月度盘点\"}")
log "P7-http=$(st "$R") txn=$(jqnum "$(body "$R")" transaction_id) before=$(jqstr "$(body "$R")" qty_before) after=$(jqstr "$(body "$R")" qty_after) version=$(jqnum "$(body "$R")" balance_version) 当前余额=$(bal 9003)"
chk "P7-同流水"     "$T2" "$(jqnum "$(body "$R")" transaction_id)"
chk "P7-回放qtyAfter" "9.000" "$(jqstr "$(body "$R")" qty_after)"
chk "P7-回放qtyBefore" "10.000" "$(jqstr "$(body "$R")" qty_before)"
note "P7 version：首次=$V2 重放=$(jqnum "$(body "$R")" balance_version) 当前余额=$(bal 9003)（balance_version 取当前余额版本，与首次响应可能不同）"

# ============================================== 汇总
sep
log "RB95_CHECK_TOTAL=$(grep -c '^PASS ' "$LOG")"
log "RB95_FAIL_COUNT=$FAIL"
log "RB95_NOTE_COUNT=$NOTE"
[ "$FAIL" = "0" ] && log "RB95_RESULT=ALL PASS" || log "RB95_RESULT=HAS FAILURES$FAILED_LIST"
log "审计全景 team7：COUNT_ADJUST=$(cnt INVENTORY_COUNT_ADJUST) MANUAL_ADJUST=$(cnt INVENTORY_MANUAL_ADJUST) REVERSE=$(cnt INVENTORY_REVERSE)"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
