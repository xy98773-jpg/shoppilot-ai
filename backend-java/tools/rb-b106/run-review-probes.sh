#!/usr/bin/env bash
# =============================================================================
# B10.6 独立复核探针（评审自建，不复用开发脚本）
#
# 目的：补「SSOT/回修单明文要求，但开发 68 项未覆盖」的场景。开发的 5 组全部是
#       2 行批次，且失败行都落在末行 ⇒ 「中间行失败后，后续行还能否写入」未被验证；
#       审计只测了销售侧；「同键同内容的完全重复行」与「三仓」未测。
#
# 环境：自建 fat jar + 独立 MySQL 8.0.36（:3341）+ 应用 :18121。
#       刻意避开已用端口 3319、3327–3341、18099、18107–18121。
#
# 探针：
#   P1  库存侧 3 行、中间行失败（成功-失败-成功）→ savepoint 回滚后同事务内后续行仍落库
#   P1b 销售侧 3 行、中间行失败 → 同上（销售写服务 NESTED 的对照组）
#   P2  同仓同 SKU 同数量的「完全重复行」→ 实测幂等重放行为（口径登记用）
#   P3  同批同 SKU 跨三仓 → 键后缀维度不是只支持 2 个仓；键长度 ≤ 128
#   P4  PARTIAL_SUCCESS 的审计条数与行级错误明细（副产物残留检查）
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb-b106.txt"
APP_LOG="$OUT_DIR/evidence-rb-b106-app.log"
HDR_FILE="$OUT_DIR/evidence-rb-b106-headers.log"
MYSQL_CT="rb106-mysql"
MYSQL_PORT="${RB106_MYSQL_PORT:-3341}"
APP_PORT="${RB106_APP_PORT:-18121}"
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
D1=2026-10-10

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

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
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
  && log "SEED_OK (seed-b95.sql)" || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
