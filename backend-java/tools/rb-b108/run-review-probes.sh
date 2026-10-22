#!/usr/bin/env bash
# =============================================================================
# B10.8 独立复核探针（评审自建，不复用开发脚本）
#
# 开发 8 组（G1–G8 / 64 项）已覆盖：R85-a 快照大小写、R85-b 销售 SAL-6004、
#   R85-c 主数据 CAT-3002、R85-d 回归同大小写、G5 全小写引用解析（快照）、
#   G6 R80 跨仓、G7 R79 PARTIAL_SUCCESS、G8 seed 对账。
#
# 本脚本只补「SSOT 明文或任务书明文要求，但开发 8 组均未覆盖」的场景：
#
#   N1 ★★★ 主数据导入的小写编码（catalog Pass 1 预取表的键语义）
#       开发 G3 只测了「同一文件内两行 SKU-A + sku-a ⇒ CAT-3002」，
#       测的是 importdata 侧的 duplicateCode；G5 只测了「快照引用既有 SKU」。
#       ★ 未覆盖：SKU 导入文件【只有一行】 sku-a（库里是 SKU-A）——
#         importdata 侧 validate 会报 CAT-3002 吗？若不报，confirm 走
#         catalogWriteService.upsertSkusByCode → preloadSkuIdsByCode 用
#         in("sku_code", …)（ci 匹配，能查到 SKU-A），但返回表 ids.put(row.getSkuCode())
#         的键是【库中原样写法 SKU-A】；下游 doUpsertSkuByCode 用文件原样
#         preloadedIds.get("sku-a") ⇒ 落空 ⇒ 走 insertSku ⇒ 撞 uk_sku_team_code
#         ⇒ DuplicateKeyException → 被 C1b 当行级失败 ⇒ PARTIAL_SUCCESS。
#         即：DB 唯一键是 ci 的（不可能并存两条），但应用层把 ci 命中读成了「不存在」。
#   N2 ★★ 同上，WAREHOUSE 侧（独立预取表 preloadWarehouseIdsByCode）
#   N3 ★★ 同上，SUPPLIER 侧
#   N4 ★ 反向误杀面：文件内 SKU-A + sku-a 两行判 CAT-3002 后，
#       【不能】误杀「SKU-A 与 SKU-B」这种真不同编码（证明折叠没过头）
#   N5 ★ seed 对账（B10.8 新增 seed-init-9003 后应 0 行，不额外补流水）
#   N6 ★ 边界：R80 跨仓 / R79 PARTIAL_SUCCESS 不回退（各 1 条）
#   N7 ★★ 大小写折叠不得影响「真正不同的编码」：SKU-AB vs SKU-A B（trim 语义）
#
# 环境：自建 fat jar + 独立 MySQL 8.0.36（:3356）+ 应用 :18220
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb-b108.txt"
APP_LOG="$OUT_DIR/evidence-rb-b108-app.log"
MYSQL_CT="rb108-mysql"
MYSQL_PORT="${RB108_MYSQL_PORT:-3356}"
APP_PORT="${RB108_APP_PORT:-18220}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
STORE_DIR="/tmp/rb108-store"

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "REVIEWER=architect-independent-probe"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

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

