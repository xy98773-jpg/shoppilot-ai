#!/usr/bin/env bash
# =============================================================================
# B10.7（R83：INVENTORY_SNAPSHOT 文件内重复未校验 + R84：缺 /reimport 端点）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3342 / 18200 —— 刻意错开已用端口 3319、3327–3341、18099、18107–18121。
#   ★ 任务书【3】指定的 3350 被宿主进程 WorkBuddy.exe 占用（netstat 实测），
#     故 MySQL 侧下调到 3342（仍在「未分配」区间），应用侧沿用 18200。已登记偏差。
#
# 组次（对应任务书【1】【2】的 a–h 与【3】门槛 2/3/4）：
#   G1  R83-a 同仓同 SKU 同数量两行 → 后行 ERROR COM-0001、error_rows=1、confirm 422 IMP-4009
#   G2  R83-b 同仓同 SKU 不同数量两行 → 同样是后行 ERROR（不是留到 confirm 报 INV-5003）
#   G3  R83-c 同 SKU 跨两仓 → 不误杀，COMPLETED（R80 能力不回退；幂等键后缀 9004/9006）
#   G4  R83-d 回归：SALES_DAILY 的 SAL-6004、主数据 SKU 的 CAT-3002 行为一字不变
#   G5  R84-e 导入成功 → reimport → 201 UPLOADED、原批被取代 + 槽位释放、审计 1 条、字段对齐
#   G6  R84-f 未终结批次（UPLOADED / VALIDATED）reimport → 422 IMP-4005
#   G7  R84-g 不存在 / 跨团队 → 404 IMP-4007；已被取代 → 422 IMP-4005
#   G8  R84-h reimport 后同文件 upload → 409 IMP-4004 且指向新批；新批释放槽位后 → 201
#   G9  边界：R79 PARTIAL_SUCCESS 不回退（SALES_DAILY 写服务内部失败 → SAL-6003）
#   G10 对账：TEST_STRATEGY §7.3 余额可由流水推导（0 行）
#            + Σ流水恒等式 qty_after = qty_before + qty_delta（0 行）
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b10.7/run-b107-evidence.sh
#
# 产物：
#   backend-java/tools/b10.7/evidence-b107.txt          ← 断言日志（入库）
#   backend-java/tools/b10.7/evidence-b107-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b10.7/evidence-b107-headers.log  ← 最近一次响应头（*.log 已 gitignore）
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
LOG="$OUT_DIR/evidence-b107.txt"
APP_LOG="$OUT_DIR/evidence-b107-app.log"
HDR_FILE="$OUT_DIR/evidence-b107-headers.log"
MYSQL_CT="b107-mysql"
MYSQL_PORT="${B107_MYSQL_PORT:-3342}"
APP_PORT="${B107_APP_PORT:-18200}"
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
# 过去日期（SAL-6003 只拦「晚于今天 / 早于上架日」）
D1=2026-10-10
D2=2026-10-11

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT app=$APP_PORT（任务书指定 3350 被宿主 WorkBuddy.exe 占用，MySQL 下调至 3342）"

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
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimezone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b107-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队 7 仓库 9004=WH-MAIN，SKU-A=9002 / SKU-B=9003 / SKU-C=9005 各 10.000；团队 8 owner8)" \
  || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
