#!/usr/bin/env bash
# =============================================================================
# B10.5（R78：导入确认补挂 INVENTORY_SNAPSHOT / SALES_DAILY）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3338 / 18118 —— 刻意错开回修单【5】2 点名的
#   3319、3327–3337 与 18099、18107–18117（3338 紧接其后，18118 同理）。
#
# 组次（对应回修单【5】真栈 5 条断言）：
#   G1  INVENTORY_SNAPSHOT：upload→preview→mapping→validate→confirm 全绿；
#       余额【增量】（10.000 + 120.000 = 130.000）、INIT_IMPORT 流水 1 条/行、
#       biz_type=IMPORT、biz_ref_id=batchId、幂等键 INIT_IMPORT:IMPORT:<batch>:<sku>、
#       二次 confirm 为重放且不重复累加。
#   G2  SALES_DAILY：全绿 + sales_daily 字段（source=IMPORT / batch_id / product_id）；
#       重复导入同一业务键为【覆盖】不累加 + IMP-4006 可见（error_level=WARNING）+
#       SALES_DAILY_UPSERT 审计 change_summary 含「覆盖 N 行」。
#   G3  未知 sku_code（校验后漂移）→ confirm 200 + PARTIAL_SUCCESS + 该行 CAT-3001，
#       且失败行不落库。
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b10.5/run-b105-evidence.sh
#
# 产物：
#   backend-java/tools/b10.5/evidence-b105.txt          ← 断言日志（入库）
#   backend-java/tools/b10.5/evidence-b105-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b10.5/evidence-b105-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（B8 脚本第一版即因此丢过整段）。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# ★ curl 必须 --noproxy '*'：本机全局 http 代理（Clash 127.0.0.1:7890）会劫持 localhost。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b105.txt"
APP_LOG="$OUT_DIR/evidence-b105-app.log"
HDR_FILE="$OUT_DIR/evidence-b105-headers.log"
CSV_DIR="$OUT_DIR"
MYSQL_CT="b105-mysql"
MYSQL_PORT="${B105_MYSQL_PORT:-3338}"
APP_PORT="${B105_APP_PORT:-18118}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
# 过去日期（SAL-6003 只拦未来日期）
D1=2026-10-10; D2=2026-10-11; D3=2026-10-12; D4=2026-10-13

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b105-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队 7，仓库 9004，SKU-A=9002 / SKU-B=9003 / SKU-C=9005 各 10.000)" \
  || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
