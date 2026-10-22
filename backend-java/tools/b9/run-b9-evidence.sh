#!/usr/bin/env bash
# =============================================================================
# B9（M3-T17 · 审计埋点：库存调整 / 冲销 / 补货策略）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器。端口 3331 / 18111 —— 刻意错开
# 历史批次已用过的 3319 / 3327 / 3328 / 3329 / 3330 与 18099 / 18107 / 18108 / 18109 / 18110。
#
# 组次（对应任务书「五、真栈取证 ≥5 组」）：
#   A1  COUNT_ADJUST        → 恰 1 条 #12，biz_id=本次新流水 id，摘要含前后值/原因/流水
#   A2  MANUAL_ADJUST(ADMIN)→ 恰 1 条 #11
#   A3  REVERSE             → 恰 1 条 #13 且 biz_id=被冲销的原流水；二次冲销 INV-5009 且审计不增
#   A4  策略 upsert ×2 + disable → 恰 3 条 #7，biz_id 为同一策略行 id
#   A5  失败与越权（INV-5005 / TEAM-2003 / INV-5007 / INV-5006）→ 审计条数不变
#
# 附加探针组（P，开发主动补测；评审法论：每批至少 3 条 SSOT 明文但对方未覆盖的探针）
#   P1  幂等重放（同 Idempotency-Key 同内容）→ 审计不增（一次变更只记一次）
#   P2  #13 的 biz_id 是原流水、新 REVERSAL 流水 id 只在摘要里（交叉验证两者都存在且不同）
#   P3  团队归属：全部审计行 team_id=7，团队 8 名下 0 行
#   P4  策略第二次 upsert 的摘要只含实际变化字段（未变的「提前期天数」不得出现）
#   P5  before_json / after_json 含前后值，且不含敏感字样
#   P6  盘点差额为 0（目标=当前）→ 不写流水、不写审计（§5.5 步骤 G）
#
# 用法：bash backend-java/tools/b9/run-b9-evidence.sh
# 产物：backend-java/tools/b9/evidence-b9.txt        ← 断言日志（入库）
#       backend-java/tools/b9/evidence-b9-app.log    ← 应用 stdout（*.log 已 gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（B8 脚本第一版即因此丢了整段）。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b9.txt"
APP_LOG="$OUT_DIR/evidence-b9-app.log"
MYSQL_CT="b9-mysql"
MYSQL_PORT=3331
APP_PORT=18111
PW="1234"
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

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; pkill -f "server.port=$APP_PORT" 2>/dev/null || true; }
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b9-app.log)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

MYSQL_FLAGS=(--default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot)
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot < "$EVI/seed-b9.sql" >> "$APP_LOG" 2>&1 && log "SEED_OK (seed-b9.sql)"

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
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }   # 数字字段
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
adj(){ # bearer idem json -> body|http
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" -d "$3"
}
rev(){ # bearer idem txnId reason
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$3/reverse" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $2" \
    -d "{\"reason\":\"$4\"}"
}
pol_put(){ # bearer json
  curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/replenishment-policies" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"
}
pol_del(){ # bearer id
  curl -s --noproxy '*' -w "|%{http_code}" -X DELETE "$BASE/api/v1/replenishment-policies/$2" \
    -H "Authorization: Bearer $1"
}
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
af(){ sql "SELECT $2 FROM audit_logs WHERE action='$1' AND team_id=7 ORDER BY id DESC LIMIT 1"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1'"; }

OWN=$(token owner01); ADM=$(token admin01); OP=$(token operator01); VIEW=$(token viewer01)
log "TOKENS own=${OWN:0:8} adm=${ADM:0:8} op=${OP:0:8} view=${VIEW:0:8}"

# ============================================================== A1 COUNT_ADJUST（#12）
sep; log "A1 #12 盘点调整：SKU-A 10.000 → 7.000（OPERATOR）"
C12_0=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "b9-a1-count" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"月度盘点\"}")
A1TXN=$(jqn "$(body "$R")" transaction_id)
log "A1-http=$(st "$R") code=$(code "$(body "$R")") txn=$A1TXN"
chk "A1-http" "201" "$(st "$R")"
chk "A1 审计条数(+1)" "$((C12_0 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "A1-action"        "INVENTORY_COUNT_ADJUST" "$(af INVENTORY_COUNT_ADJUST action)"
chk "A1-biz_type"      "INVENTORY_BALANCE"      "$(af INVENTORY_COUNT_ADJUST biz_type)"
chk "A1-biz_id=新流水"  "$A1TXN"                 "$(af INVENTORY_COUNT_ADJUST biz_id)"
chk "A1-result"        "SUCCESS"                "$(af INVENTORY_COUNT_ADJUST result)"
chk "A1-team_id"       "7"                      "$(af INVENTORY_COUNT_ADJUST team_id)"
chk "A1-operator_id"   "1001"                   "$(af INVENTORY_COUNT_ADJUST operator_id)"
chk "A1-operator_role" "OPERATOR"               "$(af INVENTORY_COUNT_ADJUST operator_role)"
S=$(af INVENTORY_COUNT_ADJUST change_summary)
log "A1-summary=$S"
case "$S" in *"10.000 → 7.000"*) chk "A1-摘要含前后值" 1 1;; *) chk "A1-摘要含前后值" 1 0;; esac
case "$S" in *"COUNT_ADJUST"*)    chk "A1-摘要含类型"   1 1;; *) chk "A1-摘要含类型"   1 0;; esac
case "$S" in *"原因：月度盘点"*)  chk "A1-摘要含原因"   1 1;; *) chk "A1-摘要含原因"   1 0;; esac
case "$S" in *"流水 $A1TXN"*)     chk "A1-摘要含流水id" 1 1;; *) chk "A1-摘要含流水id" 1 0;; esac
chk "A1-request_uri" "/api/v1/inventory/adjustments" "$(af INVENTORY_COUNT_ADJUST request_uri)"