chkne(){ # name unexpected actual —— 断言「不等于」，用于排除被替换成通用错误码
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
# 上传：$1 token, $3 本地绝对路径, $4 bizType（$2 仅为可读性，实际文件名由 -F 的 @路径 决定）
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
reimport(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/reimport" \
  -H "Authorization: Bearer $1"; }
balw(){ sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=$1 AND sku_id=$2"; }
txn(){ sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7"; }
salesn(){ sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7"; }
# ★ 只取 ERROR 级：同一批次里成功行可能有 IMP-4006（WARNING），按 row_no 取首条会误取 WARNING
errOf(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',error_level) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
msgOf(){ sql "SELECT error_message FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }

OP=$(token operator01)
O8=$(token owner8)
log "TOKEN op=${OP:0:8} owner8=${O8:0:8}"
[ -n "$OP" ] && [ -n "$O8" ] || { log "FATAL login failed"; exit 6; }

# ============================================================== 环境补齐
# 第二个仓库（跨仓场景）：id 9006 / code WH-B
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE')"
log "SETUP WH-B(id=9006) 已插入；team7 仓库=$(sql "SELECT GROUP_CONCAT(code ORDER BY id) FROM warehouses WHERE team_id=7")"

# ★ 种子自洽性修补（G10 对账的前置）：seed-b95.sql 给 SKU-B(9003) 建了 10.000 的余额行，
#   却没有对应的 INIT_IMPORT 流水 ⇒ 「余额可由流水推导」在种子阶段就不成立。
#   这不是本批引入的缺陷（见 README 偏差），但不修补就无法做对账断言，
#   故在脚本内补一条种子流水；seed 文件本身不改（禁改共享资产）。
RECON_SEED=$(sql "SELECT COUNT(*) FROM (SELECT b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty,
  COALESCE(SUM(t.qty_delta),0) AS derived_qty FROM inventory_balances b
  LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
  GROUP BY b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x")
log "G10-种子对账缺口行数=$RECON_SEED（seed-b95.sql 的 SKU-B 余额无对应流水，非本批引入）"
sql "INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta,
     qty_before, qty_after, biz_type, idempotency_key, reason, operator_id, occurred_at, created_by)
     VALUES (7,9004,9003,'INIT_IMPORT',10.000,0.000,10.000,'IMPORT','seed-init-9003','种子导入（b10.7 脚本补齐）',1001,NOW(3),1001)"
log "G10-种子已补齐：$(sql 'SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7') 条流水"

# ============================================================== G1 R83-a
sep; log "G1 R83-a：同仓同 SKU【同数量】两行 → 后行 ERROR COM-0001，confirm 422 IMP-4009"
CSV1="$OUT_DIR/snap-dup-same-qty.csv"
cat > "$CSV1" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-B,WH-MAIN,2.000,$D1
SKU-B,WH-MAIN,2.000,$D1
CSVEOF
BAL_B0=$(balw 9004 9003); TXN0=$(txn)
log "G1-前置 SKU-B@WH-MAIN=$BAL_B0 流水=$TXN0"

R=$(upload "$OP" x "$(win "$CSV1")" INVENTORY_SNAPSHOT)
B1=$(jqn "$(body "$R")" batch_id)
chk "G1-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B1/preview?limit=20")
chk "G1-预览200" "200" "$(st "$R")"
chk "G1-解析2行" "2" "$(jqn "$(body "$R")" total_rows)"
R=$(putm "$OP" "$B1" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}')
chk "G1-映射200" "200" "$(st "$R")"

R=$(validate "$OP" "$B1")
VB1=$(body "$R")
log "G1-validate=$(st "$R") err=$(jqn "$VB1" error_rows) warn=$(jqn "$VB1" warning_rows)"
log "G1-errors=$(echo "$VB1" | grep -o '"errors":\[[^]]*\]' | head -1)"
chk "G1-校验200" "200" "$(st "$R")"
chk "G1-error_rows=1" "1" "$(jqn "$VB1" error_rows)"
E1=$(errOf "$B1")
log "G1-行级错误=$E1"
chk "G1-失败行号2（后行）" "2" "$(echo "$E1" | cut -d'|' -f1)"
chk "G1-错误码COM-0001" "COM-0001" "$(echo "$E1" | cut -d'|' -f2)"
chk "G1-错误级别ERROR" "ERROR" "$(echo "$E1" | cut -d'|' -f3)"
contains "G1-文案逐字" "同一仓库、同一 SKU 在文件内重复" "$(msgOf "$B1")"
# R83 要求 5：WARNING 与 ERROR 允许并存（第 1 行命中已有余额 → IMP-4006）
chk "G1-WARNING与ERROR并存" "1" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND error_level='WARNING' AND error_code='IMP-4006' AND row_no=2")"

R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b107-g1-dup-same")
log "G1-confirm=$(st "$R") body=$(body "$R")"
chk "G1-confirm422前置门" "422" "$(st "$R")"
chk "G1-code=IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"
chk "G1-余额不变" "$BAL_B0" "$(balw 9004 9003)"
chk "G1-流水不变" "$TXN0" "$(txn)"

# ============================================================== G2 R83-b
sep; log "G2 R83-b：同仓同 SKU【不同数量】两行 → 同样后行 ERROR（不是留到 confirm 报 INV-5003）"
CSV2="$OUT_DIR/snap-dup-diff-qty.csv"
cat > "$CSV2" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-B,WH-MAIN,2.000,$D1
SKU-B,WH-MAIN,7.000,$D1
CSVEOF
R=$(upload "$OP" x "$(win "$CSV2")" INVENTORY_SNAPSHOT)
B2=$(jqn "$(body "$R")" batch_id)
chk "G2-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B2/preview?limit=20")
chk "G2-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B2" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}')
chk "G2-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B2")
VB2=$(body "$R")
log "G2-validate=$(st "$R") err=$(jqn "$VB2" error_rows) warn=$(jqn "$VB2" warning_rows)"
chk "G2-error_rows=1" "1" "$(jqn "$VB2" error_rows)"
E2=$(errOf "$B2")
log "G2-行级错误=$E2"
chk "G2-失败行号2" "2" "$(echo "$E2" | cut -d'|' -f1)"
chk "G2-错误码COM-0001" "COM-0001" "$(echo "$E2" | cut -d'|' -f2)"
chkne "G2-不是INV-5003" "INV-5003" "$(echo "$E2" | cut -d'|' -f2)"
contains "G2-文案逐字" "同一仓库、同一 SKU 在文件内重复" "$(msgOf "$B2")"

R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b107-g2-dup-diff")
log "G2-confirm=$(st "$R") body=$(body "$R")"
chk "G2-confirm422" "422" "$(st "$R")"
chk "G2-code=IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"
chkne "G2-不是PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G2-余额不变" "$BAL_B0" "$(balw 9004 9003)"
chk "G2-本批0条流水" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$B2")"

# ============================================================== G3 R83-c（R80 边界）
sep; log "G3 R83-c：同 SKU 跨两仓 → 不误杀，COMPLETED（R80 能力不回退）"
CSV3="$OUT_DIR/snap-cross-wh.csv"
cat > "$CSV3" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-C,WH-MAIN,3.000,$D1
SKU-C,WH-B,4.000,$D1
CSVEOF
C_MAIN0=$(balw 9004 9005); TXN3_0=$(txn)
log "G3-前置 SKU-C@WH-MAIN=$C_MAIN0 SKU-C@WH-B=[$(balw 9006 9005)] 流水=$TXN3_0"
R=$(upload "$OP" x "$(win "$CSV3")" INVENTORY_SNAPSHOT)
B3=$(jqn "$(body "$R")" batch_id)
chk "G3-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B3/preview?limit=20")
chk "G3-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B3" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}')
chk "G3-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B3")
VB3=$(body "$R")
log "G3-validate=$(st "$R") err=$(jqn "$VB3" error_rows) warn=$(jqn "$VB3" warning_rows)"
chk "G3-校验0错误（不误杀）" "0" "$(jqn "$VB3" error_rows)"
R=$(postn "$OP" "/api/v1/import/batches/$B3/confirm" "b107-g3-cross")
log "G3-confirm=$(st "$R") body=$(body "$R")"
chk "G3-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G3-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G3-主仓余额10+3" "13.000" "$(balw 9004 9005)"
chk "G3-副仓余额0+4" "4.000" "$(balw 9006 9005)"
chk "G3-本批2条INIT_IMPORT" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='INIT_IMPORT' AND biz_ref_id=$B3")"
chk "G3-幂等键主仓" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B3:9005:9004'")"
chk "G3-幂等键副仓" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$B3:9005:9006'")"