chkne(){
  if [ "$2" != "$3" ]; then log "PASS $1 => $3（≠$2）"; else log "FAIL $1 不得等于[$2]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 不得等于[$2]"; fi
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$4"; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ local payload="${4-}"; [ -z "$payload" ] && payload='{}'
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE$2" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$payload"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
balw(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=$1 AND sku_id=$2"; }
txn(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
salesn(){ sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7"; }
errOf(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',error_level) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
MAPINV='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}'
MAPSALE='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}'
# 一次跑完 upload → preview → mapping → validate，回显 batch_id 与校验计数
stage(){ # $1 token $2 bizType $3 csvpath $4 filename $5 mapping
  local R B
  R=$(upload "$1" "$4" "$(win "$3")" "$2"); B=$(jqn "$(body "$R")" batch_id)
  chk "$4-上传201" "201" "$(st "$R")"
  R=$(getq "$1" "/api/v1/import/batches/$B/preview?limit=20"); chk "$4-预览200" "200" "$(st "$R")"
  R=$(putm "$1" "$B" "$5"); chk "$4-映射200" "200" "$(st "$R")"
  R=$(validate "$1" "$B")
  log "$4-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows) warn=$(jqn "$(body "$R")" warning_rows)"
  chk "$4-校验200" "200" "$(st "$R")"
  STAGE_BATCH="$B"
}

OP=$(token operator01); ADM=$(token admin01)
log "TOKEN op=${OP:0:8} admin=${ADM:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

# 副仓 WH-B(9006) / 三仓 WH-C(9007)
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE'),(9007,7,'WH-C','三仓','ACTIVE')"
log "SETUP 仓库=$(sql "SELECT GROUP_CONCAT(code ORDER BY id) FROM warehouses WHERE team_id=7")"

# =========================================================== P1 三行中间失败（库存）
sep; log "P1 库存侧 3 行「成功-失败-成功」：第 2 行同键异量必 INV-5003，第 3 行须照常落库"
CSV="$OUT_DIR/p1-three-rows-inventory.csv"
cat > "$CSV" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-A,WH-MAIN,5.000,$D1
SKU-A,WH-MAIN,9.000,$D1
SKU-C,WH-MAIN,4.000,$D1
CSVEOF
A0=$(balw 9004 9002); C0=$(balw 9004 9005); T0=$(txn)
log "P1-前置 A=$A0 C=$C0 流水=$T0"
stage "$OP" INVENTORY_SNAPSHOT "$CSV" p1-three-rows.csv "$MAPINV"
B1=$STAGE_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "rb106-p1-mid-fail")
log "P1-confirm=$(st "$R") body=$(body "$R")"
chk "P1-确认200" "200" "$(st "$R")"
chk "P1-终态PARTIAL" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "P1-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "P1-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
chk "P1-不变式" "3" "$(sql "SELECT success_rows+failed_rows FROM import_batches WHERE id=$B1")"
E=$(errOf "$B1"); log "P1-行级错误=$E"
chk "P1-失败行号2" "2" "$(echo "$E" | cut -d'|' -f1)"
chk "P1-行级码INV-5003" "INV-5003" "$(echo "$E" | cut -d'|' -f2)"
chkne "P1-不是COM-0002" "COM-0002" "$(echo "$E" | cut -d'|' -f2)"
log "P1-后置 A=$(balw 9004 9002) C=$(balw 9004 9005) 流水=$(txn)"
chk "P1-第1行已落库 A+5" "15.000" "$(balw 9004 9002)"
chk "P1-第3行已落库 C+4" "14.000" "$(balw 9004 9005)"
chk "P1-只写2条流水" "$((T0 + 2))" "$(txn)"
chk "P1-第3行有流水" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9005:9004'")"
chk "P1-第2行无流水" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B1:9002:9004' AND qty_delta=9.000")"

# =========================================================== P1b 三行中间失败（销售）
sep; log "P1b 销售侧 3 行「成功-失败-成功」：中间行 listed_date 漂移必 SAL-6003，两侧行须落库"
CSV="$OUT_DIR/p1b-three-rows-sales.csv"
cat > "$CSV" <<CSVEOF
sku_code,warehouse_code,sale_date,qty
SKU-A,WH-MAIN,$D1,1.000
SKU-B,WH-MAIN,$D1,2.000
SKU-C,WH-MAIN,$D1,3.000
CSVEOF
S0=$(salesn)
stage "$OP" SALES_DAILY "$CSV" p1b-three-rows.csv "$MAPSALE"
B1B=$STAGE_BATCH
sql "UPDATE skus SET listed_date='2026-10-20' WHERE team_id=7 AND id=9003"
log "P1b-漂移 SKU-B(9003).listed_date=2026-10-20 > $D1"
R=$(postn "$OP" "/api/v1/import/batches/$B1B/confirm" "rb106-p1b-mid-fail")
log "P1b-confirm=$(st "$R") body=$(body "$R")"
chk "P1b-确认200" "200" "$(st "$R")"
chk "P1b-终态PARTIAL" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "P1b-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "P1b-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
E=$(errOf "$B1B"); log "P1b-行级错误=$E"
chk "P1b-失败行号2" "2" "$(echo "$E" | cut -d'|' -f1)"
chk "P1b-行级码SAL-6003" "SAL-6003" "$(echo "$E" | cut -d'|' -f2)"
log "P1b-后置 sales=$(( $(salesn) - S0 )) 行（新增）"
chk "P1b-新增2行" "2" "$(( $(salesn) - S0 ))"
chk "P1b-失败SKU未落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9003")"
chk "P1b-成功SKU-A落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9002")"
chk "P1b-成功SKU-C落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9005")"

# =========================================================== P2 完全重复行（同键同内容）
sep; log "P2 同仓同 SKU 同数量（完全重复行）：实测幂等重放是静默去重还是报错"
CSV="$OUT_DIR/p2-identical-rows.csv"
cat > "$CSV" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-C,WH-MAIN,2.000,$D1
SKU-C,WH-MAIN,2.000,$D1
CSVEOF
C1=$(balw 9004 9005); T1=$(txn)
log "P2-前置 SKU-C@WH-MAIN=$C1 流水=$T1"
stage "$OP" INVENTORY_SNAPSHOT "$CSV" p2-identical-rows.csv "$MAPINV"
B2=$STAGE_BATCH
log "P2-校验期文件内重复是否被拦：err_rows=$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B2 AND error_level='ERROR'")"
R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "rb106-p2-identical")
log "P2-confirm=$(st "$R") body=$(body "$R")"
P2STATUS=$(jstr "$(body "$R")" status); P2SUC=$(jqn "$(body "$R")" success_rows)
log "P2-实测 status=$P2STATUS success_rows=$P2SUC 余额 $(balw 9004 9005)（原 $C1）流水 $(txn)（原 $T1）"
log "P2-键=$(sql "SELECT GROUP_CONCAT(idempotency_key) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$B2")"
log "P2-NOTE 该组为口径观察，不作为 PASS/FAIL 判定"

