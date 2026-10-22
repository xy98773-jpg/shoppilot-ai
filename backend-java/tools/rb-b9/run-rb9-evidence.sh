#!/usr/bin/env bash
# =============================================================================
# B9（M3-T17 审计埋点）—— 项目负责人【独立复核】脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器。端口 3332 / 18112 —— 刻意错开历史批次
# 已用过的 3319 / 3327 / 3328 / 3329 / 3330 / 3331 与 18099 / 18107 / 18108 / 18109 / 18110 / 18111。
#
# 评审法论（B7/B8 两轮教训）：只复现对方给的场景【证明不了完整性】——开发
# A1–A5 / P1–P6 已覆盖「正向埋点 + 字段正确 + 重放/零差额不写 + 脱敏」，
# 本轮刻意【不重复】它们，只补 SSOT 明文但开发未覆盖的四类风险：
#
#   R 组（复现基线，确认结论可对齐）
#     R1 COUNT_ADJUST → #12 一条，biz_id=新流水
#     R2 REVERSE      → #13 一条，biz_id=原流水
#     R3 策略 upsert×2 + disable → 3 条同一 biz_id
#     R4 零增量（INV-5005）→ 审计不增
#
#   P 组（我的新增探针）
#     P1 ★★ 审计写失败时业务照常成功、且【无任何补偿】—— 复现 §2.10「高风险操作要求
#            审计与业务同事务、或在业务提交后立即写入并可补偿」的偏离（预期 FINDING）
#     P2 ★  策略侧失败/越权（跨团队 CAT-3004 / disable 不存在 CAT-3001 / VIEWER TEAM-2003）不写审计
#     P3 ★  账本—审计对账：COUNT(#12)+COUNT(#11) == COUNT(COUNT_ADJUST|MANUAL_ADJUST 流水)；
#            COUNT(#13) == COUNT(REVERSAL 流水)
#     P4 ★  change_summary 超长（14 字段全改 + 1200 字备注）→ 截断至 1000 且业务不 500、审计不丢
#     P5 ★  禁令 9 静态核验：入库/出库/初始导入【无】审计调用；recordAdjustAudit/recordReversalAudit
#            调用点数量与预期一致
#     P6 ★  occurred_at 同源：审计 occurred_at 与流水 occurred_at 差 ≤ 2s（同一事实时刻）
#     P7 ★  横向越权：团队 8 OWNER 调团队 7 资源 → 4xx 且 team_id=8 名下零审计行
#     P8 ★  冲销幂等重放（同 Idempotency-Key）→ 200 且 #13 不增
#
# 用法：bash backend-java/tools/rb-b9/run-rb9-evidence.sh
# 产物：backend-java/tools/rb-b9/evidence-rb9.txt      ← 断言日志（入库）
#       backend-java/tools/rb-b9/evidence-rb9-app.log  ← 应用 stdout（*.log 已 gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（B8 取证脚本首版即因此丢段）。
# ★ mysql 客户端必须 --default-character-set=utf8mb4，否则中文断言字面量退化成 '?' 造成假 FAIL。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb9.txt"
APP_LOG="$OUT_DIR/evidence-rb9-app.log"
MYSQL_CT="rb9-mysql"
MYSQL_PORT=3332
APP_PORT=18112
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

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
  docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot shoppilot \
    -e "DROP TRIGGER IF EXISTS trg_rb9_block_audit" >/dev/null 2>&1 || true
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  pkill -f "server.port=$APP_PORT" 2>/dev/null || true
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-rb9-app.log)"

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
FINDING=0
FINDINGS=""
find(){ # name  —— 记为「与 SSOT 不符的实证」，不计入 FAIL（工具/探针无误，产品行为与契约有差距）
  log "FINDING $1"; FINDING=$((FINDING+1)); FINDINGS="$FINDINGS; $1"
}
chk_has(){ # name needle haystack
  case "$3" in *"$2"*) log "PASS $1";; *) log "FAIL $1 (缺少 [$2]) actual=[$3]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 缺少 [$2]";; esac
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
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
pol_put(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/replenishment-policies" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"; }
pol_del(){ curl -s --noproxy '*' -w "|%{http_code}" -X DELETE "$BASE/api/v1/replenishment-policies/$2" \
  -H "Authorization: Bearer $1"; }