contains(){ # name needle haystack
  case "$3" in *"$2"*) log "PASS $1 => 含[$2]";; *) log "FAIL $1 期望包含[$2] 实际[$3]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 期望包含[$2] 实际[$3]";; esac
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
# 上传：$1 token, $2 文件名, $3 本地路径, $4 bizType
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$4"; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ # bearer path idemKey [json]
  local payload="${4-}"
  [ -z "$payload" ] && payload='{}'
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE$2" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$payload"
}
bal(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=$1"; }
txn(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
salesn(){ sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7"; }
auditn(){ sql "SELECT COUNT(*) FROM audit_logs WHERE action='$1' AND team_id=7"; }

OP=$(token operator01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

# ============================================================== G1 INVENTORY_SNAPSHOT
sep; log "G1 INVENTORY_SNAPSHOT：库存快照导入（增量 + INIT_IMPORT 流水）"
INV_CSV="$CSV_DIR/inventory-snapshot.csv"
cat > "$INV_CSV" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date,remark
SKU-A,WH-MAIN,120.000,$D1,期初导入
SKU-B,WH-MAIN,30.000,$D1,期初导入
CSVEOF
INV_WIN="$(cd "$(dirname "$INV_CSV")" && pwd -W)/$(basename "$INV_CSV")"
BAL_A0=$(bal 9002); BAL_B0=$(bal 9003); TXN0=$(txn)
log "G1-前置 SKU-A=$BAL_A0 SKU-B=$BAL_B0 流水=$TXN0"

R=$(upload "$OP" inventory-snapshot.csv "$INV_WIN" INVENTORY_SNAPSHOT)
B1=$(jqn "$(body "$R")" batch_id)
log "G1-upload=$(st "$R") batch=$B1 status=$(jstr "$(body "$R")" status)"
chk "G1-上传201" "201" "$(st "$R")"

R=$(getq "$OP" "/api/v1/import/batches/$B1/preview?limit=20")
log "G1-preview=$(st "$R") status=$(jstr "$(body "$R")" status) rows=$(jqn "$(body "$R")" total_rows)"
chk "G1-预览200" "200" "$(st "$R")"
chk "G1-解析2行" "2" "$(jqn "$(body "$R")" total_rows)"

R=$(putm "$OP" "$B1" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date","remark":"remark"},"parse_options_json":{}}')
log "G1-mapping=$(st "$R") status=$(jstr "$(body "$R")" status)"
chk "G1-映射200" "200" "$(st "$R")"

R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$B1/validate" -H "Authorization: Bearer $OP")
log "G1-validate=$(st "$R") body=$(body "$R")"
chk "G1-校验200" "200" "$(st "$R")"
chk "G1-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
chk "G1-校验2行告警" "2" "$(jqn "$(body "$R")" warning_rows)"

# IMP-4006 必须在错误列表里以 WARNING 出现（errorLevel 过滤）
R=$(getq "$OP" "/api/v1/import/batches/$B1/errors?page=1&pageSize=20&errorLevel=WARNING")
log "G1-告警列表=$(st "$R") body=$(body "$R")"
chk "G1-告警200" "200" "$(st "$R")"
chk "G1-告警2条" "2" "$(jqn "$(body "$R")" total)"
contains "G1-告警码IMP-4006" '"error_code":"IMP-4006"' "$(body "$R")"
contains "G1-告警级别WARNING" '"error_level":"WARNING"' "$(body "$R")"

R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b105-g1-confirm")
log "G1-confirm=$(st "$R") header=[$(hdr Idempotent-Replayed)] body=$(body "$R")"
chk "G1-确认200" "200" "$(st "$R")"
chk "G1-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G1-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G1-首次无重放头" "" "$(hdr Idempotent-Replayed)"

log "G1-余额 SKU-A=$(bal 9002) SKU-B=$(bal 9003) 流水=$(txn)"
chk "G1-余额增量A" "130.000" "$(bal 9002)"
chk "G1-余额增量B" "40.000" "$(bal 9003)"
chk "G1-流水+2" "$((TXN0 + 2))" "$(txn)"
chk "G1-流水类型INIT_IMPORT" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='INIT_IMPORT' AND biz_type='IMPORT' AND biz_ref_id=$B1")"
chk "G1-幂等键字面" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002'")"
chk "G1-qty_before10" "10.000" "$(sql "SELECT qty_before FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002'")"
chk "G1-qty_after130" "130.000" "$(sql "SELECT qty_after FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002'")"

R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b105-g1-confirm")
log "G1-重试=$(st "$R") header=[$(hdr Idempotent-Replayed)]"
chk "G1-重试200" "200" "$(st "$R")"
chk "G1-重试带重放头" "true" "$(hdr Idempotent-Replayed)"
chk "G1-重试余额不变" "130.000" "$(bal 9002)"
chk "G1-重试流水不增" "$((TXN0 + 2))" "$(txn)"

# ============================================================== G2 SALES_DAILY
sep; log "G2 SALES_DAILY：日销售导入（覆盖语义 + IMP-4006 + SALES_DAILY_UPSERT 审计）"
SALES_CSV="$CSV_DIR/sales-daily-b105.csv"
cat > "$SALES_CSV" <<CSVEOF
sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count
SKU-A,WH-MAIN,$D1,12.000,1.000,1080.00,8
SKU-B,WH-MAIN,$D2,5.500,0.000,495.00,4
CSVEOF
SALES_WIN="$(cd "$(dirname "$SALES_CSV")" && pwd -W)/$(basename "$SALES_CSV")"

R=$(upload "$OP" sales-daily-b105.csv "$SALES_WIN" SALES_DAILY)
B2=$(jqn "$(body "$R")" batch_id)
log "G2-upload=$(st "$R") batch=$B2"
chk "G2-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B2/preview?limit=20")
log "G2-preview=$(st "$R") status=$(jstr "$(body "$R")" status) rows=$(jqn "$(body "$R")" total_rows)"
chk "G2-预览200" "200" "$(st "$R")"
chk "G2-解析2行" "2" "$(jqn "$(body "$R")" total_rows)"
R=$(putm "$OP" "$B2" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty","return_qty":"return_qty","sales_amount":"sales_amount","order_count":"order_count"},"parse_options_json":{}}')
chk "G2-映射200" "200" "$(st "$R")"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$B2/validate" -H "Authorization: Bearer $OP")
log "G2-validate=$(st "$R") $(jstr "$(body "$R")" status) err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G2-校验200" "200" "$(st "$R")"
chk "G2-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
chk "G2-校验0告警" "0" "$(jqn "$(body "$R")" warning_rows)"

R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b105-g2-confirm-1")
log "G2-confirm=$(st "$R") body=$(body "$R")"
chk "G2-确认200" "200" "$(st "$R")"
chk "G2-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G2-落库2行" "2" "$(salesn)"
chk "G2-source=IMPORT" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND source='IMPORT'")"
chk "G2-batch_id回指" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND batch_id=$B2")"
chk "G2-product_id派生" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND product_id=9001")"
chk "G2-qty原值" "12.000" "$(sql "SELECT qty FROM sales_daily WHERE team_id=7 AND sku_id=9002 AND sale_date='$D1'")"
chk "G2-审计1条" "1" "$(auditn SALES_DAILY_UPSERT)"

# ---- 重复导入同一业务键：内容改了 qty（否则撞 IMP-4004 文件哈希）----
SALES_CSV2="$CSV_DIR/sales-daily-b105-overwrite.csv"
cat > "$SALES_CSV2" <<CSVEOF
sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count
SKU-A,WH-MAIN,$D1,20.500,1.000,1800.00,9
SKU-B,WH-MAIN,$D2,5.500,0.000,495.00,4
CSVEOF
SALES_WIN2="$(cd "$(dirname "$SALES_CSV2")" && pwd -W)/$(basename "$SALES_CSV2")"
R=$(upload "$OP" sales-daily-b105-overwrite.csv "$SALES_WIN2" SALES_DAILY)
B3=$(jqn "$(body "$R")" batch_id)
log "G2-覆盖upload=$(st "$R") batch=$B3"
chk "G2-覆盖上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B3/preview?limit=20")
chk "G2-覆盖预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B3" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty","return_qty":"return_qty","sales_amount":"sales_amount","order_count":"order_count"},"parse_options_json":{}}')
chk "G2-覆盖映射200" "200" "$(st "$R")"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$B3/validate" -H "Authorization: Bearer $OP")
log "G2-覆盖validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G2-覆盖校验200" "200" "$(st "$R")"
chk "G2-覆盖2行告警" "2" "$(jqn "$(body "$R")" warning_rows)"
R=$(getq "$OP" "/api/v1/import/batches/$B3/errors?page=1&pageSize=20&errorLevel=WARNING")
log "G2-覆盖告警列表=$(st "$R") body=$(body "$R")"
chk "G2-告警2条" "2" "$(jqn "$(body "$R")" total)"
contains "G2-告警码IMP-4006" '"error_code":"IMP-4006"' "$(body "$R")"
contains "G2-覆盖文案" '确认导入后将覆盖' "$(body "$R")"

R=$(postn "$OP" "/api/v1/import/batches/$B3/confirm" "b105-g2-confirm-2")
log "G2-覆盖confirm=$(st "$R") body=$(body "$R")"
chk "G2-覆盖确认200" "200" "$(st "$R")"
chk "G2-覆盖后仍2行" "2" "$(salesn)"
chk "G2-覆盖后qty" "20.500" "$(sql "SELECT qty FROM sales_daily WHERE team_id=7 AND sku_id=9002 AND sale_date='$D1'")"
chk "G2-覆盖后batch_id回指" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND batch_id=$B3")"
chk "G2-审计共2条" "2" "$(auditn SALES_DAILY_UPSERT)"
# 覆盖批次两行都命中库内已有行 ⇒ 审计应记「覆盖 2 行 / 共 2 行」
contains "G2-审计含被覆盖行数" '覆盖 2 行 / 共 2 行' "$(sql "SELECT change_summary FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND team_id=7 ORDER BY id DESC LIMIT 1")"

# ============================================================== G3 未知 sku_code → PARTIAL_SUCCESS
sep; log "G3 未知 sku_code（校验后漂移）→ 200 PARTIAL_SUCCESS + 行级 CAT-3001"
SALES_CSV3="$CSV_DIR/sales-daily-b105-drift.csv"
cat > "$SALES_CSV3" <<CSVEOF
sku_code,warehouse_code,sale_date,qty
SKU-A,WH-MAIN,$D3,3.000
SKU-B,WH-MAIN,$D4,4.000
CSVEOF
SALES_WIN3="$(cd "$(dirname "$SALES_CSV3")" && pwd -W)/$(basename "$SALES_CSV3")"
R=$(upload "$OP" sales-daily-b105-drift.csv "$SALES_WIN3" SALES_DAILY)
B4=$(jqn "$(body "$R")" batch_id)
chk "G3-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B4/preview?limit=20")
chk "G3-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B4" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}')
chk "G3-映射200" "200" "$(st "$R")"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$B4/validate" -H "Authorization: Bearer $OP")
log "G3-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows)"
chk "G3-校验200" "200" "$(st "$R")"
chk "G3-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
# 校验通过后再改库：引用漂移
sql "UPDATE skus SET sku_code='SKU-B-DRIFTED' WHERE team_id=7 AND id=9003" >/dev/null
SALES0=$(salesn)
R=$(postn "$OP" "/api/v1/import/batches/$B4/confirm" "b105-g3-drift")
log "G3-confirm=$(st "$R") body=$(body "$R")"
chk "G3-确认200" "200" "$(st "$R")"
chk "G3-终态PARTIAL" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G3-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G3-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
chk "G3-不变式" "$(jqn "$(body "$R")" total_rows)" \
  "$(( $(jqn "$(body "$R")" success_rows) + $(jqn "$(body "$R")" failed_rows) ))"
chk "G3-错误码CAT-3001" "CAT-3001" "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$B4 AND error_level='ERROR'")"
chk "G3-失败行号2" "2" "$(sql "SELECT row_no FROM import_row_errors WHERE batch_id=$B4 AND error_level='ERROR'")"
chk "G3-只落成功行" "$((SALES0 + 1))" "$(salesn)"

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
log "B105_CHECK_TOTAL=$TOTAL"
log "B105_FAIL_COUNT=$FAIL"
[ "$FAIL" = "0" ] && log "B105_RESULT=ALL PASS" || log "B105_RESULT=HAS FAILURES$FAILED_LIST"
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(txn) sales=$(salesn) batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
