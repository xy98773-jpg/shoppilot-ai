#!/usr/bin/env bash
# =============================================================================
# M7-T01 端到端演示脚本（ACCEPTANCE_CRITERIA §7.2 的 20 步）
# -----------------------------------------------------------------------------
# 形态：独立 MySQL 容器 + fat jar + service-ai 容器（与 m6-t13 同骨架，端口独立避撞）
#   MySQL 3362 / app 18252 / service-ai 18253
#
# ★ 与 §7.2 第 1 步的偏差（已申报）：本脚本用「独立容器 + fat jar」而不用
#   `docker compose up`，原因是 T01 要可反复重跑且不污染 dev 栈；compose 五服务
#   部署验证由 M7-T05 单独取证。除部署形态外，其余 19 步全部走真实 HTTP。
#
# 覆盖：
#   2  登录（OWNER / OPERATOR / VIEWER / 他团队 OWNER）
#   3-8 六类导入（供应商→商品→SKU→仓库→库存快照→28 天销售）
#   9  重复上传同一文件 → 409 IMP-4004
#   10 含错误行文件 → 校验期 error_rows=1 → 确认被 422 IMP-4009 阻断 → 错误报告可导出
#   11 库存总览（on_hand=10、in_transit 派生）
#   12 创建分析运行 → 202
#   13 轮询至 SUCCEEDED
#   14 分析明细（D / IP / Q_suggest）
#   15 改建议量 + 提交审核 → PENDING_REVIEW
#   16 审批通过 → APPROVED（OWNER）
#   17 转采购单 → DRAFT → PENDING → ORDERED，且库存不变
#   18 部分到货 20 → PARTIALLY_RECEIVED，入库
#   19 剩余到货 10 → RECEIVED
#   20 对账 0 差额 + 审计 + VIEWER 只读 403 + 跨团队 404
#
# 用法：bash backend-java/tools/e2e/run-e2e.sh
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/e2e"
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
SEED="$OUT/seed.sql"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
MYSQL_NAME=e2e-mysql
AI_NAME=e2e-ai
MYSQL_PORT="${MYSQL_PORT:-3362}"
APP_PORT="${APP_PORT:-18252}"
AI_PORT="${AI_PORT:-18253}"
TOKEN=e2e-internal-token-0123456789
EVI="$OUT/evidence-e2e.txt"
BODY="$OUT/body.json"
DATA="$OUT/data"
BASE="http://127.0.0.1:$APP_PORT"
PASS=0; FAIL=0; SKIP=0
mkdir -p "$OUT" "$DATA"
: > "$EVI"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
chk_ne(){ if [ "$2" != "$3" ] && [ -n "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected NOT [$2] but was [$3]"; fi; }
chk_gt(){ if [ "${3%.*}" -gt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (> $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected > [$2] but was [$3]"; fi; }
skip(){ SKIP=$((SKIP+1)); log "[SKIP] $1"; }
jget(){ "$PY" -c "import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    print('<unparsable>'); raise SystemExit
seen=[]
for k in sys.argv[2].split('.'):
    if k=='': continue
    seen.append(k)
    if isinstance(d,dict) and k in d: d=d[k]
    elif isinstance(d,list) and k.isdigit() and int(k)<len(d): d=d[int(k)]
    else: print('<missing:%s>'%'.'.join(seen)); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }
# jraw：取子树的原始 JSON（用于把 suggested_mapping 原样回填给 mapping 接口）
jraw(){ "$PY" -c "import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
for k in sys.argv[2].split('.'):
    if not k: continue
    d=d[k] if isinstance(d,dict) else d[int(k)]
print(json.dumps(d,ensure_ascii=False))" "$BODY" "$1"; }
sql(){ docker exec -i "$MYSQL_NAME" mysql -ushoppilot -pchangeme_app_password shoppilot \
        --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }
api(){ curl -s --noproxy '*' -o "$BODY" -w "%{http_code}" "$@"; }
# ★ Windows/Git Bash：curl -F 的 @path 必须用 Windows 路径，否则 MSYS 会改写
winpath(){ cygpath -w "$1" 2>/dev/null || echo "$1"; }

APP_PID=""
cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: fat jar 不存在：$JAR"; exit 4; }
log "E2E 基线：JAR_MTIME=$(date -r "$JAR" '+%F %T')  SIZE=$(stat -c%s "$JAR")"
sep

# ---------------------------------------------------------------- 基础设施
log "[0] 启动 MySQL 容器（$MYSQL_NAME :$MYSQL_PORT）"
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=changeme_root_password \
  -e MYSQL_DATABASE=shoppilot -e MYSQL_USER=shoppilot -e MYSQL_PASSWORD=changeme_app_password \
  -e TZ=UTC mysql:8.0.36 --default-time-zone=+00:00 >/dev/null
for i in $(seq 1 60); do
  docker exec "$MYSQL_NAME" mysqladmin ping -uroot -pchangeme_root_password >/dev/null 2>&1 && break
  sleep 2
done
sql "SELECT 1" >/dev/null && log "    MySQL ready"

log "[1] 启动 service-ai 容器（:$AI_PORT）"
docker rm -f "$AI_NAME" >/dev/null 2>&1
AI_UP=0
if docker run -d --name "$AI_NAME" -p "127.0.0.1:$AI_PORT:8000" \
     -e AI_INTERNAL_TOKEN="$TOKEN" \
     -e JAVA_BACKEND_BASE_URL="http://host.docker.internal:$APP_PORT" \
     shoppilot-dev-service-ai:latest >/dev/null 2>&1; then
  for i in $(seq 1 40); do
    if curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$AI_PORT/internal/v1/health"; then AI_UP=1; break; fi
    sleep 1
  done
fi
log "    service-ai up=$AI_UP"

log "[2] 启动 fat jar（:$APP_PORT）"
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=shoppilot \
SPRING_DATASOURCE_PASSWORD=changeme_app_password \
SPRING_PROFILES_ACTIVE=dev \
JWT_SECRET="e2e-only-placeholder-jwt-secret-32bytes" \
AI_INTERNAL_TOKEN="$TOKEN" \
AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT" \
ANALYSIS_POLL_ENABLED=true \
java -jar "$JAR" --server.port="$APP_PORT" > "$OUT/app.log" 2>&1 &
APP_PID=$!
UP=0
for i in $(seq 1 90); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/v1/auth/login" 2>/dev/null)
  if [ "$code" = "405" ] || [ "$code" = "400" ] || [ "$code" = "401" ]; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || { log "FATAL: app 未就绪，见 $OUT/app.log"; tail -20 "$OUT/app.log" | tee -a "$EVI"; exit 5; }

# ★ 第 1 步（替代 compose）：迁移已自动执行完毕，断言版本数
MIG=$(sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")
chk "S1 Flyway 成功迁移数" "13" "$MIG"
log "    app ready"
sep

log "[3] 灌种子（团队 + 用户；主数据全部走导入接口，不预置）"
docker exec -i "$MYSQL_NAME" mysql -ushoppilot -pchangeme_app_password shoppilot \
  --default-character-set=utf8mb4 < "$SEED" 2>/dev/null
chk "S3 teams" "2" "$(sql 'SELECT COUNT(*) FROM teams')"
chk "S3 users" "5" "$(sql 'SELECT COUNT(*) FROM users')"
sep

# ---------------------------------------------------------------- 登录
login(){ api -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
           -d "{\"username\":\"$1\",\"password\":\"S3cret!Passw0rd\"}" >/dev/null; jget "data.accessToken"; }

log "[S2] 登录四种角色"
TOK_OWNER=$(login owner01)
case "$TOK_OWNER" in *.*.*) chk "S2 OWNER 登录" "ok" "ok" ;; *) chk "S2 OWNER 登录" "ok" "$TOK_OWNER" ;; esac
TOK_OP=$(login operator01)
TOK_VIEW=$(login viewer01)
TOK_OTHER=$(login owner8)
AUTH="Authorization: Bearer $TOK_OWNER"
AUTH_OP="Authorization: Bearer $TOK_OP"
AUTH_VIEW="Authorization: Bearer $TOK_VIEW"
AUTH_OTHER="Authorization: Bearer $TOK_OTHER"
case "$TOK_VIEW" in *.*.*) chk "S2 VIEWER 登录" "ok" "ok" ;; *) chk "S2 VIEWER 登录" "ok" "$TOK_VIEW" ;; esac
sep

# ---------------------------------------------------------------- 导入工具
TODAY=$(date +%F)
cat > "$DATA/supplier.csv" <<'CSV'
code,name,contact_name,contact_phone,lead_time_days,status
SUP-E2E,华东供应链,张经理,13800000001,7,ACTIVE
CSV
cat > "$DATA/product.csv" <<'CSV'
product_code,name,category,brand,unit,default_supplier_code,status
P-E2E-001,轻旅行双肩包,箱包,UPack,个,SUP-E2E,ACTIVE
CSV
cat > "$DATA/sku.csv" <<'CSV'
sku_code,product_code,name,supplier_code,default_lead_time_days,moq,order_multiple,qty_step,purchase_price,sale_price,status
SKU-E2E-001,P-E2E-001,轻旅行双肩包 黑色,SUP-E2E,7,10,10,1,35.5000,89.0000,ACTIVE
CSV
cat > "$DATA/warehouse.csv" <<'CSV'
code,name,is_default,address,status
WH-E2E,演示仓,TRUE,上海市浦东新区,ACTIVE
CSV
cat > "$DATA/inventory.csv" <<CSV
sku_code,warehouse_code,qty,snapshot_date,remark
SKU-E2E-001,WH-E2E,10,$TODAY,期初快照
CSV
# 28 天销售：每天 3 件 ⇒ D=3，L=7，R=7 ⇒ Q_base = 3*14 - IP(10) = 32
{
  echo "sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count"
  for i in $(seq 27 -1 0); do
    d=$(date -d "-$i days" +%F)
    echo "SKU-E2E-001,WH-E2E,$d,3.000,0.000,267.00,2"
  done
} > "$DATA/sales.csv"
# 含错误行：第 2 行 SKU 不存在（CAT-3001）
{
  echo "sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count"
  echo "SKU-E2E-001,WH-E2E,$(date -d '-1 days' +%F),5.000,0.000,445.00,3"
  echo "SKU-NOT-EXIST,WH-E2E,$(date -d '-1 days' +%F),1.000,0.000,89.00,1"
} > "$DATA/sales-bad.csv"

# import_run <bizType> <file> <标签>
import_run(){
  local biz=$1 file=$2 tag=$3 bid code
  code=$(api -X POST "$BASE/api/v1/import/batches" -H "$AUTH" \
    -F "file=@$(winpath "$file")" -F "bizType=$biz")
  chk "$tag 上传" "201" "$code"
  bid=$(jget 'data.batch_id')
  case "$bid" in ''|*[!0-9]*) FAIL=$((FAIL+1)); log "[FAIL] $tag 未取到 batch_id：$(head -c 200 "$BODY")"; echo "" ; return ;; esac
  code=$(api "$BASE/api/v1/import/batches/$bid/preview" -H "$AUTH")
  chk "$tag 预览" "200" "$code"
  chk "$tag 总行数" "$4" "$(jget 'data.total_rows')"
  jraw 'data.suggested_mapping' > "$DATA/map.json"
  code=$(api -X PUT "$BASE/api/v1/import/batches/$bid/mapping" -H "$AUTH" \
    -H 'Content-Type: application/json' -d "{\"field_mapping_json\":$(cat "$DATA/map.json")}")
  chk "$tag 映射" "200" "$code"
  code=$(api -X POST "$BASE/api/v1/import/batches/$bid/validate" -H "$AUTH")
  chk "$tag 校验" "200" "$code"
  chk "$tag 错误行" "0" "$(jget 'data.error_rows')"
  code=$(api -X POST "$BASE/api/v1/import/batches/$bid/confirm" -H "$AUTH" \
    -H "Idempotency-Key: e2e-import-$biz" -H 'Content-Type: application/json' -d '{}')
  chk "$tag 确认" "200" "$code"
  chk "$tag 终态" "COMPLETED" "$(jget 'data.status')"
  IMPORT_BATCH[$biz]=$bid
}
declare -A IMPORT_BATCH 2>/dev/null || true

log "[S3] SUPPLIER 导入"
import_run SUPPLIER "$DATA/supplier.csv" "S3 供应商" 1
log "[S4] PRODUCT 导入"
import_run PRODUCT "$DATA/product.csv" "S4 商品" 1
log "[S5] SKU 导入"
import_run SKU "$DATA/sku.csv" "S5 SKU" 1
log "[S6] WAREHOUSE 导入"
import_run WAREHOUSE "$DATA/warehouse.csv" "S6 仓库" 1
log "[S7] INVENTORY_SNAPSHOT 导入"
import_run INVENTORY_SNAPSHOT "$DATA/inventory.csv" "S7 库存快照" 1
log "[S8] SALES_DAILY 导入（28 天）"
import_run SALES_DAILY "$DATA/sales.csv" "S8 销售" 28
sep

# 落库核对
chk "S8 suppliers 行数" "1" "$(sql 'SELECT COUNT(*) FROM suppliers')"
chk "S8 products 行数" "1" "$(sql 'SELECT COUNT(*) FROM products')"
chk "S8 skus 行数" "1" "$(sql 'SELECT COUNT(*) FROM skus')"
chk "S8 warehouses 行数" "1" "$(sql 'SELECT COUNT(*) FROM warehouses')"
chk "S8 sales_daily 行数" "28" "$(sql 'SELECT COUNT(*) FROM sales_daily')"
chk "S8 balances 行数" "1" "$(sql 'SELECT COUNT(*) FROM inventory_balances')"
chk "S8 INIT_IMPORT 流水" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE txn_type='INIT_IMPORT'")"
WH_ID=$(sql "SELECT id FROM warehouses WHERE code='WH-E2E'")
SKU_ID=$(sql "SELECT id FROM skus WHERE sku_code='SKU-E2E-001'")
log "    WH_ID=$WH_ID SKU_ID=$SKU_ID"
sep

log "[S9] 重复上传同一销售文件 ⇒ 409 IMP-4004"
code=$(api -X POST "$BASE/api/v1/import/batches" -H "$AUTH" \
  -F "file=@$(winpath "$DATA/sales.csv")" -F "bizType=SALES_DAILY")
chk "S9 重复上传 HTTP" "409" "$code"
chk "S9 错误码" "IMP-4004" "$(jget 'code')"
chk "S9 无重复数据" "28" "$(sql 'SELECT COUNT(*) FROM sales_daily')"
sep

log "[S10] 含错误行文件 ⇒ 校验期 error_rows=1 ⇒ 确认被 IMP-4009 阻断 ⇒ 错误报告可导出"
code=$(api -X POST "$BASE/api/v1/import/batches" -H "$AUTH" \
  -F "file=@$(winpath "$DATA/sales-bad.csv")" -F "bizType=SALES_DAILY")
chk "S10 上传" "201" "$code"
BAD_ID=$(jget 'data.batch_id')
api "$BASE/api/v1/import/batches/$BAD_ID/preview" -H "$AUTH" >/dev/null
jraw 'data.suggested_mapping' > "$DATA/map-bad.json"
api -X PUT "$BASE/api/v1/import/batches/$BAD_ID/mapping" -H "$AUTH" \
  -H 'Content-Type: application/json' -d "{\"field_mapping_json\":$(cat "$DATA/map-bad.json")}" >/dev/null
code=$(api -X POST "$BASE/api/v1/import/batches/$BAD_ID/validate" -H "$AUTH")
chk "S10 校验 HTTP" "200" "$code"
chk "S10 错误行数" "1" "$(jget 'data.error_rows')"
code=$(api -X POST "$BASE/api/v1/import/batches/$BAD_ID/confirm" -H "$AUTH" \
  -H "Idempotency-Key: e2e-import-bad" -H 'Content-Type: application/json' -d '{}')
chk "S10 确认被阻断 HTTP" "422" "$code"
chk "S10 阻断码" "IMP-4009" "$(jget 'code')"
chk "S10 阻断 details.error_rows" "1" "$(jget 'data.error_rows')"
code=$(api "$BASE/api/v1/import/batches/$BAD_ID/errors/export" -H "$AUTH")
chk "S10 错误报告导出" "200" "$code"
chk "S10 未落库（仍 28 行）" "28" "$(sql 'SELECT COUNT(*) FROM sales_daily')"
sep

log "[S11] 库存总览"
code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=$WH_ID&sku_id=$SKU_ID" -H "$AUTH")
chk "S11 HTTP" "200" "$code"
chk "S11 on_hand_qty" "10.000" "$(jget 'data.list.0.on_hand_qty')"
chk "S11 in_transit_qty（无采购单）" "0.000" "$(jget 'data.list.0.in_transit_qty')"
chk "S11 available_qty" "10.000" "$(jget 'data.list.0.available_qty')"
sep

log "[S12] 创建分析运行（ALL_ACTIVE, enable_llm=false）"
FROM=$(date -d '-27 days' +%F)
code=$(api -X POST "$BASE/api/v1/analysis/runs" -H "$AUTH" \
  -H "Idempotency-Key: e2e-run-1" -H 'Content-Type: application/json' \
  -d "{\"scope_type\":\"ALL_ACTIVE\",\"warehouse_id\":$WH_ID,\"date_from\":\"$FROM\",\"date_to\":\"$TODAY\",\"params\":{\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\",\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}")
chk "S12 创建运行" "202" "$code"
RUN_ID=$(jget 'data.run_id')
case "$RUN_ID" in ''|*[!0-9]*) FAIL=$((FAIL+1)); log "[FAIL] S12 未取到 run_id：$(head -c 240 "$BODY")"; RUN_ID="" ;; esac