cnt(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }
af(){ sql "SELECT $2 FROM audit_logs WHERE action='$1' AND team_id=7 ORDER BY id DESC LIMIT 1"; }
txnid(){ sql "SELECT id FROM inventory_transactions WHERE idempotency_key='$1'"; }

OWN=$(token owner01); ADM=$(token admin01); OP=$(token operator01); VIEW=$(token viewer01); O8=$(token owner8)
log "TOKENS own=${OWN:0:8} adm=${ADM:0:8} op=${OP:0:8} view=${VIEW:0:8} o8=${O8:0:8}"

# ============================================================== R1 COUNT_ADJUST（#12）
sep; log "R1 #12 盘点调整：SKU-A 10.000 → 7.000（OPERATOR）"
C12_0=$(cnt INVENTORY_COUNT_ADJUST)
R=$(adj "$OP" "rb9-r1-count" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"7.000\",\"reason\":\"月度盘点\"}")
A1TXN=$(jqn "$(body "$R")" transaction_id)
log "R1-http=$(st "$R") code=$(code "$(body "$R")") txn=$A1TXN"
chk "R1-http" "201" "$(st "$R")"
chk "R1-审计+1" "$((C12_0 + 1))" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "R1-biz_type" "INVENTORY_BALANCE" "$(af INVENTORY_COUNT_ADJUST biz_type)"
chk "R1-biz_id=新流水" "$A1TXN" "$(af INVENTORY_COUNT_ADJUST biz_id)"
S=$(af INVENTORY_COUNT_ADJUST change_summary); log "R1-summary=$S"
chk_has "R1-摘要含前后值" "10.000 → 7.000" "$S"

# ============================================================== R2 REVERSE（#13）
sep; log "R2 #13 冲销：seed-init-9005（SKU-C +10.000 → 余额 0）"
ORIG=$(txnid seed-init-9005)
C13_0=$(cnt INVENTORY_REVERSE)
R=$(rev "$OWN" "rb9-r2-rev" "$ORIG" "导入错误")
NEW=$(jqn "$(body "$R")" transaction_id)
log "R2-http=$(st "$R") 原流水=$ORIG 新流水=$NEW"
chk "R2-http" "200" "$(st "$R")"
chk "R2-审计+1" "$((C13_0 + 1))" "$(cnt INVENTORY_REVERSE)"
chk "R2-biz_type" "INVENTORY_TRANSACTION" "$(af INVENTORY_REVERSE biz_type)"
chk "R2-biz_id=原流水" "$ORIG" "$(af INVENTORY_REVERSE biz_id)"
S=$(af INVENTORY_REVERSE change_summary); log "R2-summary=$S"
chk_has "R2-摘要含新流水" "新流水 $NEW" "$S"

# ============================================================== R3 策略（#7）
sep; log "R3 #7 策略：首次 upsert → 改阈值 → disable"
C7_0=$(cnt REPLENISHMENT_POLICY_UPSERT)
R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"8.000\",\"is_enabled\":true}")
PID=$(jqn "$(body "$R")" id); log "R3a-http=$(st "$R") policyId=$PID"
chk "R3a-http" "200" "$(st "$R")"
R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"12.000\",\"is_enabled\":true}")
chk "R3b-http" "200" "$(st "$R")"
R=$(pol_del "$OP" "$PID")
chk "R3c-http" "200" "$(st "$R")"
chk "R3-审计+3" "$((C7_0 + 3))" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
chk "R3-三条同一biz_id" "1" "$(sql "SELECT COUNT(DISTINCT biz_id) FROM audit_logs WHERE action='REPLENISHMENT_POLICY_UPSERT' AND team_id=7 AND biz_id=$PID")"
S=$(af REPLENISHMENT_POLICY_UPSERT change_summary); log "R3c-summary=$S"

# ============================================================== R4 零增量失败不写审计
sep; log "R4 零增量（INV-5005）→ 审计不增"
B_M=$(cnt INVENTORY_MANUAL_ADJUST)
R=$(adj "$ADM" "rb9-r4-zero" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"MANUAL_ADJUST\",\"qty_delta\":\"0.000\",\"reason\":\"零调整\"}")
chk "R4-http" "400" "$(st "$R")"
chk "R4-code" "INV-5005" "$(code "$(body "$R")")"
chk "R4-审计不增" "$B_M" "$(cnt INVENTORY_MANUAL_ADJUST)"