# ============================================================== G4 R83-d（回归）
sep; log "G4 R83-d 回归：SALES_DAILY 的 SAL-6004 与主数据 SKU 的 CAT-3002 行为一字不变"
CSV4="$OUT_DIR/sales-dup.csv"
cat > "$CSV4" <<CSVEOF
sku_code,warehouse_code,sale_date,qty
SKU-A,WH-MAIN,$D2,5.000
SKU-A,WH-MAIN,$D2,6.000
CSVEOF
R=$(upload "$OP" x "$(win "$CSV4")" SALES_DAILY)
B4=$(jqn "$(body "$R")" batch_id)
chk "G4-销售上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B4/preview?limit=20")
chk "G4-销售预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B4" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}')
chk "G4-销售映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B4")
VB4=$(body "$R")
log "G4-销售validate=$(st "$R") err=$(jqn "$VB4" error_rows)"
chk "G4-销售error_rows=1" "1" "$(jqn "$VB4" error_rows)"
E4=$(errOf "$B4")
log "G4-销售行级错误=$E4"
chk "G4-销售码SAL-6004" "SAL-6004" "$(echo "$E4" | cut -d'|' -f2)"
chk "G4-销售失败行号2" "2" "$(echo "$E4" | cut -d'|' -f1)"

CSV5="$OUT_DIR/sku-dup.csv"
cat > "$CSV5" <<CSVEOF
sku_code,product_code
SKU-B107-X,P-BAG-001
SKU-B107-X,P-BAG-001
CSVEOF
R=$(upload "$OP" x "$(win "$CSV5")" SKU)
B5=$(jqn "$(body "$R")" batch_id)
chk "G4-SKU上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B5/preview?limit=20")
chk "G4-SKU预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B5" '{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}')
chk "G4-SKU映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B5")
VB5=$(body "$R")
log "G4-SKUvalidate=$(st "$R") err=$(jqn "$VB5" error_rows)"
chk "G4-SKUerror_rows=1" "1" "$(jqn "$VB5" error_rows)"
E5=$(errOf "$B5")
log "G4-SKU行级错误=$E5"
chk "G4-SKU码CAT-3002" "CAT-3002" "$(echo "$E5" | cut -d'|' -f2)"
chk "G4-SKU失败行号2" "2" "$(echo "$E5" | cut -d'|' -f1)"

