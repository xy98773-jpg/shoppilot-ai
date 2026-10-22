#!/usr/bin/env bash
# =============================================================================
# B10.8 复核补充探针：N9 —— 主数据导入「编码字面量被静默改写」的因果验证
#
# N1/N2/N2b/N3 已实测：文件写 sku-a / wh-main / p-bag-001 / sup-x，
# confirm 均 COMPLETED、库内行数不变，但【库内 code 字面量被改成文件里的写法】。
#
# 本探针做两件事：
#   1) 用 GET 接口证明这是「对外可见」的（不是只在 DB 层发生）；
#   2) 记录审计日志里的 code，确认审计与库内一致（不产生分叉）；
#   3) 断言「同一批次内 SKU-A + sku-a 两行」在 SKU 导入路径下的行为（R85 已判 CAT-3002）。
# 环境：复用 rb108 的独立库与 fat jar（新起一组端口）。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb-b108b.txt"
APP_LOG="$OUT_DIR/evidence-rb-b108b-app.log"
MYSQL_CT="rb108b-mysql"
MYSQL_PORT="${RB108B_MYSQL_PORT:-3357}"
APP_PORT="${RB108B_APP_PORT:-18230}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
STORE_DIR="/tmp/rb108b-store"

: > "$LOG"; : > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

[ -z "$JAR" ] && { log "FATAL: jar not found"; exit 2; }
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
cleanup(){ docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true; }
trap cleanup EXIT

docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=shoppilot mysql:8.0.36 --default-time-zone=+00:00 >> "$APP_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }
rm -rf "$STORE_DIR" 2>/dev/null || true; mkdir -p "$STORE_DIR"

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
IMPORT_STORAGE_DIR="$STORE_DIR" JWT_SECRET="$JWT_SECRET" \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }; sleep 2
done
log "APP_UP=$UP"; [ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

MYSQL_FLAGS=(--default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot)
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot < "$EVI/seed-b95.sql" >> "$APP_LOG" 2>&1 \
  && log "SEED_OK" || { log "FATAL seed failed"; exit 5; }

FAIL=0; FAILED_LIST=""
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
getqf(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ local p="${4-}"; [ -z "$p" ] && p='{}'
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE$2" -H "Authorization: Bearer $1" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$p"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" -H "Authorization: Bearer $1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
MAPSKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'

OP=$(token operator01); ADM=$(token admin01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

# ============================================================ N9-a 对外可见性
sep; log "N9-a ★★★ 前置：GET /api/v1/skus/9002（库里此刻是 SKU-A）"
R=$(getqf "$OP" "/api/v1/skus/9002")
log "N9a-改写前 HTTP=$(st "$R") body=$(body "$R")"

sep; log "N9-b 导入单行 sku-a（库里是 SKU-A）"
CSV="$OUT_DIR/n9-sku-lower.csv"
printf 'sku_code,product_code\nsku-a,p-bag-001\n' > "$CSV"
R=$(upload "$OP" "$CSV" SKU); B=$(jqn "$(body "$R")" batch_id)
R=$(getqf "$OP" "/api/v1/import/batches/$B/preview?limit=20")
R=$(putm "$OP" "$B" "$MAPSKU")
R=$(validate "$OP" "$B")
log "N9b-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows)"
R=$(postn "$OP" "/api/v1/import/batches/$B/confirm" "rb108-n9")
log "N9b-confirm=$(st "$R") status=$(jstr "$(body "$R")" status)"

sep; log "N9-c 后置：库内与对外接口"
log "N9c-DB=$(sql "SELECT CONCAT(id,'|',sku_code) FROM skus WHERE id=9002")"
R=$(getqf "$OP" "/api/v1/skus/9002")
log "N9c-GET(按id)=$(st "$R") body=$(body "$R")"
chk "N9c-DB码被改写为文件写法" "9002|sku-a" "$(sql "SELECT CONCAT(id,'|',sku_code) FROM skus WHERE id=9002")"
log "N9c-审计biz_no=$(sql "SELECT biz_no FROM audit_logs WHERE team_id=7 AND action='MASTER_DATA_STATUS_CHANGE' AND biz_type='SKU' AND biz_id=9002 ORDER BY id DESC LIMIT 1")"
chk "N9c-审计biz_no=改写后码（无分叉）" "sku-a" "$(sql "SELECT biz_no FROM audit_logs WHERE team_id=7 AND action='MASTER_DATA_STATUS_CHANGE' AND biz_type='SKU' AND biz_id=9002 ORDER BY id DESC LIMIT 1")"

# ============================================================ N9-d 后续导入大写会改回
sep; log "N9-d 后续再导入一次 SKU-A（大写）⇒ 库内码应改回大写（证明「最后写入者赢」）"
printf 'sku_code,product_code\nSKU-A,p-bag-001\n' > "$OUT_DIR/n9-sku-upper.csv"
R=$(upload "$OP" "$OUT_DIR/n9-sku-upper.csv" SKU); B2=$(jqn "$(body "$R")" batch_id)
R=$(getqf "$OP" "/api/v1/import/batches/$B2/preview?limit=20")
R=$(putm "$OP" "$B2" "$MAPSKU")
R=$(validate "$OP" "$B2")
R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "rb108-n9d")
log "N9d-confirm=$(st "$R") status=$(jstr "$(body "$R")" status)"
chk "N9d-库内码改回大写" "9002|SKU-A" "$(sql "SELECT CONCAT(id,'|',sku_code) FROM skus WHERE id=9002")"

# ============================================================ N9-e 文件内重复（R85 已闭环）
sep; log "N9-e 同批 SKU-A + sku-a 两行 ⇒ 应 CAT-3002（R85 已闭环，确认不受影响）"
printf 'sku_code,product_code\nSKU-A,p-bag-001\nsku-a,p-bag-001\n' > "$OUT_DIR/n9-dup.csv"
R=$(upload "$OP" "$OUT_DIR/n9-dup.csv" SKU); B3=$(jqn "$(body "$R")" batch_id)
R=$(getqf "$OP" "/api/v1/import/batches/$B3/preview?limit=20")
R=$(putm "$OP" "$B3" "$MAPSKU")
R=$(validate "$OP" "$B3")
log "N9e-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows)"
chk "N9e-判文件内重复" "1" "$(jqn "$(body "$R")" error_rows)"
chk "N9e-行级码=CAT-3002" "CAT-3002" "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$B3 AND error_level='ERROR' ORDER BY row_no LIMIT 1")"

sep
TOTAL=$(grep -acE "^(PASS|FAIL) " "$LOG")
log "RB108B_CHECK_TOTAL=$TOTAL"; log "RB108B_FAIL_COUNT=$FAIL"
if [ "$FAIL" -eq 0 ]; then log "RB108B_RESULT=ALL PASS"; else log "RB108B_RESULT=FAIL"; log "失败清单:$FAILED_LIST"; fi
exit 0