# ============================================================== A2 MANUAL_ADJUST（#11）
sep; log "A2 #11 手工调整：SKU-B 10.000 → 8.000（ADMIN）"
C11_0=$(cnt INVENTORY_MANUAL_ADJUST)
R=$(adj "$ADM" "b9-a2-manual" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"-2.000\",\"reason\":\"破损\"}")
A2TXN=$(jqn "$(body "$R")" transaction_id)
log "A2-http=$(st "$R") code=$(code "$(body "$R")") txn=$A2TXN"
chk "A2-http" "201" "$(st "$R")"
chk "A2 审计条数(+1)" "$((C11_0 + 1))" "$(cnt INVENTORY_MANUAL_ADJUST)"
chk "A2-action"        "INVENTORY_MANUAL_ADJUST" "$(af INVENTORY_MANUAL_ADJUST action)"
chk "A2-biz_type"      "INVENTORY_BALANCE"       "$(af INVENTORY_MANUAL_ADJUST biz_type)"
chk "A2-biz_id=新流水"  "$A2TXN"                  "$(af INVENTORY_MANUAL_ADJUST biz_id)"
chk "A2-operator_id"   "1005"                    "$(af INVENTORY_MANUAL_ADJUST operator_id)"
chk "A2-operator_role" "ADMIN"                   "$(af INVENTORY_MANUAL_ADJUST operator_role)"
S=$(af INVENTORY_MANUAL_ADJUST change_summary)
log "A2-summary=$S"
case "$S" in *"10.000 → 8.000"*) chk "A2-摘要含前后值" 1 1;; *) chk "A2-摘要含前后值" 1 0;; esac
case "$S" in *"MANUAL_ADJUST"*)  chk "A2-摘要含类型"   1 1;; *) chk "A2-摘要含类型"   1 0;; esac

# ============================================================== A3 REVERSE（#13）
sep; log "A3 #13 冲销：冲 seed-init-9005（SKU-C +10.000 → 余额 0）"
ORIG=$(txnid seed-init-9005)
C13_0=$(cnt INVENTORY_REVERSE)
R=$(rev "$OWN" "b9-a3-rev1" "$ORIG" "导入错误")
NEW=$(jqn "$(body "$R")" transaction_id)
log "A3-http=$(st "$R") code=$(code "$(body "$R")") 原流水=$ORIG 新流水=$NEW"
chk "A3-http" "200" "$(st "$R")"
chk "A3 审计条数(+1)" "$((C13_0 + 1))" "$(cnt INVENTORY_REVERSE)"
chk "A3-biz_type"  "INVENTORY_TRANSACTION" "$(af INVENTORY_REVERSE biz_type)"
chk "A3-biz_id=原流水" "$ORIG"             "$(af INVENTORY_REVERSE biz_id)"
S=$(af INVENTORY_REVERSE change_summary)
log "A3-summary=$S"
case "$S" in *"冲销流水 $ORIG"*) chk "A3-摘要含原流水" 1 1;; *) chk "A3-摘要含原流水" 1 0;; esac
case "$S" in *"新流水 $NEW"*)    chk "A3-摘要含新流水" 1 1;; *) chk "A3-摘要含新流水" 1 0;; esac
case "$S" in *"10.000 → 0.000"*) chk "A3-摘要含回退余额" 1 1;; *) chk "A3-摘要含回退余额" 1 0;; esac
# 二次冲销：INV-5009(409)，审计不增
C13_1=$(cnt INVENTORY_REVERSE)
R2=$(rev "$OWN" "b9-a3-rev2" "$ORIG" "再冲一次")
log "A3b-http=$(st "$R2") code=$(code "$(body "$R2")")"
chk "A3b-二次冲销http" "409" "$(st "$R2")"
chk "A3b-二次冲销code" "INV-5009" "$(code "$(body "$R2")")"
chk "A3b-审计不增" "$C13_1" "$(cnt INVENTORY_REVERSE)"
# P2：biz_id 是原流水；新流水行真实存在且类型为 REVERSAL；两者不同
chk "P2-新流水类型" "REVERSAL" "$(sql "SELECT txn_type FROM inventory_transactions WHERE id=$NEW")"
chk "P2-biz_id≠新流水" "1" "$([ "$(af INVENTORY_REVERSE biz_id)" != "$NEW" ] && echo 1 || echo 0)"

