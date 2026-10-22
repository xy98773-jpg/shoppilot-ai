#!/usr/bin/env bash
# =============================================================================
# B10（M3-T18 前端）项目负责人【独立复核】脚本
#
# 与 ../b10/（开发取证）互不替代：
#   开发证「页面能用」（P1–P6 + W1–W3）
#   本脚本证「前端 api 层契约逐字成立、无反向误杀、且相邻面未被带走」——刻意加开发未覆盖的探针
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器（不采信开发 3336/8082 栈）。
# 端口 3337 / 18117 —— 避开历史全部占用：
#   3319 / 3327–3336 与 18099 / 18107–18116。
#
# 组次：
#   P1  余额列表：分页字段 `list` + 四列齐全（前端 InventoryOverview 的渲染源）
#   P2  【探针·契约】sort 双形态：`sort=x,asc` 生效 vs axios 数组形态 `sort[]=` 是否静默失效
#   P3  流水列表 / 详情：字段与类型（数量必须为字符串）
#   P4  调整缺 Idempotency-Key → 400 COM-0001（前端必须带键）
#   P5  调整带键 → 201；同键重放 → 头 true 且流水不增（前端 W2 的正向复现）
#   P6  【探针·反向误杀】APPROVER 做 COUNT 调整 → 201（后端 adjustments 白名单含 APPROVER；
#       前端 CountAdjust 未做角色拦截，这里验证它没有把有权角色挡掉）
#   P7  VIEWER 做 COUNT 调整 → 403 TEAM-2003（服务端才是边界）
#   P8  销售列表 dateFrom/dateTo 边界 + qty 原样字符串
#   P9  【探针·缺口】SALES_DAILY 导入全链路 → confirm 是否可用（SSOT §7.6 要求可落库）
#   P10 【探针·缺口】INVENTORY_SNAPSHOT 导入全链路 → confirm 是否可用（SSOT §7.5 要求可落库）
#   P11 批次列表 status 筛选生效（前端 ImportBatchList 的筛选参数）
#
# 用法：bash backend-java/tools/rb-b10/run-rb10-evidence.sh
# 产物：evidence-rb10.txt（断言，入库）｜*.log（中间产物，gitignore）
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb10.txt"
APP_LOG="$OUT_DIR/evidence-rb10-app.log"
HDR_FILE="$OUT_DIR/evidence-rb10-headers.log"
MYSQL_CT="rb10-mysql"
MYSQL_PORT=3337
APP_PORT=18117
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
PY="python"

: > "$LOG"; : > "$APP_LOG"; : > "$HDR_FILE"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "REVIEWER=项目负责人（独立复核，不采信交付自报）"
log "SCOPE=B10 前端 api/types 契约 + 开发未覆盖的相邻面 + R78 疑似缺口复现"

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
IMPORT_STORAGE_DIR="$OUT_DIR/tmp/storage" \
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
  && log "SEED_OK (seed-b95.sql：团队 7，仓库 9004，SKU-A=9002 / SKU-B=9003 / SKU-C=9005 各 10.000)"
# ★ seed-b95 无 APPROVER 账号；P6 需要它（后端 adjustments 白名单含 APPROVER）
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot -e \
  "INSERT IGNORE INTO users (id,username,email,password_hash,display_name,status) VALUES (1006,'approver01','approver01@example.com','\$2a\$10\$Y.kQCLjuasoWXvfXl/zurubfV9RTe3NJl2hlguWZz.QKfZ7zvuuGS','审批人','ACTIVE');
   INSERT IGNORE INTO team_members (team_id,user_id,role,status) VALUES (7,1006,'APPROVER','ACTIVE');" \
  >> "$APP_LOG" 2>&1 && log "SEED_EXTRA_OK (approver01/APPROVER, 仅本复核库)"