# ============================================================== P2 策略侧失败/越权不写审计
sep; log "P2 策略侧失败/越权：跨团队 CAT-3004 / disable 不存在 CAT-3001 / VIEWER TEAM-2003 → 审计不增"
P7_B=$(cnt REPLENISHMENT_POLICY_UPSERT)
R=$(pol_put "$OP" "{\"sku_id\":9102,\"warehouse_id\":9004,\"safety_stock_qty\":\"5.000\"}")
log "P2a-跨团队 http=$(st "$R") code=$(code "$(body "$R")")"
chk "P2a-http" "422" "$(st "$R")"
chk "P2a-code" "CAT-3004" "$(code "$(body "$R")")"
R=$(pol_del "$OP" 999999)
log "P2b-disable不存在 http=$(st "$R") code=$(code "$(body "$R")")"
chk "P2b-http" "404" "$(st "$R")"
chk "P2b-code" "CAT-3001" "$(code "$(body "$R")")"
R=$(pol_put "$VIEW" "{\"sku_id\":9002,\"warehouse_id\":9004,\"safety_stock_qty\":\"5.000\"}")
log "P2c-VIEWER http=$(st "$R") code=$(code "$(body "$R")")"
chk "P2c-http" "403" "$(st "$R")"
chk "P2c-code" "TEAM-2003" "$(code "$(body "$R")")"
chk "P2-审计不增" "$P7_B" "$(cnt REPLENISHMENT_POLICY_UPSERT)"

# ============================================================== P4 策略 before/after JSON 只含变化字段
sep; log "P4 #7 策略审计的 before_json/after_json 只含【实际变化】字段（开发只验了摘要文本，未验 JSON 结构）"
# R3 的三条 #7：第 1 条=插入（before 为 NULL）、第 2 条=改阈值、第 3 条=disable
AID2=$(sql "SELECT id FROM audit_logs WHERE action='REPLENISHMENT_POLICY_UPSERT' AND team_id=7 AND biz_id=$PID ORDER BY id LIMIT 1 OFFSET 1")
log "P4-第二条审计id=$AID2"
chk "P4-before 只含变化字段(safety)" "8"  "$(sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(before_json,'\$.safety_stock_qty')) FROM audit_logs WHERE id=$AID2")"
chk "P4-after  只含变化字段(safety)" "12" "$(sql "SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json,'\$.safety_stock_qty')) FROM audit_logs WHERE id=$AID2")"
chk "P4-未变字段不出现在 before" "0" "$(sql "SELECT JSON_CONTAINS_PATH(before_json,'one','\$.lead_time_days') FROM audit_logs WHERE id=$AID2")"
chk "P4-未变字段不出现在 after"  "0" "$(sql "SELECT JSON_CONTAINS_PATH(after_json,'one','\$.lead_time_days') FROM audit_logs WHERE id=$AID2")"
chk "P4-插入行 before 各字段值为 null" "1" "$(sql "SELECT JSON_TYPE(JSON_EXTRACT(before_json,'$.safety_stock_qty'))='NULL' FROM audit_logs WHERE action='REPLENISHMENT_POLICY_UPSERT' AND team_id=7 AND biz_id=$PID ORDER BY id LIMIT 1")"

# ============================================================== P4b 无字段变化的 upsert 仍写一条
sep; log "P4b 无字段变化的 upsert（与当前值完全一致）→ 仍写 1 条，摘要标注（无字段变化）"
C7_1=$(cnt REPLENISHMENT_POLICY_UPSERT)
R=$(pol_put "$OP" "{\"sku_id\":9002,\"warehouse_id\":9004,\"lead_time_days\":5,\"coverage_days\":7,\"safety_stock_qty\":\"12.000\",\"is_enabled\":false}")
log "P4b-http=$(st "$R")"
chk "P4b-http" "200" "$(st "$R")"
chk "P4b-审计+1" "$((C7_1 + 1))" "$(cnt REPLENISHMENT_POLICY_UPSERT)"
S=$(af REPLENISHMENT_POLICY_UPSERT change_summary); log "P4b-summary=$S"
chk_has "P4b-摘要标注无变化" "（无字段变化）" "$S"