# ============================================================== A4 策略 upsert ×2 + disable（#7）
sep; log "A4 #7 策略：首次 upsert（安全库存 8）→ 二次 upsert（安全库存 12）→ disable"
C7_0=$(cnt REPLENISHMENT_POLICY_UPSERT)
R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"8.000\",\"is_enabled\":true}")
PID=$(jqn "$(body "$R")" id)
log "A4a-http=$(st "$R") policyId=$PID"
chk "A4a-首次upsert" "200" "$(st "$R")"
chk "A4a-审计+1" "$((C7_0 + 1))" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
chk "A4a-biz_type" "REPLENISHMENT_POLICY" "$(af REPLENISHMENT_POLICY_UPSERT biz_type)"
chk "A4a-biz_id=策略id" "$PID" "$(af REPLENISHMENT_POLICY_UPSERT biz_id)"
S=$(af REPLENISHMENT_POLICY_UPSERT change_summary)
log "A4a-summary=$S"
case "$S" in *"策略 $PID 变更：sku=9002 仓库=9004"*) chk "A4a-摘要头" 1 1;; *) chk "A4a-摘要头" 1 0;; esac
case "$S" in *"安全库存 无 → 8.000"*) chk "A4a-摘要含创建值" 1 1;; *) chk "A4a-摘要含创建值" 1 0;; esac

R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"12.000\",\"is_enabled\":true}")
log "A4b-http=$(st "$R")"
chk "A4b-二次upsert" "200" "$(st "$R")"
chk "A4b-审计+1" "$((C7_0 + 2))" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
chk "A4b-biz_id不变" "$PID" "$(af REPLENISHMENT_POLICY_UPSERT biz_id)"
S=$(af REPLENISHMENT_POLICY_UPSERT change_summary)
log "A4b-summary=$S"
case "$S" in *"安全库存 8.000 → 12.000"*) chk "A4b-摘要含变化" 1 1;; *) chk "A4b-摘要含变化" 1 0;; esac
case "$S" in *"提前期天数"*) chk "P4-未变字段不进摘要" 1 0;; *) chk "P4-未变字段不进摘要" 1 1;; esac

R=$(pol_del "$OP" "$PID")
log "A4c-http=$(st "$R")"
chk "A4c-disable" "200" "$(st "$R")"
chk "A4c-审计+1" "$((C7_0 + 3))" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
chk "A4c-摘要" "停用策略 $PID" "$(af REPLENISHMENT_POLICY_UPSERT change_summary)"
chk "A4c-三条同一biz_id" "1" "$(sql "SELECT COUNT(DISTINCT biz_id) FROM audit_logs WHERE action='REPLENISHMENT_POLICY_UPSERT' AND team_id=7 AND biz_id=$PID")"
chk "A4c-is_enabled落库" "0" "$(sql "SELECT is_enabled FROM replenishment_policies WHERE id=$PID")"

# ============================================================== A5 失败与越权不写审计
sep; log "A5 失败与越权：审计条数必须不变"
B_C=$(cnt INVENTORY_COUNT_ADJUST); B_M=$(cnt INVENTORY_MANUAL_ADJUST); B_R=$(cnt INVENTORY_REVERSE); B_P=$(cnt REPLENISHMENT_POLICY_UPSERT)
B_T=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")

R=$(adj "$ADM" "b9-a5-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"0.000\",\"reason\":\"零调整\"}")
log "A5a-零增量 http=$(st "$R") code=$(code "$(body "$R")")"
chk "A5a-INV-5005" "400" "$(st "$R")"
chk "A5a-code" "INV-5005" "$(code "$(body "$R")")"

R=$(adj "$OP" "b9-a5-neg" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"-1.000\",\"reason\":\"错误目标\"}")
log "A5b-负库存 http=$(st "$R") code=$(code "$(body "$R")")"
chk "A5b-INV-5006" "409" "$(st "$R")"
chk "A5b-code" "INV-5006" "$(code "$(body "$R")")"