# ============================================================== G5 R84-e
sep; log "G5 R84-e：导入成功 → reimport → 201 UPLOADED、原批被取代 + 槽位释放、审计 1 条"
CSV6="$OUT_DIR/sku-master.csv"
cat > "$CSV6" <<CSVEOF
sku_code,product_code
SKU-B107-1,P-BAG-001
SKU-B107-2,P-BAG-001
CSVEOF
R=$(upload "$OP" x "$(win "$CSV6")" SKU)
B6=$(jqn "$(body "$R")" batch_id)
chk "G5-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B6/preview?limit=20")
chk "G5-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B6" '{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}')
chk "G5-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B6")
chk "G5-校验200" "200" "$(st "$R")"
chk "G5-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
R=$(postn "$OP" "/api/v1/import/batches/$B6/confirm" "b107-g5-confirm")
chk "G5-原批COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
ORIG_HASH=$(sql "SELECT file_hash FROM import_batches WHERE id=$B6")
ORIG_PATH=$(sql "SELECT storage_path FROM import_batches WHERE id=$B6")
log "G5-原批 id=$B6 hash=${ORIG_HASH:0:12}… attempt_no=$(sql "SELECT attempt_no FROM import_batches WHERE id=$B6")"

R=$(reimport "$OP" "$B6")
RB=$(body "$R")
log "G5-reimport=$(st "$R") body=$RB"
chk "G5-reimport201" "201" "$(st "$R")"
NEW=$(jqn "$RB" batch_id)
chk "G5-新批UPLOADED" "UPLOADED" "$(jstr "$RB" status)"
chk "G5-新批attempt_no=原+1" "2" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$NEW")"
chk "G5-新批占槽位" "$ORIG_HASH" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$NEW")"
chk "G5-新批duplicate_of为NULL" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE id=$NEW AND duplicate_of_batch_id IS NOT NULL")"
chk "G5-复制file_hash" "$ORIG_HASH" "$(sql "SELECT file_hash FROM import_batches WHERE id=$NEW")"
chk "G5-复制storage_path" "$ORIG_PATH" "$(sql "SELECT storage_path FROM import_batches WHERE id=$NEW")"
chk "G5-复制file_name" "sku-master.csv" "$(sql "SELECT file_name FROM import_batches WHERE id=$NEW")"
chk "G5-复制biz_type" "SKU" "$(sql "SELECT biz_type FROM import_batches WHERE id=$NEW")"
chk "G5-复制template_version" "$(sql "SELECT template_version FROM import_batches WHERE id=$B6")" "$(sql "SELECT template_version FROM import_batches WHERE id=$NEW")"
chk "G5-计数列归零" "0" "$(sql "SELECT total_rows FROM import_batches WHERE id=$NEW")"
chk "G5-原批被取代" "$NEW" "$(sql "SELECT superseded_by_batch_id FROM import_batches WHERE id=$B6")"
chk "G5-原批槽位释放" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE id=$B6 AND dedupe_hash IS NOT NULL")"
chk "G5-原批状态不变" "COMPLETED" "$(sql "SELECT status FROM import_batches WHERE id=$B6")"
chk "G5-槽位唯一占用者=新批" "$NEW" "$(sql "SELECT id FROM import_batches WHERE team_id=7 AND dedupe_hash='$ORIG_HASH' AND biz_type='SKU'")"
R=$(getq "$OP" "/api/v1/import/batches/$NEW/preview?limit=5")
chk "G5-新批可预览" "200" "$(st "$R")"