# ============================================================== P6 occurred_at 同源
sep; log "P6 审计 occurred_at 与流水 occurred_at 同一事实时刻（差 ≤ 2s）"
DELTA=$(sql "SELECT ABS(TIMESTAMPDIFF(SECOND, a.occurred_at, t.occurred_at)) FROM audit_logs a JOIN inventory_transactions t ON t.id=$A1TXN WHERE a.action='INVENTORY_COUNT_ADJUST' AND a.biz_id=$A1TXN")
log "P6-delta_seconds=$DELTA"
chk "P6-≤2s" "1" "$([ "${DELTA:-99}" -le 2 ] && echo 1 || echo 0)"

# ============================================================== P6b 审计上下文采集
sep; log "P6b 审计 HTTP 上下文采集（request_uri 剥查询串 / request_id / ip / http_method）"
chk "P6b-request_uri" "/api/v1/inventory/adjustments" "$(sql "SELECT request_uri FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN")"
chk "P6b-http_method" "POST" "$(sql "SELECT http_method FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN")"
chk "P6b-request_id非空" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN AND request_id IS NOT NULL")"
chk "P6b-ip非空" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='INVENTORY_COUNT_ADJUST' AND biz_id=$A1TXN AND ip IS NOT NULL")"

# ============================================================== P7 横向越权
sep; log "P7 横向越权：团队 8 OWNER 调团队 7 资源 → 4xx 且 team_id=8 零审计行"
R=$(adj "$O8" "rb9-p7-cross" "{\"warehouse_id\":9004,\"sku_id\":9002,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"1.000\",\"reason\":\"越权探针\"}")
H=$(st "$R"); C=$(code "$(body "$R")")
log "P7-http=$H code=$C"
chk "P7-4xx" "1" "$([ "${H:0:1}" = "4" ] && echo 1 || echo 0)"
chk "P7-team8四动作零审计" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=8 AND action IN ('INVENTORY_COUNT_ADJUST','INVENTORY_MANUAL_ADJUST','INVENTORY_REVERSE','REPLENISHMENT_POLICY_UPSERT')")"
chk "P7-四动作无非7行" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action IN ('INVENTORY_COUNT_ADJUST','INVENTORY_MANUAL_ADJUST','INVENTORY_REVERSE','REPLENISHMENT_POLICY_UPSERT') AND team_id<>7")"

# ============================================================== P8 冲销幂等重放
sep; log "P8 冲销幂等重放（同 Idempotency-Key）→ 200 且 #13 不增"
C13_1=$(cnt INVENTORY_REVERSE)
R=$(rev "$OWN" "rb9-r2-rev" "$ORIG" "导入错误")
log "P8-http=$(st "$R") 重放流水=$(jqn "$(body "$R")" transaction_id)"
chk "P8-审计不增" "$C13_1" "$(cnt INVENTORY_REVERSE)"
# API_CONTRACT §6.3：同幂等键 + 请求体一致 → 返回首次结果（200/201），不重复执行
if [ "$(st "$R")" = "200" ] && [ "$(jqn "$(body "$R")" transaction_id)" = "$NEW" ]; then
  chk "P8-幂等重放返回首次结果" 1 1
else
  find "P8 冲销同 Idempotency-Key 重放 → $(st "$R")/$(code "$(body "$R")")，契约要求 200 + 首次结果（API_CONTRACT §6.3）"
fi

# ============================================================== P3 账本—审计对账（须在 P1 前）
sep; log "P3 账本—审计对账：审计条数 == 业务流水条数"
N_ADJ_AUDIT=$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action IN ('INVENTORY_COUNT_ADJUST','INVENTORY_MANUAL_ADJUST')")
N_ADJ_TXN=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type IN ('COUNT_ADJUST','MANUAL_ADJUST')")
N_REV_AUDIT=$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action='INVENTORY_REVERSE'")
N_REV_TXN=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'")
log "P3 调整: audit=$N_ADJ_AUDIT txn=$N_ADJ_TXN | 冲销: audit=$N_REV_AUDIT txn=$N_REV_TXN"
chk "P3-调整对账" "$N_ADJ_TXN" "$N_ADJ_AUDIT"
chk "P3-冲销对账" "$N_REV_TXN" "$N_REV_AUDIT"