R=$(adj "$VIEW" "b9-a5-viewer" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"越权\"}")
log "A5c-VIEWER http=$(st "$R") code=$(code "$(body "$R")")"
chk "A5c-TEAM-2003" "403" "$(st "$R")"
chk "A5c-code" "TEAM-2003" "$(code "$(body "$R")")"

R=$(adj "$OP" "b9-a5-op-manual" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"-2.000\",\"reason\":\"越权\"}")
log "A5d-OPERATOR手工 http=$(st "$R") code=$(code "$(body "$R")")"
chk "A5d-INV-5007" "403" "$(st "$R")"
chk "A5d-code" "INV-5007" "$(code "$(body "$R")")"

R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":-1}")
log "A5e-策略参数非法 http=$(st "$R") code=$(code "$(body "$R")")"
chk "A5e-400" "400" "$(st "$R")"
chk "A5e-code" "COM-0001" "$(code "$(body "$R")")"

chk "A5-计数:#12不变" "$B_C" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "A5-计数:#11不变" "$B_M" "$(cnt INVENTORY_MANUAL_ADJUST)"
chk "A5-计数:#13不变" "$B_R" "$(cnt INVENTORY_REVERSE)"
chk "A5-计数:#7不变"  "$B_P" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
chk "A5-失败零落库(流水数不变)" "$B_T" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"

# ============================================================== P1 幂等重放
sep; log "P1 幂等重放：同 Idempotency-Key 同内容再发一次 → 不新增审计"
C_BEFORE=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "b9-p1-replay" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"5.000\",\"reason\":\"重放探针\"}")
T1=$(jqn "$(body "$R")" transaction_id)
R2=$(adj "$OP" "b9-p1-replay" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"5.000\",\"reason\":\"重放探针\"}")
T2=$(jqn "$(body "$R2")" transaction_id)
log "P1-首次 txn=$T1 重放 txn=$T2 审计=$C_BEFORE → $(cnt INVENTORY_COUNT_ADJUST)"
chk "P1-重放同流水" "1" "$([ -n "$T1" ] && [ "$T1" = "$T2" ] && echo 1 || echo 0)"
chk "P1-审计仅+1" "$((C_BEFORE + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"

# ============================================================== P6 零差额盘点
sep; log "P6 §5.5 步骤 G：盘点目标=当前值（差额 0）→ 不写流水、不写审计"
C_BEFORE=$(cnt INVENTORY_COUNT_ADJUST)
T_BEFORE=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")
R=$(adj "$OP" "b9-p6-noop" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"5.000\",\"reason\":\"零差额盘点\"}")
log "P6-http=$(st "$R") transaction_id=$(jqn "$(body "$R")" transaction_id)"
chk "P6-http" "201" "$(st "$R")"
chk "P6-txn为空" "" "$(jqn "$(body "$R")" transaction_id)"
chk "P6-流水不增" "$T_BEFORE" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"
chk "P6-审计不增" "$C_BEFORE" "$(cnt INVENTORY_COUNT_ADJUST)"

# ============================================================== P3 / P5 归属与脱敏
sep; log "P3 团队归属：审计行 team_id 全为 7，团队 8 名下 0 行"
chk "P3-团队8零行" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=8")"
chk "P3-本批四动作全为7" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action IN ('INVENTORY_COUNT_ADJUST','INVENTORY_MANUAL_ADJUST','INVENTORY_REVERSE','REPLENISHMENT_POLICY_UPSERT') AND team_id<>7")"

sep; log "P5 before_json / after_json 前后值 + 脱敏红线（按 A1 的流水 id 精确定位，避免取到后续探针的行）"
chk "P5-before ok" "10.000" "$(sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(before_json,'\$.on_hand_qty')) FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN")"
chk "P5-after  ok" "7.000"  "$(sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json,'\$.on_hand_qty')) FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN")"
chk "P5-无敏感字样" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE change_summary LIKE '%password%' OR change_summary LIKE '%secret%' OR after_json LIKE '%password%' OR before_json LIKE '%password%'")"

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
TOTAL=$((TOTAL + FAIL))
log "B9_CHECK_TOTAL=$TOTAL"
log "B9_FAIL_COUNT=$FAIL"
[ "$FAIL" = "0" ] && log "B9_RESULT=ALL_PASS" || log "B9_RESULT=FAILED${FAILED_LIST}"
log "--- 审计全景（按 action 分组，team 7）---"
sql "SELECT action, COUNT(*) FROM audit_logs WHERE team_id=7 GROUP BY action ORDER BY action" | tee -a "$LOG"
exit "$FAIL"