log "[S13] 轮询至终态"
STATUS=""
for i in $(seq 1 60); do
  [ -n "$RUN_ID" ] || break
  api "$BASE/api/v1/analysis/runs/$RUN_ID" -H "$AUTH" >/dev/null
  STATUS=$(jget 'data.status')
  { [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "DEGRADED" ]; } && break
  sleep 2
done
chk "S13 run 终态" "SUCCEEDED" "$STATUS"
chk "S13 progress" "100" "$(jget 'data.progress')"
sep

log "[S14] 分析明细（D / IP / Q_suggest）"
Q_SUGGEST=""
if [ -n "$RUN_ID" ] && [ "$STATUS" = "SUCCEEDED" ]; then
  code=$(api "$BASE/api/v1/analysis/runs/$RUN_ID/items" -H "$AUTH")
  chk "S14 items HTTP" "200" "$code"
  chk "S14 total" "1" "$(jget 'data.total')"
  chk "S14 on_hand" "10.000" "$(jget 'data.list.0.on_hand_qty')"
  chk "S14 avg_daily_sales（D）" "3.000" "$(jget 'data.list.0.avg_daily_sales')"
  chk "S14 lead_time_days" "7" "$(jget 'data.list.0.lead_time_days')"
  chk "S14 coverage_days（R）" "7" "$(jget 'data.list.0.coverage_days')"
  # §10.2/§10.3：SS = D × ceil(L × ss_ratio) = 3 × ceil(7 × 0.5) = 12
  #            Q_base = 3 × (7+7) + 12 − IP(10) = 44 → MOQ/倍数 10 ⇒ Q_suggest = 50
  chk "S14 safety_stock（SS=3×4）" "12.000" "$(jget 'data.list.0.safety_stock_qty')"
  chk "S14 qty_base" "44.000" "$(jget 'data.list.0.qty_base')"
  chk "S14 qty_suggest（44 向上取整到 10 的倍数）" "50.000" "$(jget 'data.list.0.qty_suggest')"
  chk_ne "S14 risk_level" "NONE" "$(jget 'data.list.0.risk_level')"
  Q_SUGGEST=$(jget 'data.list.0.qty_suggest')
