#!/usr/bin/env bash
# =============================================================================
# B10.6（R79：数据类行级隔离失效 + R80：INIT_IMPORT 幂等键不含仓库）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3340 / 18120 —— 刻意错开回修单【0】点名的已用端口
#   3319、3327–3339 与 18099、18107–18119。
#
# 组次（对应回修单【5】真栈 5 条断言）：
#   G1  R80 正向：同批同 SKU 跨两仓（SKU-A@WH-MAIN 5.000 + SKU-A@WH-B 7.000）
#       → COMPLETED、success_rows=2、两仓余额各 15.000/7.000、两条 INIT_IMPORT、
#         两条幂等键后缀分别为 9004 / 9006（若键不含仓库则第二行被误判重复提交）。
#   G1R R80 重放：同一批次二次 confirm → 200 + Idempotent-Replayed: true，
#       两仓余额与流水条数均不增。
#   G2  R79 同仓重复行：同批同 SKU 同仓两行（5.000 / 9.000，幂等键相同但数量不同）
#       → PARTIAL_SUCCESS + success=1 failed=1 + 行级 INV-5003（**不是** COM-0002）
#         + 成功行已落库（余额 15.000）。修复前：整批 FAILED + 全行 COM-0002 + 0 行落库。
#   G3  R79 写服务内部失败（销售侧）：校验后把 SKU-C 的 listed_date 改到销售日之后
#       → PARTIAL_SUCCESS + 行级 SAL-6003 + 成功行已落 sales_daily。
#   G4  主数据回归：SKU 主数据导入 → COMPLETED、skus 新增行（NESTED 改动不得带坏主数据路径）。
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b10.6/run-b106-evidence.sh
#
# 产物：
#   backend-java/tools/b10.6/evidence-b106.txt          ← 断言日志（入库）
#   backend-java/tools/b10.6/evidence-b106-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b10.6/evidence-b106-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# ★ curl 必须 --noproxy '*'：本机全局 http 代理（Clash 127.0.0.1:7890）会劫持 localhost。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b106.txt"
APP_LOG="$OUT_DIR/evidence-b106-app.log"
HDR_FILE="$OUT_DIR/evidence-b106-headers.log"
MYSQL_CT="b106-mysql"
MYSQL_PORT="${B106_MYSQL_PORT:-3340}"
APP_PORT="${B106_APP_PORT:-18120}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
# 过去日期（SAL-6003 只拦未来日期）
D1=2026-10-10

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
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b106-app.log)"

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
chkne(){ # name unexpected actual —— 断言「不等于」，用于排除通用错误码
  if [ "$2" != "$3" ]; then log "PASS $1 => $3（≠$2）"; else log "FAIL $1 不得等于[$2]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 不得等于[$2]"; fi
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
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
balw(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=$1 AND sku_id=$2"; }
txn(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
salesn(){ sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7"; }
# ★ 只取 ERROR 级：同一批次里成功行可能有 IMP-4006（WARNING），按 row_no 取首条会误取 WARNING
errOf(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',error_level) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }

OP=$(token operator01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

# 第二个仓库（跨仓场景）：id 9006 / code WH-B
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE')"
log "SETUP WH-B(id=9006) 已插入；仓库清单=$(sql "SELECT GROUP_CONCAT(code ORDER BY id) FROM warehouses WHERE team_id=7")"

# ============================================================== G1 R80 跨仓
sep; log "G1 R80：同批同 SKU 跨两仓（幂等键必须含 warehouse_id）"
CSV1="$OUT_DIR/cross-warehouse.csv"
cat > "$CSV1" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-A,WH-MAIN,5.000,$D1
SKU-A,WH-B,7.000,$D1
CSVEOF
BAL_A_MAIN0=$(balw 9004 9002); BAL_A_B0=$(balw 9006 9002); TXN0=$(txn)
log "G1-前置 SKU-A@WH-MAIN=$BAL_A_MAIN0 SKU-A@WH-B=[${BAL_A_B0:-<无行>}] 流水=$TXN0"

R=$(upload "$OP" cross-warehouse.csv "$(win "$CSV1")" INVENTORY_SNAPSHOT)
B1=$(jqn "$(body "$R")" batch_id)
log "G1-upload=$(st "$R") batch=$B1"
chk "G1-上传201" "201" "$(st "$R")"

R=$(getq "$OP" "/api/v1/import/batches/$B1/preview?limit=20")
chk "G1-预览200" "200" "$(st "$R")"
chk "G1-解析2行" "2" "$(jqn "$(body "$R")" total_rows)"

R=$(putm "$OP" "$B1" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}')
chk "G1-映射200" "200" "$(st "$R")"

R=$(validate "$OP" "$B1")
log "G1-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G1-校验200" "200" "$(st "$R")"
chk "G1-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
# 第 1 行命中已有余额 → IMP-4006；第 2 行（WH-B 无余额）不告警
chk "G1-校验1行告警" "1" "$(jqn "$(body "$R")" warning_rows)"

R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b106-g1-cross-wh")
log "G1-confirm=$(st "$R") body=$(body "$R")"
chk "G1-确认200" "200" "$(st "$R")"
chk "G1-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G1-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G1-失败0行" "0" "$(jqn "$(body "$R")" failed_rows)"

log "G1-余额 SKU-A@WH-MAIN=$(balw 9004 9002) SKU-A@WH-B=$(balw 9006 9002) 流水=$(txn)"
chk "G1-主仓余额10+5" "15.000" "$(balw 9004 9002)"
chk "G1-副仓余额0+7" "7.000" "$(balw 9006 9002)"
chk "G1-流水+2" "$((TXN0 + 2))" "$(txn)"
chk "G1-本批INIT_IMPORT两条" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='INIT_IMPORT' AND biz_type='IMPORT' AND biz_ref_id=$B1")"
chk "G1-幂等键含主仓" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002:9004'")"
chk "G1-幂等键含副仓" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002:9006'")"
chk "G1-旧格式键不存在" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002'")"
chk "G1-主仓流水qty_delta" "5.000" "$(sql "SELECT qty_delta FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002:9004'")"
chk "G1-副仓流水qty_delta" "7.000" "$(sql "SELECT qty_delta FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002:9006'")"

# ============================================================== G1R 跨仓重放
sep; log "G1R R80：跨仓批次二次 confirm → 纯重放"
TXN_BEFORE=$(txn); MAIN_BEFORE=$(balw 9004 9002); B_BEFORE=$(balw 9006 9002)
R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b106-g1-cross-wh")
log "G1R-重试=$(st "$R") header=[$(hdr Idempotent-Replayed)] body=$(body "$R")"
chk "G1R-重试200" "200" "$(st "$R")"
chk "G1R-带重放头" "true" "$(hdr Idempotent-Replayed)"
chk "G1R-流水不增" "$TXN_BEFORE" "$(txn)"
chk "G1R-主仓余额不变" "$MAIN_BEFORE" "$(balw 9004 9002)"
chk "G1R-副仓余额不变" "$B_BEFORE" "$(balw 9006 9002)"

# ============================================================== G2 R79 同仓重复行
sep; log "G2 R79：同批同 SKU 同仓两行（键相同、数量不同）→ 行级 INV-5003，不是 COM-0002"
CSV2="$OUT_DIR/same-warehouse-dup.csv"
cat > "$CSV2" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-B,WH-MAIN,5.000,$D1
SKU-B,WH-MAIN,9.000,$D1
CSVEOF
BAL_B0=$(balw 9004 9003); TXN2_0=$(txn)
log "G2-前置 SKU-B@WH-MAIN=$BAL_B0 流水=$TXN2_0"

R=$(upload "$OP" same-warehouse-dup.csv "$(win "$CSV2")" INVENTORY_SNAPSHOT)
B2=$(jqn "$(body "$R")" batch_id)
chk "G2-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B2/preview?limit=20")
chk "G2-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B2" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}')
chk "G2-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B2")
log "G2-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G2-校验200" "200" "$(st "$R")"
chk "G2-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"

R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b106-g2-same-wh")
log "G2-confirm=$(st "$R") body=$(body "$R")"
chk "G2-确认200" "200" "$(st "$R")"
chk "G2-终态PARTIAL" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G2-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G2-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
chk "G2-不变式" "2" "$(( $(jqn "$(body "$R")" success_rows) + $(jqn "$(body "$R")" failed_rows) ))"

G2_ERR=$(errOf "$B2")
log "G2-行级错误=$G2_ERR"
chk "G2-失败行号2" "2" "$(echo "$G2_ERR" | cut -d'|' -f1)"
chk "G2-行级码INV-5003" "INV-5003" "$(echo "$G2_ERR" | cut -d'|' -f2)"
chkne "G2-不是COM-0002" "COM-0002" "$(echo "$G2_ERR" | cut -d'|' -f2)"
chk "G2-错误级别ERROR" "ERROR" "$(echo "$G2_ERR" | cut -d'|' -f3)"
# 同批两行同 SKU 同仓 ⇒ 校验阶段两行都命中已有余额 ⇒ 2 条 IMP-4006 WARNING；
# confirm 阶段第 2 行再追加 1 条 INV-5003 ERROR（同一 row_no 下 WARNING 与 ERROR 并存，
# 属既有跨阶段口径：校验阶段无法预知写服务会拒绝，本批未改动）
chk "G2-WARNING行并存" "2" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B2 AND error_level='WARNING' AND error_code='IMP-4006'")"
chk "G2-ERROR行仅1条" "1" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B2 AND error_level='ERROR'")"

log "G2-余额 SKU-B@WH-MAIN=$(balw 9004 9003) 流水=$(txn)"
chk "G2-成功行已落库" "15.000" "$(balw 9004 9003)"
chk "G2-只写1条流水" "$((TXN2_0 + 1))" "$(txn)"
chk "G2-本批流水1条" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$B2")"

# ============================================================== G3 R79 销售写服务内部失败
sep; log "G3 R79：SALES_DAILY 写服务内部失败（校验后改 listed_date）→ 行级 SAL-6003"
CSV3="$OUT_DIR/sales-listed-drift.csv"
cat > "$CSV3" <<CSVEOF
sku_code,warehouse_code,sale_date,qty
SKU-A,WH-MAIN,$D1,4.000
SKU-C,WH-MAIN,$D1,6.000
CSVEOF
SALES0=$(salesn)

R=$(upload "$OP" sales-listed-drift.csv "$(win "$CSV3")" SALES_DAILY)
B3=$(jqn "$(body "$R")" batch_id)
chk "G3-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B3/preview?limit=20")
chk "G3-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B3" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}')
chk "G3-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B3")
log "G3-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G3-校验200" "200" "$(st "$R")"
chk "G3-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"

