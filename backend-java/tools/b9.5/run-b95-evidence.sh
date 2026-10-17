#!/usr/bin/env bash
# =============================================================================
# B9.5（R72 审计事务耦合 + R73 冲销幂等重放）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器。
# 端口 3333 / 18113 —— 刻意错开历史批次已用过的
#   3319 / 3327 / 3328 / 3329 / 3330 / 3331 / 3332 与
#   18099 / 18107 / 18108 / 18109 / 18110 / 18111 / 18112。
#
# 组次（对应回修单「四、真栈 ≥4 组」）：
#   C1   R72：#12 盘点调整在「审计被 BEFORE INSERT 触发器阻断」时 →
#            非 2xx（500 COM-0002）、余额不变、流水不增、审计 0 行
#   C2   R72 反证：DROP TRIGGER 后同动作 → 201，且「余额 / 流水 / 审计」各 +1
#   C3   失败路径仍不写审计：INV-5006（目标为负）→ 409 且 #12 条数不变
#   C4   有意差异：#7 策略 upsert 在审计被阻断时仍 200 且策略落库（走 record 解耦通道）
#   R1   R73：同键同体重放 → 200 + 首次 transaction_id + Idempotent-Replayed: true，
#            REVERSAL 只有 1 行、#13 审计仍 1 条
#   R2   R73：同键不同体（换一笔原流水）→ 409 INV-5003，不新增冲销
#   R3   R73 回归：不同键二次冲销 → 409 INV-5009（拦截未被削弱）
#
# 用法：bash backend-java/tools/b9.5/run-b95-evidence.sh
# 产物：backend-java/tools/b9.5/evidence-b95.txt          ← 断言日志（入库）
#       backend-java/tools/b9.5/evidence-b95-app.log      ← 应用 stdout（*.log 已 gitignore）
#       backend-java/tools/b9.5/evidence-b95-headers.log  ← 最近一次响应头（*.log 已 gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（B8 脚本第一版即因此丢了整段）。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b95.txt"
APP_LOG="$OUT_DIR/evidence-b95-app.log"
HDR_FILE="$OUT_DIR/evidence-b95-headers.log"
MYSQL_CT="b95-mysql"
MYSQL_PORT=3333
APP_PORT=18113
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
TRIGGER="trg_b95_block_audit"

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
}
trap cleanup EXIT