else
  skip "S14 分析明细（run 未成功）"
fi
sep

log "[S15] 建议生成（E-2 事件编排）→ 改量 30 → 提交审核"
ITEM_ID=""; ITEM_VER=""
if [ -n "$RUN_ID" ]; then
  code=$(api "$BASE/api/v1/replenishment/items?run_id=$RUN_ID" -H "$AUTH")
  chk "S15 建议列表 HTTP" "200" "$code"
  chk "S15 建议条数（分析完成自动派生）" "1" "$(jget 'data.total')"
  ITEM_ID=$(jget 'data.list.0.id')
  ITEM_VER=$(jget 'data.list.0.version')
  chk "S15 初始状态" "SUGGESTED" "$(jget 'data.list.0.status')"
  case "$ITEM_ID" in ''|*[!0-9]*) FAIL=$((FAIL+1)); log "[FAIL] S15 未取到建议 id" ;; *)
    code=$(api -X PATCH "$BASE/api/v1/replenishment/items/$ITEM_ID" -H "$AUTH_OP" \
      -H 'Content-Type: application/json' -d "{\"version\":$ITEM_VER,\"qty\":\"30.000\"}")
    chk "S15 改量 HTTP" "200" "$code"
    chk "S15 改后 qty" "30.000" "$(jget 'data.qty')"
    ITEM_VER=$(jget 'data.version')
    code=$(api -X POST "$BASE/api/v1/replenishment/items/$ITEM_ID/submit" -H "$AUTH_OP" \
      -H 'Content-Type: application/json' -d '{"comment":"E2E 提交审核"}')
    chk "S15 提交审核" "200" "$code"
    chk "S15 状态" "PENDING_REVIEW" "$(jget 'data.status')"
    ITEM_VER=$(jget 'data.version') ;;
  esac