AU=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")
chk "G5-审计恰好1条" "1" "$AU"
chk "G5-审计biz_id=新批" "$NEW" "$(sql "SELECT biz_id FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"
chk "G5-审计biz_type" "IMPORT_BATCH" "$(sql "SELECT biz_type FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"
contains "G5-审计记原批id" "原批次 id=$B6" "$(sql "SELECT change_summary FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"

# ============================================================== G6 R84-f
sep; log "G6 R84-f：未终结批次（UPLOADED / VALIDATED）reimport → 422 IMP-4005"
CSV7="$OUT_DIR/sku-f1.csv"
cat > "$CSV7" <<CSVEOF
sku_code,product_code
SKU-B107-3,P-BAG-001
CSVEOF
R=$(upload "$OP" x "$(win "$CSV7")" SKU)
B7=$(jqn "$(body "$R")" batch_id)
chk "G6-上传201" "201" "$(st "$R")"
R=$(reimport "$OP" "$B7")
log "G6-UPLOADED-reimport=$(st "$R") body=$(body "$R")"
chk "G6-UPLOADED→422" "422" "$(st "$R")"
chk "G6-UPLOADED码" "IMP-4005" "$(jstr "$(body "$R")" code)"
chk "G6-UPLOADED状态未变" "UPLOADED" "$(sql "SELECT status FROM import_batches WHERE id=$B7")"

CSV8="$OUT_DIR/sku-f2.csv"
cat > "$CSV8" <<CSVEOF
sku_code,product_code
SKU-B107-4,P-BAG-001
CSVEOF
R=$(upload "$OP" x "$(win "$CSV8")" SKU)
B8=$(jqn "$(body "$R")" batch_id)
R=$(getq "$OP" "/api/v1/import/batches/$B8/preview?limit=20")
R=$(putm "$OP" "$B8" '{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}')
R=$(validate "$OP" "$B8")
chk "G6-VALIDATED就绪" "VALIDATED" "$(sql "SELECT status FROM import_batches WHERE id=$B8")"
R=$(reimport "$OP" "$B8")
log "G6-VALIDATED-reimport=$(st "$R") body=$(body "$R")"
chk "G6-VALIDATED→422" "422" "$(st "$R")"
chk "G6-VALIDATED码" "IMP-4005" "$(jstr "$(body "$R")" code)"
chk "G6-被拒不建新批" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND superseded_by_batch_id IN ($B7,$B8)")"

# ============================================================== G7 R84-g
sep; log "G7 R84-g：不存在 / 跨团队 → 404 IMP-4007；已被取代 → 422 IMP-4005"
R=$(reimport "$OP" 99999999)
log "G7-不存在=$(st "$R") body=$(body "$R")"
chk "G7-不存在→404" "404" "$(st "$R")"
chk "G7-不存在码IMP-4007" "IMP-4007" "$(jstr "$(body "$R")" code)"
chkne "G7-不用IMP-4004" "IMP-4004" "$(jstr "$(body "$R")" code)"

R=$(upload "$O8" x "$(win "$CSV7")" SKU)
B9=$(jqn "$(body "$R")" batch_id)
log "G7-跨团队批次 id=$B9 team=$(sql "SELECT team_id FROM import_batches WHERE id=$B9")"
R=$(reimport "$OP" "$B9")
log "G7-跨团队=$(st "$R") body=$(body "$R")"
chk "G7-跨团队→404" "404" "$(st "$R")"
chk "G7-跨团队码IMP-4007" "IMP-4007" "$(jstr "$(body "$R")" code)"
chk "G7-跨团队未被改" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE id=$B9 AND superseded_by_batch_id IS NOT NULL")"

R=$(reimport "$OP" "$B6")
log "G7-已取代=$(st "$R") body=$(body "$R")"
chk "G7-已取代→422" "422" "$(st "$R")"
chk "G7-已取代码IMP-4005" "IMP-4005" "$(jstr "$(body "$R")" code)"
chk "G7-链条不分叉" "2" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND biz_type='SKU' AND file_hash='$ORIG_HASH'")"
chk "G7-审计仍1条" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"