# ---------------------------------------------------------------- 起独立 MySQL
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
# ★ --log_bin_trust_function_creators=1：MySQL 8 默认开 binlog，root 在容器内建触发器会报
#   ERROR 1419（You do not have the SUPER privilege and binary logging is enabled）。
#   C1/C4 依赖 BEFORE INSERT 触发器制造「审计写不进」，故在启动参数上放开（仅本取证容器）。
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 --log_bin_trust_function_creators=1 >> "$APP_LOG" 2>&1
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b95-app.log)"

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
pol_put(){ # bearer json
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X PUT "$BASE/api/v1/replenishment-policies" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"
}
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
af(){ sql "SELECT $2 FROM audit_logs WHERE action='$1' AND team_id=7 ORDER BY id DESC LIMIT 1"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1'"; }
bal(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=$1"; }
txncount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
rcount(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'"; }
block_audit(){ sql "CREATE TRIGGER $TRIGGER BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='audit blocked by B9.5 evidence'"; }
unblock_audit(){ sql "DROP TRIGGER IF EXISTS $TRIGGER"; }

OWN=$(token owner01); OP=$(token operator01); ADM=$(token admin01)
log "TOKENS own=${OWN:0:8} op=${OP:0:8} adm=${ADM:0:8}"

# ============================================================== C1 R72 审计阻断 → 业务回滚
sep; log "C1 R72 触发器阻断审计：COUNT_ADJUST SKU-A 10.000 → 7.000 必须整体失败"
BAL0=$(bal 9002); TXN0=$(txncount); A12_0=$(cnt INVENTORY_COUNT_ADJUST); AUD0=$(sql "SELECT COUNT(*) FROM audit_logs")
log "C1-前置 balance=$BAL0 txn=$TXN0 #12=$A12_0 audit_total=$AUD0"
block_audit
R=$(adj "$OP" "b95-c1-blocked" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"月度盘点\"}")
log "C1-http=$(st "$R") code=$(code "$(body "$R")")"
log "C1-body=$(body "$R")"
chk "C1-http(非2xx)" "500" "$(st "$R")"
chk "C1-code"        "COM-0002" "$(code "$(body "$R")")"
chk "C1-余额回滚"     "$BAL0" "$(bal 9002)"
chk "C1-流水回滚"     "$TXN0" "$(txncount)"
chk "C1-#12审计0行"   "$A12_0" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "C1-全表审计不变" "$AUD0" "$(sql "SELECT COUNT(*) FROM audit_logs")"

# ============================================================== C2 反证：解除阻断后成功
sep; log "C2 R72 反证：DROP TRIGGER 后同一动作 → 201，余额/流水/审计各 +1"
unblock_audit
R=$(adj "$OP" "b95-c2-ok" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"月度盘点\"}")
C2TXN=$(jqn "$(body "$R")" transaction_id)
log "C2-http=$(st "$R") txn=$C2TXN"
chk "C2-http"      "201"    "$(st "$R")"
chk "C2-余额"       "7.000" "$(bal 9002)"
chk "C2-流水+1"     "$((TXN0 + 1))" "$(txncount)"
chk "C2-#12审计+1"  "$((A12_0 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "C2-biz_id"     "$C2TXN" "$(af INVENTORY_COUNT_ADJUST biz_id)"
log "C2-summary=$(af INVENTORY_COUNT_ADJUST change_summary)"

# ============================================================== C3 失败路径不写审计
sep; log "C3 失败路径：COUNT_ADJUST 目标 -1.000 → 409 INV-5006，审计不增"
A12_1=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "b95-c3-neg" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"-1.000\",\"reason\":\"错误目标\"}")
log "C3-http=$(st "$R") code=$(code "$(body "$R")")"
chk "C3-http"     "409"     "$(st "$R")"
chk "C3-code"     "INV-5006" "$(code "$(body "$R")")"
chk "C3-审计不增" "$A12_1"  "$(cnt INVENTORY_COUNT_ADJUST)"
chk "C3-余额不变" "7.000"   "$(bal 9002)"

# ============================================================== C4 有意差异：#7 仍解耦
sep; log "C4 有意差异：审计被阻断时 #7 策略 upsert 仍 200 且策略落库（record 解耦通道）"
A7_0=$(cnt REPLENISHMENT_POLICY_UPSERT)
block_audit
R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"8.000\",\"is_enabled\":true}")
log "C4-http=$(st "$R") code=$(code "$(body "$R")")"
chk "C4-http(业务成功)"     "200" "$(st "$R")"
chk "C4-策略已落库"          "1"   "$(sql "SELECT COUNT(*) FROM replenishment_policies WHERE team_id=7 AND sku_id=9002 AND warehouse_id=9004")"
chk "C4-#7审计0行(被阻断)"   "$A7_0" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
unblock_audit

# ============================================================== R1 R73 同键同体重放
sep; log "R1 R73 同键同体重放：冲 seed-init-9005（SKU-C 10.000 → 0.000）"
ORIG=$(txnid seed-init-9005)
A13_0=$(cnt INVENTORY_REVERSE)
R=$(rev "$OWN" "b95-r1-replay" "$ORIG" "导入错误")
R1TXN=$(jqn "$(body "$R")" transaction_id)
log "R1-http=$(st "$R") txn=$R1TXN replay_header=[$(hdr Idempotent-Replayed)]"
chk "R1-首次http"        "200" "$(st "$R")"
chk "R1-首次无重放头"     ""    "$(hdr Idempotent-Replayed)"
chk "R1-biz_id=原流水"   "$ORIG" "$(af INVENTORY_REVERSE biz_id)"
chk "R1-#13审计+1"       "$((A13_0 + 1))" "$(cnt INVENTORY_REVERSE)"
chk "R1-余额"            "0.000" "$(bal 9005)"
R2=$(rev "$OWN" "b95-r1-replay" "$ORIG" "导入错误")
R1TXN2=$(jqn "$(body "$R2")" transaction_id)
log "R1b-http=$(st "$R2") txn=$R1TXN2 replay_header=[$(hdr Idempotent-Replayed)]"
chk "R1b-重放http200"     "200" "$(st "$R2")"
chk "R1b-同一流水id"      "$R1TXN" "$R1TXN2"
chk "R1b-重放头"          "true" "$(hdr Idempotent-Replayed)"
chk "R1b-REVERSAL仅1行"   "1"    "$(rcount)"
chk "R1b-#13审计仍1条"    "$((A13_0 + 1))" "$(cnt INVENTORY_REVERSE)"

# ============================================================== R2 R73 同键不同体
sep; log "R2 R73 同键不同体：拿 seed-init-9002 配 R1 的幂等键 → 409 INV-5003"
ORIG2=$(txnid seed-init-9002)
R=$(rev "$OWN" "b95-r1-replay" "$ORIG2" "导入错误")
log "R2-http=$(st "$R") code=$(code "$(body "$R")")"
chk "R2-http"         "409"     "$(st "$R")"
chk "R2-code"         "INV-5003" "$(code "$(body "$R")")"
chk "R2-未新增冲销"    "1"       "$(rcount)"
chk "R2-余额未变"      "7.000"   "$(bal 9002)"

# ============================================================== R3 R73 不同键二次冲销（回归）
sep; log "R3 R73 回归：不同键二次冲销 seed-init-9005 → 409 INV-5009"
A13_1=$(cnt INVENTORY_REVERSE)
R=$(rev "$OWN" "b95-r3-second" "$ORIG" "再冲一次")
log "R3-http=$(st "$R") code=$(code "$(body "$R")")"
chk "R3-http"      "409"     "$(st "$R")"
chk "R3-code"      "INV-5009" "$(code "$(body "$R")")"
chk "R3-审计不增"   "$A13_1"  "$(cnt INVENTORY_REVERSE)"
chk "R3-REVERSAL仍1" "1"      "$(rcount)"

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
log "B95_CHECK_TOTAL=$TOTAL"
log "B95_FAIL_COUNT=$FAIL"
[ "$FAIL" = "0" ] && log "B95_RESULT=ALL PASS" || log "B95_RESULT=HAS FAILURES$FAILED_LIST"
log "审计全景 team7：COUNT_ADJUST=$(cnt INVENTORY_COUNT_ADJUST) MANUAL_ADJUST=$(cnt INVENTORY_MANUAL_ADJUST) REVERSE=$(cnt INVENTORY_REVERSE) POLICY=$(cnt REPLENISHMENT_POLICY_UPSERT)"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