else
  skip "S15 建议链路（无 run）"
fi
sep

log "[S16] 审批通过（OWNER）"
if [ -n "$ITEM_ID" ]; then
  code=$(api -X POST "$BASE/api/v1/replenishment/items/$ITEM_ID/approve" -H "$AUTH" \
    -H 'Content-Type: application/json' -d "{\"expected_version\":$ITEM_VER,\"comment\":\"E2E 批准\"}")
  chk "S16 审批 HTTP" "200" "$code"
  chk "S16 状态" "APPROVED" "$(jget 'data.status')"
  code=$(api "$BASE/api/v1/replenishment/items/$ITEM_ID/reviews" -H "$AUTH")
  chk "S16 审核时间线" "200" "$code"
  chk "S16 审核记录数（SUBMIT+APPROVE）" "2" "$(jget 'data.total')"
else
  skip "S16 审批（无建议）"
fi
sep

log "[S17] 转采购单 → 确认 → 下单；库存必须不变"
PO_ID=""
if [ -n "$ITEM_ID" ]; then
  code=$(api -X POST "$BASE/api/v1/purchasing/orders/from-replenishment" -H "$AUTH" \
    -H "Idempotency-Key: e2e-po-1" -H 'Content-Type: application/json' \
    -d "{\"replenishment_item_ids\":[$ITEM_ID],\"warehouse_id\":$WH_ID,\"remark\":\"E2E 转采购\"}")
  chk "S17 转单 HTTP" "201" "$code"
  chk "S17 转单状态" "DRAFT" "$(jget 'data.status')"
  PO_ID=$(jget 'data.purchase_order_id')
  chk "S17 采购数量" "30.000" "$(jget 'data.total_qty')"
  case "$PO_ID" in ''|*[!0-9]*) FAIL=$((FAIL+1)); log "[FAIL] S17 未取到采购单 id" ;; *)
    code=$(api "$BASE/api/v1/purchasing/orders/$PO_ID" -H "$AUTH")
    PV=$(jget 'data.version')
    code=$(api -X POST "$BASE/api/v1/purchasing/orders/$PO_ID/confirm" -H "$AUTH" \
      -H 'Content-Type: application/json' -d "{\"expected_version\":$PV}")
    chk "S17 确认 HTTP" "200" "$code"
    chk "S17 确认后状态" "PENDING" "$(jget 'data.status')"
    api "$BASE/api/v1/purchasing/orders/$PO_ID" -H "$AUTH" >/dev/null
    PV=$(jget 'data.version')
    code=$(api -X POST "$BASE/api/v1/purchasing/orders/$PO_ID/order" -H "$AUTH" \
      -H 'Content-Type: application/json' -d "{\"expected_version\":$PV}")
    chk "S17 下单 HTTP" "200" "$code"
    chk "S17 下单后状态" "ORDERED" "$(jget 'data.status')"
    chk "S17 建议已转单" "CONVERTED" "$(sql "SELECT status FROM replenishment_items WHERE id=$ITEM_ID")"
    # ★ 铁律④：建采购单/下单都不改库存
    chk "S17 库存不变" "10.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE sku_id=$SKU_ID")"
    chk "S17 在途物化列仍 0（零写入）" "0.000" \
      "$(sql "SELECT in_transit_qty FROM inventory_balances WHERE sku_id=$SKU_ID")"
    code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=$WH_ID&sku_id=$SKU_ID" -H "$AUTH")
    chk "S17 API in_transit" "30.000" "$(jget 'data.list.0.in_transit_qty')"
    POI_ID=$(sql "SELECT id FROM purchase_order_items WHERE purchase_order_id=$PO_ID LIMIT 1") ;;
  esac