# ============================================================== G8 R84-h
sep; log "G8 R84-h：reimport 后同文件 upload 的槽位语义"
R=$(upload "$OP" x "$(win "$CSV6")" SKU)
log "G8-同文件upload=$(st "$R") body=$(body "$R")"
chk "G8-upload→409" "409" "$(st "$R")"
chk "G8-码IMP-4004" "IMP-4004" "$(jstr "$(body "$R")" code)"
chk "G8-指向新批" "$NEW" "$(jqn "$(body "$R")" existing_batch_id)"
sql "UPDATE import_batches SET status='FAILED', dedupe_hash=NULL WHERE id=$NEW"
log "G8-新批置 FAILED 并释放槽位"
R=$(upload "$OP" x "$(win "$CSV6")" SKU)
log "G8-释放后upload=$(st "$R") body=$(body "$R")"
chk "G8-释放后→201" "201" "$(st "$R")"
B10=$(jqn "$(body "$R")" batch_id)
chk "G8-新批次UPLOADED" "UPLOADED" "$(sql "SELECT status FROM import_batches WHERE id=$B10")"
chk "G8-新批次占槽位" "$ORIG_HASH" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$B10")"

# ============================================================== G9 边界：R79 PARTIAL_SUCCESS 不回退
sep; log "G9 边界：R79 PARTIAL_SUCCESS 不回退（SALES_DAILY 写服务内部失败 → 行级 SAL-6003）"
CSV9="$OUT_DIR/sales-listed-drift.csv"
cat > "$CSV9" <<CSVEOF
sku_code,warehouse_code,sale_date,qty
SKU-A,WH-MAIN,$D1,4.000
SKU-B,WH-MAIN,$D1,6.000
CSVEOF
SALES0=$(salesn)
R=$(upload "$OP" x "$(win "$CSV9")" SALES_DAILY)
B11=$(jqn "$(body "$R")" batch_id)
chk "G9-上传201" "201" "$(st "$R")"
R=$(getq "$OP" "/api/v1/import/batches/$B11/preview?limit=20")
chk "G9-预览200" "200" "$(st "$R")"
R=$(putm "$OP" "$B11" '{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}')
chk "G9-映射200" "200" "$(st "$R")"
R=$(validate "$OP" "$B11")
log "G9-validate=$(st "$R") err=$(jqn "$(body "$R")" error_rows)"
chk "G9-校验0错误" "0" "$(jqn "$(body "$R")" error_rows)"
# 校验阶段不查 listed_date；改到销售日之后 ⇒ confirm 时写服务必抛 SAL-6003
sql "UPDATE skus SET listed_date='2026-10-20' WHERE team_id=7 AND id=9003"
log "G9-漂移 SKU-B listed_date=2026-10-20 > sale_date=$D1"
R=$(postn "$OP" "/api/v1/import/batches/$B11/confirm" "b107-g9-listed")
log "G9-confirm=$(st "$R") body=$(body "$R")"
chk "G9-终态PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G9-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G9-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
E9=$(errOf "$B11")
log "G9-行级错误=$E9"
chk "G9-行级码SAL-6003" "SAL-6003" "$(echo "$E9" | cut -d'|' -f2)"
chkne "G9-不是COM-0002" "COM-0002" "$(echo "$E9" | cut -d'|' -f2)"
chk "G9-只落成功行" "$((SALES0 + 1))" "$(salesn)"
chk "G9-失败行不落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9003")"

# ============================================================== G10 对账
sep; log "G10 对账：TEST_STRATEGY §7.3 余额可由流水推导 + 流水自身恒等式"
RECON=$(sql "SELECT COUNT(*) FROM (SELECT b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty,
  COALESCE(SUM(t.qty_delta),0) AS derived_qty FROM inventory_balances b
  LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
  GROUP BY b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x")
log "G10-对账明细=$(sql "SELECT CONCAT(warehouse_id,'/',sku_id,':',on_hand_qty) FROM inventory_balances WHERE team_id=7 ORDER BY sku_id,warehouse_id" | tr '\n' ' ')"
chk "G10-余额可由流水推导" "0" "$RECON"
IDEM=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta")
chk "G10-qty_after恒等式" "0" "$IDEM"
chk "G10-不变式success+failed=total" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND status IN ('COMPLETED','PARTIAL_SUCCESS') AND success_rows + failed_rows <> total_rows")"

# ============================================================== 汇总
sep
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(txn) sales=$(salesn) skus=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7") batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
log "B107_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B107_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B107_RESULT=ALL PASS"; else log "B107_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