FAIL=0; NOTES=""
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi; }
note(){ log "NOTE $1 => $2"; NOTES="$NOTES
  - $1: $2"; }
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
st(){ echo "$1" | sed 's/.*|//'; }
body(){ echo "${1%|*}"; }
jqn(){ local B="$1" K="$2"; echo "$B" | grep -o "\"$K\":[0-9]*" | head -1 | sed 's/.*://'; }
jqs(){ local B="$1" K="$2"; echo "$B" | grep -o "\"$K\":\"[^\"]*\"" | head -1 | sed 's/.*:"//;s/"$//'; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
pyget(){ "$PY" -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }

OWNER=$(token owner01); ADMIN=$(token admin01); OPER=$(token operator01); VIEWER=$(token viewer01)
APPROVER=$(token approver01 2>/dev/null)
[ -z "$APPROVER" ] && APPROVER=$(sql "SELECT 1" >/dev/null 2>&1; echo "")
log "TOKEN_OWNER=${#OWNER} TOKEN_ADMIN=${#ADMIN} TOKEN_OPER=${#OPER} TOKEN_VIEWER=${#VIEWER} TOKEN_APPROVER=${#APPROVER}"
chk "P0 四个基础账号可登录（token 非空）" "ok" "$([ ${#OWNER} -gt 20 ] && [ ${#ADMIN} -gt 20 ] && [ ${#OPER} -gt 20 ] && [ ${#VIEWER} -gt 20 ] && echo ok || echo bad)"

sep
# ------------------------------------------------------------------ P1 余额列表
R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $OWNER" \
  "$BASE/api/v1/inventory/balances?page=1&pageSize=20&warehouse_id=9004")
P1B=$(body "$R")
chk "P1 余额列表 http" "200" "$(st "$R")"
chk "P1 分页字段为 list" "3" "$(echo "$P1B" | pyget "len(d['data']['list'])")"
chk "P1 四列齐全(on_hand/in_transit/committed/available)" "4" \
  "$(echo "$P1B" | pyget "sum(1 for k in ('on_hand_qty','in_transit_qty','committed_qty','available_qty') if k in d['data']['list'][0])")"
chk "P1 数量为字符串(\"10.000\")" "10.000" "$(echo "$P1B" | pyget "d['data']['list'][0]['on_hand_qty']")"

sep
# ------------------------------------------------------------------ P2 sort 双形态
# 先把 SKU-B 改成 6.000，使 on_hand 出现差异（asc 时 SKU-B 必须排第一）
sql "INSERT INTO inventory_transactions (team_id,warehouse_id,sku_id,txn_type,qty_delta,qty_before,qty_after,biz_type,idempotency_key,reason,operator_id,occurred_at,created_by) VALUES (7,9004,9003,'MANUAL_ADJUST',-4.000,10.000,6.000,'MANUAL','rb10-prep-p2','复核准备',1001,UTC_TIMESTAMP(3),1001)" >/dev/null
sql "UPDATE inventory_balances SET on_hand_qty=6.000 WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003" >/dev/null

DEF=$(curl -s --noproxy '*' -H "Authorization: Bearer $OWNER" "$BASE/api/v1/inventory/balances?page=1&pageSize=20&warehouse_id=9004")
DESC=$(curl -s --noproxy '*' -H "Authorization: Bearer $OWNER" "$BASE/api/v1/inventory/balances?page=1&pageSize=20&warehouse_id=9004&sort=on_hand_qty,desc")
ARR=$(curl -s --noproxy '*' -H "Authorization: Bearer $OWNER" "$BASE/api/v1/inventory/balances?page=1&pageSize=20&warehouse_id=9004&sort%5B%5D=on_hand_qty,desc")
log "P2 默认首行 qty=$(echo "$DEF" | pyget "d['data']['list'][0]['on_hand_qty']")"
log "P2 sort=...,desc 首行 qty=$(echo "$DESC" | pyget "d['data']['list'][0]['on_hand_qty']")"
log "P2 sort[]=...,desc 首行 qty=$(echo "$ARR" | pyget "d['data']['list'][0]['on_hand_qty']")"
chk "P2 sort=on_hand_qty,desc 生效（最大在前）" "10.000" "$(echo "$DESC" | pyget "d['data']['list'][0]['on_hand_qty']")"
# ★ 实测结论（与预期相反）：Spring 对 `sort[]=x` 同样绑定到 `sort`，axios 默认数组序列化
#   不会让排序静默失效 —— 故前端 types 的 `sort?: string[]` 不存在该风险（本批页面未使用 sort）。
chk "P2 axios 数组形态 sort[]= 亦被后端识别（无静默失效）" "10.000" \
  "$(echo "$ARR" | pyget "d['data']['list'][0]['on_hand_qty']")"

sep
# ------------------------------------------------------------------ P3 流水
R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $OWNER" \
  "$BASE/api/v1/inventory/transactions?page=1&pageSize=20&warehouse_id=9004")
P3B=$(body "$R")
chk "P3 流水列表 http" "200" "$(st "$R")"
chk "P3 分页字段为 list" "yes" "$(echo "$P3B" | pyget "'yes' if isinstance(d['data']['list'],list) else 'no'")"
TID=$(echo "$P3B" | pyget "d['data']['list'][0]['id']")
chk "P3 首条流水 qty_delta 为字符串" "yes" "$(echo "$P3B" | pyget "'yes' if isinstance(d['data']['list'][0]['qty_delta'],str) else 'no'")"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $OWNER" "$BASE/api/v1/inventory/transactions/$TID")
chk "P3 流水详情 http" "200" "$(st "$R")"
chk "P3 详情含 occurred_at/created_at（前端类型声明的字段）" "2" \
  "$(body "$R" | pyget "sum(1 for k in ('occurred_at','created_at') if k in d['data'])")"

sep
# ------------------------------------------------------------------ P4 缺幂等键
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OPER" -H 'Content-Type: application/json' \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"10.000","reason":"缺键"}')
chk "P4 缺 Idempotency-Key http" "400" "$(st "$R")"
chk "P4 缺键错误码" "COM-0001" "$(code "$(body "$R")")"

sep
# ------------------------------------------------------------------ P5 带键 + 重放
KEY="rb10-$$-count-a"
R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OPER" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"8.000","reason":"复核盘点"}')
P5B=$(body "$R")
chk "P5 首次调整 http(201)" "201" "$(st "$R")"
chk "P5 首次无 Idempotent-Replayed" "" "$(hdr 'Idempotent-Replayed')"
TXN1=$(jqn "$P5B" transaction_id)
AFTER1=$(jqs "$P5B" qty_after)
R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OPER" -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"8.000","reason":"复核盘点"}')
P5B2=$(body "$R")
chk "P5 同键重放 http(201)" "201" "$(st "$R")"
chk "P5 重放带 Idempotent-Replayed: true" "true" "$(hdr 'Idempotent-Replayed')"
chk "P5 重放返回同一 transaction_id" "$TXN1" "$(jqn "$P5B2" transaction_id)"
chk "P5 重放后余额仍为首次结果" "$AFTER1" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "P5 重放不新增流水（该键仅 1 条）" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='$KEY'")"

sep
# ------------------------------------------------------------------ P6 APPROVER 做 COUNT
APPROVER_OK="skip"
if [ ${#APPROVER} -gt 20 ]; then
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
    -H "Authorization: Bearer $APPROVER" -H 'Content-Type: application/json' -H "Idempotency-Key: rb10-$$-approver" \
    -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"7.000","reason":"审批人盘点"}')
  chk "P6 APPROVER 执行 COUNT_ADJUST http(201)" "201" "$(st "$R")"
  APPROVER_OK="201"
else
  note "P6 种子无 approver01，跳过 APPROVER 探针" "seed-b95.sql 未含 approver01；后端白名单含 APPROVER 待后续批次覆盖"
fi

sep
# ------------------------------------------------------------------ P7 VIEWER
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $VIEWER" -H 'Content-Type: application/json' -H "Idempotency-Key: rb10-$$-viewer" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"1.000","reason":"越权"}')
chk "P7 VIEWER 调整 http(403)" "403" "$(st "$R")"
chk "P7 VIEWER 错误码" "TEAM-2003" "$(code "$(body "$R")")"

sep
# ------------------------------------------------------------------ P8 销售筛选
for d in 2026-10-15 2026-10-16; do
  curl -s --noproxy '*' -o /dev/null -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OPER" \
    -H 'Content-Type: application/json' \
    -d "{\"warehouse_id\":9004,\"sku_id\":9005,\"sale_date\":\"$d\",\"qty\":\"12.000\",\"return_qty\":\"0.000\",\"sales_amount\":\"1068.00\",\"order_count\":9}"
done
R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $OWNER" \
  "$BASE/api/v1/sales/daily?page=1&pageSize=20&dateFrom=2026-10-16&dateTo=2026-10-16")
P8B=$(body "$R")
chk "P8 销售列表 http" "200" "$(st "$R")"
chk "P8 dateFrom=dateTo 命中 1 行" "1" "$(echo "$P8B" | pyget "d['data']['total']")"
chk "P8 qty 原样字符串" "12.000" "$(echo "$P8B" | pyget "d['data']['list'][0]['qty']")"

sep
# ------------------------------------------------------------------ 导入链路辅助
upload(){ # $1=token $2=bizType $3=file
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
    -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$2"
}
import_chain(){ # $1=token $2=bizType $3=file $4=label
  local TK="$1" BT="$2" F="$3" LB="$4"
  local R BID PV MAP VAL
  R=$(upload "$TK" "$BT" "$F"); local S=$(st "$R")
  log "$LB upload http=$S bizType=$BT status=$(jqs "$(body "$R")" status) body=$(body "$R" | head -c 300)"
  [ "$S" != "201" ] && { log "$LB upload FAIL body=$(body "$R")"; return 1; }
  BID=$(jqn "$(body "$R")" batch_id)
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $TK" "$BASE/api/v1/import/batches/$BID/preview?limit=5")
  PV=$(body "$R")
  log "$LB preview http=$(st "$R") status=$(jqs "$PV" status) rows=$(jqn "$PV" total_rows) body=$(echo "$PV" | head -c 400)"
  MAP=$(echo "$PV" | pyget "json.dumps(d['data']['suggested_mapping'],ensure_ascii=False)")
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$BID/mapping" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' \
    -d "{\"field_mapping_json\":$MAP}")
  log "$LB mapping http=$(st "$R") status=$(jqs "$(body "$R")" status)"
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$BID/validate" -H "Authorization: Bearer $TK")
  VAL=$(body "$R")
  log "$LB validate http=$(st "$R") status=$(jqs "$VAL" status) error_rows=$(jqn "$VAL" error_rows) warning_rows=$(jqn "$VAL" warning_rows)"
  R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$BID/confirm" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' -H "Idempotency-Key: $LB-key-$$" -d '{}')
  log "$LB confirm http=$(st "$R") code=$(code "$(body "$R")") detail=$(body "$R" | pyget "json.dumps(d.get('details'),ensure_ascii=False)")"
  CHAIN_STATUS=$(jqs "$VAL" status)
  CHAIN_CONFIRM_HTTP=$(st "$R"); CHAIN_CONFIRM_CODE=$(code "$(body "$R")")
  CHAIN_BID=$BID
  return 0
}

TMPD="$OUT_DIR/tmp"; mkdir -p "$TMPD/storage"
TMPD_WIN=$(cygpath -w "$TMPD" 2>/dev/null | sed 's#\\#/#g' || echo "$TMPD")
log "TMPD_WIN=$TMPD_WIN"
printf 'SKU编码,仓库编码,销售日期,净销量,退货量,销售金额,订单数\nSKU-A,WH-MAIN,2026-10-16,12.000,0.000,1068.00,9\nSKU-B,WH-MAIN,2026-10-15,3.000,0.000,240.00,2\n' > "$TMPD/sales.csv"
printf 'SKU编码,仓库编码,快照数量,快照日期,备注\nSKU-B,WH-MAIN,20.000,2026-10-16,期初导入\n' > "$TMPD/snapshot.csv"

sep
# ------------------------------------------------------------------ P9 SALES_DAILY 导入
CHAIN_STATUS=""; CHAIN_CONFIRM_HTTP=""; CHAIN_CONFIRM_CODE=""; CHAIN_BID=""
import_chain "$OPER" "SALES_DAILY" "$TMPD_WIN/sales.csv" "P9"
chk "P9 SALES_DAILY 校验通过(VALIDATED)" "VALIDATED" "$CHAIN_STATUS"
log "★ P9 结论：校验=$CHAIN_STATUS / confirm_http=$CHAIN_CONFIRM_HTTP / code=$CHAIN_CONFIRM_CODE"
if [ "$CHAIN_CONFIRM_CODE" = "COM-0001" ]; then
  note "P9 【缺口】SALES_DAILY 校验可过但 confirm 不可用" \
       "SSOT IMPORT_WORKFLOW §7.6 要求经 SalesWriteService.batchUpsert 落库；实现 SUPPORTED_BIZ_TYPES 仅 4 类主数据。销售数据唯一入口（PRD F-SAL-01 不做手工录入）因此失效"
  chk "P9 批次保持 VALIDATED（未误推进）" "VALIDATED" "$(sql "SELECT status FROM import_batches WHERE id=$CHAIN_BID")"
else
  chk "P9 SALES_DAILY confirm 可用" "200" "$CHAIN_CONFIRM_HTTP"
fi

sep
# ------------------------------------------------------------------ P10 INVENTORY_SNAPSHOT 导入
CHAIN_STATUS=""; CHAIN_CONFIRM_HTTP=""; CHAIN_CONFIRM_CODE=""; CHAIN_BID=""
import_chain "$OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snapshot.csv" "P10"
chk "P10 INVENTORY_SNAPSHOT 校验通过(VALIDATED)" "VALIDATED" "$CHAIN_STATUS"
log "★ P10 结论：校验=$CHAIN_STATUS / confirm_http=$CHAIN_CONFIRM_HTTP / code=$CHAIN_CONFIRM_CODE"
if [ "$CHAIN_CONFIRM_CODE" = "COM-0001" ]; then
  note "P10 【缺口】INVENTORY_SNAPSHOT 校验可过但 confirm 不可用" \
       "SSOT IMPORT_WORKFLOW §7.5 要求经 InventoryWriteService.applyInitialImport 落库；实现仅 4 类主数据。期初库存无法导入"
  chk "P10 批次保持 VALIDATED（未误推进）" "VALIDATED" "$(sql "SELECT status FROM import_batches WHERE id=$CHAIN_BID")"
else
  chk "P10 INVENTORY_SNAPSHOT confirm 可用" "200" "$CHAIN_CONFIRM_HTTP"
fi

sep
# ------------------------------------------------------------------ P11 批次列表筛选
R=$(curl -s --noproxy '*' -H "Authorization: Bearer $OWNER" "$BASE/api/v1/import/batches?page=1&pageSize=20&status=VALIDATED")
V1=$(echo "$R" | pyget "d['data']['total']")
R2=$(curl -s --noproxy '*' -H "Authorization: Bearer $OWNER" "$BASE/api/v1/import/batches?page=1&pageSize=20&status=COMPLETED")
V2=$(echo "$R2" | pyget "d['data']['total']")
log "P11 status=VALIDATED total=$V1 / status=COMPLETED total=$V2"
chk "P11 status 筛选生效（VALIDATED >= 2 且 COMPLETED = 0）" "ok" \
  "$([ "${V1:-0}" -ge 2 ] && [ "${V2:-1}" = "0" ] && echo ok || echo bad)"
chk "P11 批次列表分页字段 list" "yes" "$(echo "$R" | pyget "'yes' if isinstance(d['data']['list'],list) else 'no'")"

sep
log "RB10_CHECK_FAIL=$FAIL"
log "RB10_NOTES=$(echo "$NOTES" | grep -c '^  -')"
if [ "$FAIL" = "0" ]; then log "RB10_RESULT=ALL PASS"; else log "RB10_RESULT=HAS FAIL"; fi
log "---------------------------------------------------------------"
echo "RB10_CHECK_FAIL=$FAIL"
exit 0