else
  skip "S17 采购链路（无建议）"
fi
sep

log "[S18] 部分到货 20 件 → 确认 → PARTIALLY_RECEIVED"
GR1=""
if [ -n "${POI_ID:-}" ]; then
  code=$(api -X POST "$BASE/api/v1/purchasing/receipts" -H "$AUTH" \
    -H "Idempotency-Key: e2e-gr-1" -H 'Content-Type: application/json' \
    -d "{\"purchase_order_id\":$PO_ID,\"warehouse_id\":$WH_ID,\"receipt_date\":\"$TODAY\",\"remark\":\"E2E 首批\",\"items\":[{\"purchase_order_item_id\":$POI_ID,\"received_qty\":\"20.000\",\"unit_price\":\"35.5000\"}]}")
  chk "S18 建到货单" "201" "$code"
  GR1=$(jget 'data.goods_receipt_id')
  chk "S18 到货单初始态" "DRAFT" "$(jget 'data.status')"
  code=$(api -X POST "$BASE/api/v1/purchasing/receipts/$GR1/confirm" -H "$AUTH")
  chk "S18 确认到货" "200" "$code"
  chk "S18 到货单状态" "CONFIRMED" "$(jget 'data.status')"
  chk "S18 采购单状态" "PARTIALLY_RECEIVED" "$(sql "SELECT status FROM purchase_orders WHERE id=$PO_ID")"
  chk "S18 on_hand 10+20" "30.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE sku_id=$SKU_ID")"
  code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=$WH_ID&sku_id=$SKU_ID" -H "$AUTH")
  chk "S18 剩余在途 10" "10.000" "$(jget 'data.list.0.in_transit_qty')"
  chk "S18 PURCHASE_INBOUND 流水" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE txn_type='PURCHASE_INBOUND'")"