# =========================================================== P3 跨三仓 + 键长度
sep; log "P3 同批同 SKU 跨三仓：键后缀维度非「只支持两仓」"
CSV="$OUT_DIR/p3-three-warehouses.csv"
cat > "$CSV" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-A,WH-B,2.000,$D1
SKU-A,WH-C,3.000,$D1
SKU-A,WH-MAIN,1.000,$D1
CSVEOF
T2=$(txn)
stage "$OP" INVENTORY_SNAPSHOT "$CSV" p3-three-warehouses.csv "$MAPINV"
B3=$STAGE_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B3/confirm" "rb106-p3-three-wh")
log "P3-confirm=$(st "$R") body=$(body "$R")"
chk "P3-确认200" "200" "$(st "$R")"
chk "P3-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "P3-成功3行" "3" "$(jqn "$(body "$R")" success_rows)"
chk "P3-流水+3" "$((T2 + 3))" "$(txn)"
chk "P3-三个键后缀" "3" "$(sql "SELECT COUNT(DISTINCT idempotency_key) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$B3")"
log "P3-余额 WH-B=$(balw 9006 9002) WH-C=$(balw 9007 9002) WH-MAIN=$(balw 9004 9002)"
chk "P3-WH-B=2.000" "2.000" "$(balw 9006 9002)"
chk "P3-WH-C=3.000" "3.000" "$(balw 9007 9002)"
chk "P3-键长度上限≤128" "128" "$(sql "SELECT CASE WHEN MAX(LENGTH(idempotency_key))<=128 THEN 128 ELSE MAX(LENGTH(idempotency_key)) END FROM inventory_transactions WHERE team_id=7")"
log "P3-实际最长键=$(sql "SELECT MAX(LENGTH(idempotency_key)) FROM inventory_transactions WHERE team_id=7")"

# =========================================================== P4 审计与行级错误明细
sep; log "P4 PARTIAL_SUCCESS 的审计条数（库存侧）与行级错误并存明细"
log "P4-P1批次审计: $(sql "SELECT GROUP_CONCAT(CONCAT(action,'=',c)) FROM (SELECT action, COUNT(*) c FROM audit_logs WHERE biz_id=$B1 GROUP BY action) t")"
chk "P4-P1批次完成审计1条" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_COMPLETE' AND biz_id=$B1")"
chk "P4-P1b销售审计2条" "2" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")"
chk "P4-P1b批次完成审计1条" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_COMPLETE' AND biz_id=$B1B")"
log "P4-P1批次行级错误全量: $(sql "SELECT GROUP_CONCAT(CONCAT(row_no,':',error_code,':',error_level) SEPARATOR ' , ') FROM import_row_errors WHERE batch_id=$B1")"
log "P4-P1批次 WARNING 行数=$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND error_level='WARNING'") ERROR 行数=$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND error_level='ERROR'")"
chk "P4-同row_no可并存W+E" "2" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND row_no=2")"
log "P4-失败行raw_data非空=$(sql "SELECT CASE WHEN raw_data IS NULL THEN 'NULL' ELSE 'OK' END FROM import_row_errors WHERE batch_id=$B1 AND error_level='ERROR' LIMIT 1")"

sep
log "RB106_CHECK_TOTAL=$(( $(grep -c '^PASS\|^FAIL' "$LOG") ))"
log "RB106_FAIL_COUNT=$FAIL"
[ "$FAIL" -eq 0 ] && log "RB106_RESULT=ALL PASS" || { log "RB106_RESULT=FAIL"; log "$FAILED_LIST"; }
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(txn) sales=$(salesn) batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