# 校验阶段不查 listed_date；把它改到销售日之后 → confirm 时写服务必抛 SAL-6003
sql "UPDATE skus SET listed_date='2026-10-20' WHERE team_id=7 AND id=9005"
log "G3-漂移 listed_date=2026-10-20 > sale_date=$D1"

R=$(postn "$OP" "/api/v1/import/batches/$B3/confirm" "b106-g3-listed")
log "G3-confirm=$(st "$R") body=$(body "$R")"
chk "G3-确认200" "200" "$(st "$R")"
chk "G3-终态PARTIAL" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G3-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G3-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"

G3_ERR=$(errOf "$B3")
log "G3-行级错误=$G3_ERR"
chk "G3-失败行号2" "2" "$(echo "$G3_ERR" | cut -d'|' -f1)"
chk "G3-行级码SAL-6003" "SAL-6003" "$(echo "$G3_ERR" | cut -d'|' -f2)"
chkne "G3-不是COM-0002" "COM-0002" "$(echo "$G3_ERR" | cut -d'|' -f2)"

chk "G3-只落成功行" "$((SALES0 + 1))" "$(salesn)"
chk "G3-落库SKU=9002" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9002 AND sale_date='$D1'")"
chk "G3-失败行不落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9005")"

# ============================================================== G4 主数据回归
sep; log "G4 主数据回归：SKU 导入 → COMPLETED（NESTED 改动不得带坏主数据路径）"
CSV4="$OUT_DIR/sku-master.csv"
cat > "$CSV4" <<CSVEOF
sku_code,product_code
SKU-B106-1,P-BAG-001
SKU-B106-2,P-BAG-001
CSVEOF

R=$(upload "$OP" sku-master.csv "$(win "$CSV4")" SKU)
B4=$(jqn "$(body "$R")" batch_id)
chk "G4-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B4/preview?limit=20")
chk "G4-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B4" '{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}')
chk "G4-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B4")
log "G4-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
chk "G4-校验200" "200" "$(st "$R")"
chk "G4-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"

R=$(postn "$OP" "/api/v1/import/batches/$B4/confirm" "b106-g4-sku")
log "G4-confirm=$(st "$R") body=$(body "$R")"
chk "G4-确认200" "200" "$(st "$R")"
chk "G4-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G4-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G4-skUs新增2行" "2" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND sku_code LIKE 'SKU-B106-%'")"

# ============================================================== 汇总
sep
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(txn) sales=$(salesn) skus=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7") batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
log "B106_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B106_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B106_RESULT=ALL PASS"; else log "B106_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