else
  skip "S18 部分到货（无采购单）"
fi
sep

log "[S19] 剩余到货 10 件 → 确认 → RECEIVED"
if [ -n "${POI_ID:-}" ]; then
  code=$(api -X POST "$BASE/api/v1/purchasing/receipts" -H "$AUTH" \
    -H "Idempotency-Key: e2e-gr-2" -H 'Content-Type: application/json' \
    -d "{\"purchase_order_id\":$PO_ID,\"warehouse_id\":$WH_ID,\"receipt_date\":\"$TODAY\",\"remark\":\"E2E 尾批\",\"items\":[{\"purchase_order_item_id\":$POI_ID,\"received_qty\":\"10.000\",\"unit_price\":\"35.5000\"}]}")
  chk "S19 建到货单" "201" "$code"
  GR2=$(jget 'data.goods_receipt_id')
  code=$(api -X POST "$BASE/api/v1/purchasing/receipts/$GR2/confirm" -H "$AUTH")
  chk "S19 确认到货" "200" "$code"
  chk "S19 采购单终态" "RECEIVED" "$(sql "SELECT status FROM purchase_orders WHERE id=$PO_ID")"
  chk "S19 on_hand 10+30" "40.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE sku_id=$SKU_ID")"
  code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=$WH_ID&sku_id=$SKU_ID" -H "$AUTH")
  chk "S19 在途归零" "0.000" "$(jget 'data.list.0.in_transit_qty')"
  chk "S19 PURCHASE_INBOUND 流水" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE txn_type='PURCHASE_INBOUND'")"