rm -rf "$STORE_DIR" 2>/dev/null || true
mkdir -p "$STORE_DIR"

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
IMPORT_STORAGE_DIR="$STORE_DIR" \
JWT_SECRET="$JWT_SECRET" \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, IMPORT_STORAGE_DIR=$STORE_DIR)"

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
  && log "SEED_OK (seed-b95.sql)" || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi; }
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$(win "$2");type=text/csv" -F "bizType=$3"; }
getq(){ curl -s --noproxy '*' -w "%{http_code}" -H "Authorization: Bearer $1" "$BASE$2" 2>/dev/null; }
getqf(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ local payload="${4-}"; [ -z "$payload" ] && payload='{}'
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE$2" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$payload"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
errAll(){ sql "SELECT GROUP_CONCAT(CONCAT(row_no,'|',error_code,'|',error_level) ORDER BY row_no,error_id SEPARATOR ' ') FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR'"; }
errMsg(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',REPLACE(REPLACE(message,'\n',' '),'|','/')) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
balw(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=$1 AND sku_id=$2"; }
txn(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }

MAPSKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'
MAPWHS='{"field_mapping_json":{"code":"code","name":"name"},"parse_options_json":{}}'
MAPSUP='{"field_mapping_json":{"code":"code","name":"name"},"parse_options_json":{}}'
MAPINV='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty"},"parse_options_json":{}}'

run_chain(){ # $1 tag  $2 bizType  $3 csv  $4 mapping  $5 idempotencyKey
  local R B
  R=$(upload "$OP" "$3" "$2"); B=$(jqn "$(body "$R")" batch_id)
  log "$1-上传=$(st "$R") batch=$B"
  R=$(getqf "$OP" "/api/v1/import/batches/$B/preview?limit=50"); log "$1-预览=$(st "$R")"
  R=$(putm "$OP" "$B" "$4"); log "$1-映射=$(st "$R")"
  R=$(validate "$OP" "$B")
  log "$1-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
  log "$1-行级ERROR=$(errAll "$B")"
  R=$(postn "$OP" "/api/v1/import/batches/$B/confirm" "$5")
  log "$1-confirm=$(st "$R") status=$(jstr "$(body "$R")" status) success=$(jqn "$(body "$R")" success_rows) failed=$(jqn "$(body "$R")" failed_rows)"
  log "$1-confirm-body=$(body "$R")"
  CHAIN_BATCH="$B"
  CHAIN_HTTP="$(st "$R")"
  CHAIN_CONFIRM="$(body "$R")"
}

OP=$(token operator01); ADM=$(token admin01)
log "TOKEN op=${OP:0:8} admin=${ADM:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE')"
log "SETUP 仓库=$(sql "SELECT GROUP_CONCAT(code ORDER BY id) FROM warehouses WHERE team_id=7")"
log "SETUP 种子SKU=$(sql "SELECT GROUP_CONCAT(sku_code ORDER BY id) FROM skus WHERE team_id=7")"

# ============================================================ N1 主数据小写编码
sep; log "N1 ★★★ SKU 导入【单行小写编码】：库里是 SKU-A，文件写 sku-a"
log "   预期（按 R85 裁定「引用解析一律大小写不敏感等价」）：应命中既有 SKU-A 走【更新】语义。"
log "   实际风险：catalog Pass1 的 preloadSkuIdsByCode 返回表键是【库中原样写法】，"
log "            下游 preloadedIds.get(文件原样) 落空 ⇒ insertSku ⇒ 撞 uk_sku_team_code。"
CSV="$OUT_DIR/n1-sku-lower-single.csv"
printf 'sku_code,product_code\nsku-a,p-bag-001\n' > "$CSV"
run_chain "N1" SKU "$CSV" "$MAPSKU" "rb108-n1"
chk "N1-SKU总数未增（未误插新行）" "3" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "N1-SKU-A仍只有一条" "1" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND sku_code='SKU-A'")"
log "N1-SKU-A行=$(sql "SELECT CONCAT(id,'|',sku_code,'|',product_id) FROM skus WHERE team_id=7 AND sku_code='SKU-A'")"
log "N1-★关键：若 confirm 非 COMPLETED 且行级码为 CAT-3002/重复类 ⇒ 命中 R86"

# ============================================================ N2 WAREHOUSE 小写
sep; log "N2 ★★ WAREHOUSE 导入【单行小写编码】：库里 WH-MAIN，文件写 wh-main"
CSV="$OUT_DIR/n2-wh-lower-single.csv"
printf 'code,name\nwh-main,主仓改名\n' > "$CSV"
run_chain "N2" WAREHOUSE "$CSV" "$MAPWHS" "rb108-n2"
chk "N2-仓库数未增" "2" "$(sql "SELECT COUNT(*) FROM warehouses WHERE team_id=7")"
log "N2-WH-MAIN行=$(sql "SELECT CONCAT(id,'|',code,'|',name) FROM warehouses WHERE team_id=7 AND code='WH-MAIN'")"

# ============================================================ N2b PRODUCT 小写
sep; log "N2b ★★ PRODUCT 导入【单行小写编码】：库里 P-BAG-001，文件写 p-bag-001"
CSV="$OUT_DIR/n2b-product-lower.csv"
printf 'product_code,name\np-bag-001,箱包改名\n' > "$CSV"
MAPPRD='{"field_mapping_json":{"product_code":"product_code","name":"name"},"parse_options_json":{}}'
run_chain "N2b" PRODUCT "$CSV" "$MAPPRD" "rb108-n2b"
chk "N2b-商品数未增" "1" "$(sql "SELECT COUNT(*) FROM products WHERE team_id=7")"
log "N2b-P-BAG-001行=$(sql "SELECT CONCAT(id,'|',product_code,'|',name) FROM products WHERE id=9001")"

# ============================================================ N3 SUPPLIER 小写
sep; log "N3 ★★ SUPPLIER 导入【单行小写编码】（种子里无供应商，现场建一条）"
sql "INSERT INTO suppliers (id, team_id, code, name, status, version, created_at, updated_at)
     VALUES (9500, 7, 'SUP-X', '供应商X', 'ACTIVE', 0, NOW(3), NOW(3))"
sup_id=$(sql "SELECT id FROM suppliers WHERE team_id=7 LIMIT 1")
log "SETUP 供应商=$sup_id code=$(sql "SELECT code FROM suppliers WHERE id=$sup_id")"
CSV="$OUT_DIR/n3-sup-lower-single.csv"
printf 'code,name\nsup-x,供应商X改名\n' > "$CSV"
run_chain "N3" SUPPLIER "$CSV" "$MAPSUP" "rb108-n3"
chk "N3-供应商数未增" "1" "$(sql "SELECT COUNT(*) FROM suppliers WHERE team_id=7")"
log "N3-SUP-X行=$(sql "SELECT CONCAT(id,'|',code,'|',name) FROM suppliers WHERE id=9500")"

# ============================================================ N4 反向误杀：真不同编码
sep; log "N4 ★ 反向误杀面：同文件两个【真不同】的 SKU 不得被判重复"
CSV="$OUT_DIR/n4-sku-distinct.csv"
printf 'sku_code,product_code\nSKU-AB,P-BAG-001\nSKU-AC,P-BAG-001\n' > "$CSV"
run_chain "N4" SKU "$CSV" "$MAPSKU" "rb108-n4"
chk "N4-两个新SKU都建成" "2" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND sku_code IN ('SKU-AB','SKU-AC')")"

# ============================================================ N5 trim 语义边界
sep; log "N5 ★★ trim 边界：' SKU-A '（首尾空格）与 'SKU-A' 应判同一编码（重复）"
CSV="$OUT_DIR/n5-sku-space.csv"
printf 'sku_code,product_code\n SKU-A ,P-BAG-001\nSKU-A,P-BAG-001\n' > "$CSV"
run_chain "N5" SKU "$CSV" "$MAPSKU" "rb108-n5"
log "N5-★若未判重复则说明 trim 未生效（SKUS 应仍为 3）"

# ============================================================ N6 快照：大小写变体已闭环（回归）
sep; log "N6 ★ 回归：INVENTORY_SNAPSHOT 同仓 SKU-A + sku-a 应判 COM-0001 + 422"
CSV="$OUT_DIR/n6-snap-case.csv"
printf 'sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,2.000\nsku-a,WH-MAIN,3.000\n' > "$CSV"
run_chain "N6" INVENTORY_SNAPSHOT "$CSV" "$MAPINV" "rb108-n6"
chk "N6-confirm被前置门拦（422）" "422" "$CHAIN_HTTP"
chk "N6-错误码=IMP-4009" "IMP-4009" "$(jstr "$CHAIN_CONFIRM" code)"
chk "N6-余额未变" "10.000" "$(balw 9004 9002)"

# ============================================================ N7 边界不回退
sep; log "N7 ★ 边界：R80 跨仓（大小写混用）+ R79 PARTIAL_SUCCESS"
CSV="$OUT_DIR/n7-cross-wh.csv"
printf 'sku_code,warehouse_code,qty\nSKU-C,WH-MAIN,1.000\nsku-c,wh-b,2.000\n' > "$CSV"
run_chain "N7" INVENTORY_SNAPSHOT "$CSV" "$MAPINV" "rb108-n7"
chk "N7-跨仓COMPLETED" "COMPLETED" "$(jstr "$CHAIN_CONFIRM" status)"
log "N7-主仓余额=$(balw 9004 9005) 副仓余额=$(balw 9006 9005)"

CSV="$OUT_DIR/n7b-sales-partial.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-A,WH-MAIN,2026-10-10,1,0,10.00,1\nSKU-B,WH-MAIN,2026-10-10,1,0,10.00,1\n' > "$CSV"
# 制造漂移：给 SKU-B 设上架日晚于 sale_date ⇒ 写服务内部抛 SAL-6003（校验阶段看不出来）
sql "UPDATE skus SET listed_date='2026-10-20' WHERE team_id=7 AND id=9003"
log "N7b-SKU-B上架日=$(sql "SELECT listed_date FROM skus WHERE id=9003")（sale_date=2026-10-10）"
MAPSALES='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty","return_qty":"return_qty","sales_amount":"sales_amount","order_count":"order_count"},"parse_options_json":{}}'
run_chain "N7b" SALES_DAILY "$CSV" "$MAPSALES" "rb108-n7b"
chk "N7b-PARTIAL_SUCCESS（R79 不回退）" "PARTIAL_SUCCESS" "$(jstr "$CHAIN_CONFIRM" status)"
chk "N7b-行级码=SAL-6003（非 COM-0002）" "SAL-6003" "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$CHAIN_BATCH AND error_level='ERROR' ORDER BY row_no LIMIT 1")"

# ============================================================ N8 seed 对账
sep; log "N8 ★ seed 对账（B10.8 已补 seed-init-9003，不额外补流水）"
log "N8-种子流水=$(sql "SELECT GROUP_CONCAT(CONCAT(sku_id,'/',idempotency_key) ORDER BY sku_id) FROM inventory_transactions WHERE team_id=7 AND txn_type='INIT_IMPORT'")"
chk "N8-§7.3 余额可由流水推导违例行" "0" "$(sql "SELECT COUNT(*) FROM (SELECT b.sku_id FROM inventory_balances b LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.sku_id=b.sku_id AND t.warehouse_id=b.warehouse_id AND t.txn_type<>'REVERSAL' WHERE b.team_id=7 GROUP BY b.sku_id,b.warehouse_id,b.on_hand_qty HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x")"
chk "N8-qty_after 恒等式违例" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta")"
chk "N8-无流水余额行" "0" "$(sql "SELECT COUNT(*) FROM inventory_balances b WHERE b.team_id=7 AND NOT EXISTS (SELECT 1 FROM inventory_transactions t WHERE t.team_id=b.team_id AND t.sku_id=b.sku_id AND t.warehouse_id=b.warehouse_id)")"

# ============================================================ 汇总
sep
TOTAL=$(grep -acE "^(PASS|FAIL) " "$LOG")
log "RB108_CHECK_TOTAL=$TOTAL"
log "RB108_FAIL_COUNT=$FAIL"
if [ "$FAIL" -eq 0 ]; then log "RB108_RESULT=ALL PASS"; else log "RB108_RESULT=FAIL"; log "失败清单:$FAILED_LIST"; fi
exit 0