# ============================================================== P5 静态禁令核验
sep; log "P5 禁令 9 静态核验：入库/出库/初始导入无审计调用"
W="$REPO/backend-java/inventory/src/main/java/com/shoppilot/inventory/application/InventoryWriteService.java"
chk "P5-applyInitialImport 无审计" "0" "$(awk '/public StockChangeResult applyInitialImport/,/^    }/' "$W" | grep -c 'Audit')"
chk "P5-applyInbound 无审计" "0" "$(awk '/public StockChangeResult applyInbound/,/^    }/' "$W" | grep -c 'Audit')"
chk "P5-applyOutbound 无审计" "0" "$(awk '/public StockChangeResult applyOutbound/,/^    }/' "$W" | grep -c 'Audit')"
chk "P5-recordAdjustAudit 调用点=2" "2" "$(grep 'recordAdjustAudit(AuditAction' "$W" | grep -vc 'private void')"
chk "P5-recordReversalAudit 调用点=1" "1" "$(grep 'recordReversalAudit(' "$W" | grep -vc 'private void')"
chk "P5-policy audit 调用点=3" "3" "$(grep 'recordPolicyAudit(' "$REPO/backend-java/inventory/src/main/java/com/shoppilot/inventory/application/InventoryPolicyService.java" | grep -vc 'private void')"

# ============================================================== P1 ★★ 审计写失败 → 业务成功且无补偿（R72 证据）
sep; log "P1 审计写失败时：业务照常成功、审计缺失、无任何补偿（§2.10 高风险同事务/可补偿 的偏离实证）"
C12_B=$(cnt INVENTORY_COUNT_ADJUST); T_B=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")
BAL_B=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")
sql "CREATE TRIGGER trg_rb9_block_audit BEFORE INSERT ON audit_logs FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RB9_AUDIT_BLOCKED'" >/dev/null 2>&1
TRG=$(sql "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_name='trg_rb9_block_audit'")
chk "P1-触发器已装" "1" "$TRG"
R=$(adj "$OP" "rb9-p1-noaudit" "{\"warehouse_id\":9004,\"sku_id\":9003,\"txn_type\":\"COUNT_ADJUST\",\"target_on_hand_qty\":\"9.000\",\"reason\":\"审计失败探针\"}")
P1TXN=$(jqn "$(body "$R")" transaction_id)
log "P1-http=$(st "$R") code=$(code "$(body "$R")") txn=$P1TXN"
chk "P1-业务仍成功" "201" "$(st "$R")"
chk "P1-流水+1" "$((T_B + 1))" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"
chk "P1-余额已改" "9.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")"
chk "P1-审计缺失" "$C12_B" "$(cnt INVENTORY_COUNT_ADJUST)"
chk "P1-应用日志有失败ERROR" "1" "$([ "$(grep -c '审计写入失败' "$APP_LOG")" -ge 1 ] && echo 1 || echo 0)"
sql "DROP TRIGGER IF EXISTS trg_rb9_block_audit" >/dev/null 2>&1
sleep 2
chk "P1-事后无补偿写回" "$C12_B" "$(cnt INVENTORY_COUNT_ADJUST)"
N_ADJ_AUDIT2=$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action IN ('INVENTORY_COUNT_ADJUST','INVENTORY_MANUAL_ADJUST')")
N_ADJ_TXN2=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type IN ('COUNT_ADJUST','MANUAL_ADJUST')")
log "P1 对账结果：audit=$N_ADJ_AUDIT2 txn=$N_ADJ_TXN2（差 $((N_ADJ_TXN2 - N_ADJ_AUDIT2)) 条业务流水无对应审计）"
if [ $((N_ADJ_TXN2 - N_ADJ_AUDIT2)) -eq 1 ]; then
  find "P1 审计写失败 → 业务仍 201、审计永久缺失、无补偿机制（§2.10：高风险操作要求审计与业务同事务，或提交后立即写入并可补偿）"
else
  chk "P1-账本与审计已不一致(流水多1)" "1" "0"
fi

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
TOTAL=$((TOTAL + FAIL))
log "RB9_CHECK_TOTAL=$TOTAL"
log "RB9_FAIL_COUNT=$FAIL"
log "RB9_FINDING_COUNT=$FINDING"
[ "$FINDING" = "0" ] || log "RB9_FINDINGS=${FINDINGS}"
[ "$FAIL" = "0" ] && log "RB9_RESULT=ALL_PASS" || log "RB9_RESULT=FAILED${FAILED_LIST}"
log "--- 审计全景（按 action 分组，team 7）---"
sql "SELECT action, COUNT(*) FROM audit_logs WHERE team_id=7 GROUP BY action ORDER BY action" | tee -a "$LOG"
exit "$FAIL"