else
  skip "S19 剩余到货（无采购单）"
fi
sep

log "[S20] 对账 + 审计 + 只读 + 越权"
# 对账：余额 = 流水累加（P5 不变量），逐 (team, warehouse, sku) 比对
DIFF=$(sql "SELECT COUNT(*) FROM inventory_balances b LEFT JOIN (
              SELECT team_id, warehouse_id, sku_id, SUM(qty_delta) AS s
                FROM inventory_transactions GROUP BY team_id, warehouse_id, sku_id) t
              ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
            WHERE b.on_hand_qty <> COALESCE(t.s, 0)")
chk "S20 对账差额行数" "0" "$DIFF"
chk "S20 在途物化列非零行（零写入）" "0" "$(sql 'SELECT COUNT(*) FROM inventory_balances WHERE in_transit_qty <> 0')"
code=$(api "$BASE/api/v1/audit/logs?page=1&pageSize=50" -H "$AUTH")
chk "S20 审计查询 HTTP" "200" "$code"
chk_gt "S20 审计记录数" "10" "$(jget 'data.total')"
AUD_ACT=$(sql "SELECT COUNT(DISTINCT action) FROM audit_logs WHERE team_id=7")
chk_gt "S20 审计动作种类" "6" "$AUD_ACT"
# VIEWER 只读
code=$(api -X POST "$BASE/api/v1/purchasing/orders/from-replenishment" -H "$AUTH_VIEW" \
  -H "Idempotency-Key: e2e-viewer-try" -H 'Content-Type: application/json' \
  -d "{\"replenishment_item_ids\":[$ITEM_ID],\"warehouse_id\":$WH_ID}")
chk "S20 VIEWER 写操作 HTTP" "403" "$code"
# 跨团队：他团队 OWNER 读本团队采购单
if [ -n "${PO_ID:-}" ]; then
  code=$(api "$BASE/api/v1/purchasing/orders/$PO_ID" -H "$AUTH_OTHER")
  chk "S20 跨团队读采购单 HTTP" "404" "$code"
  # ERROR_CODES:226 —— PO-9004「采购单不存在」覆盖「无效/跨团队」（不泄漏他团队是否存在）
  chk "S20 跨团队错误码" "PO-9004" "$(jget 'code')"
else
  skip "S20 跨团队（无采购单）"
fi
sep

log "TOTAL: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
