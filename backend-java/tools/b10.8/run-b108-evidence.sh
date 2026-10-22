#!/usr/bin/env bash
# =============================================================================
# B10.8（R85：编码大小写一律不敏感等价 + seed-b95.sql 对账缺口）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3355 / 18210 —— 任务书指定，且避开已用端口 3319、3327–3342、18099、18107–18200。
#   ★ 3350 被宿主 WorkBuddy.exe 占用（任务书亦已禁），故用 3355。
#
# 组次（对应任务书【4】门槛 2 的 a–d、门槛 3、门槛 4）：
#   G1  R85-a 快照同仓 SKU-A + sku-a → 后行 ERROR COM-0001、error_rows=1、confirm 422 IMP-4009
#   G2  R85-b 销售同 (WH-MAIN, SKU-A, 同日期) 但大小写不同 → 后行 ERROR SAL-6004、confirm 422
#   G3  R85-c SKU 主数据 SKU-NEW-1 + sku-new-1 → 后行 ERROR CAT-3002（证明第三处也改了）
#   G4  R85-d 回归：同仓同 SKU 同大小写两行仍判重复（R83 不回退）
#   G5  R85-e 引用解析：单行全小写编码能成功导入（不误报 CAT-3001）+ 全小写也能命中 IMP-4006
#   G6  边界 1：R80 跨仓（同 SKU 两仓，大小写不同写法）不误杀 → COMPLETED
#   G7  边界 2：R79 PARTIAL_SUCCESS 仍可达（SALES_DAILY 写服务内部失败 → SAL-6003，非 COM-0002）
#   G8  门槛 3：seed-b95.sql 后【直接】执行 TEST_STRATEGY §7.3 对账 SQL → 0 行（不额外补流水）
#             + qty_after = qty_before + qty_delta 违例 0 行
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b10.8/run-b108-evidence.sh
#
# 产物：
#   backend-java/tools/b10.8/evidence-b108.txt          ← 断言日志（入库）
#   backend-java/tools/b10.8/evidence-b108-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b10.8/evidence-b108-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4：否则中文断言字面量退化成 '?'，产生假 FAIL。
# ★ curl 必须 --noproxy '*'：本机全局 http 代理（Clash 127.0.0.1:7890）会劫持 localhost。
# ★ 显式设置 IMPORT_STORAGE_DIR：避免上传文件落进仓库工作区。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b108.txt"
APP_LOG="$OUT_DIR/evidence-b108-app.log"
HDR_FILE="$OUT_DIR/evidence-b108-headers.log"
MYSQL_CT="b108-mysql"
MYSQL_PORT="${B108_MYSQL_PORT:-3355}"
APP_PORT="${B108_APP_PORT:-18210}"
IMPORT_DIR="${B108_IMPORT_DIR:-$OUT_DIR/.import-storage}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
D1=2026-10-10   # 过去日期（SAL-6003 只拦「晚于今天 / 早于上架日」）
D2=2026-10-11

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT app=$APP_PORT（任务书指定 3355 / 18210，均在未分配区间）"
log "IMPORT_STORAGE_DIR=$IMPORT_DIR（显式设置，避免落进仓库工作区）"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  rm -rf "$IMPORT_DIR" 2>/dev/null || true
}
trap cleanup EXIT
mkdir -p "$IMPORT_DIR"

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
IMPORT_STORAGE_DIR="$IMPORT_DIR" \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b108-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队 7 仓库 9004=WH-MAIN；SKU 9002=SKU-A / 9003=SKU-B / 9005=SKU-C 各 10.000；团队 8 owner8)" \
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
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
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
msgOf(){ sql "SELECT error_message FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
# 走完整链路 upload→preview→mapping→validate，返回 batch_id 与 validate 响应（用全局 VB/EC/WN/VS）
# ★ VS = validate 的 HTTP 状态码。必须在这里取：validate 会把批次推进到 VALIDATED，
#   再调一次会被状态守卫拦成 422 IMP-4005（这是正确行为，不是缺陷）。
VB=""; EC=""; WN=""; VS=""
run_chain(){ # token csvFile bizType [mappingJson]
  local mappingJson="${4-}"
  [ -z "$mappingJson" ] && mappingJson='{}'
  local R=$(upload "$1" x "$(win "$2")" "$3")
  local B=$(jqn "$(body "$R")" batch_id)
  getq "$1" "/api/v1/import/batches/$B/preview?limit=50" >/dev/null
  putm "$1" "$B" "$mappingJson" >/dev/null
  R=$(validate "$1" "$B")
  VS=$(st "$R"); VB=$(body "$R"); EC=$(jqn "$VB" error_rows); WN=$(jqn "$VB" warning_rows)
  LAST_BATCH=$B
}

OP=$(token operator01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

MAP_SNAP='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty","snapshot_date":"snapshot_date"},"parse_options_json":{}}'
MAP_SALES='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty","return_qty":"return_qty","sales_amount":"sales_amount","order_count":"order_count"},"parse_options_json":{}}'
MAP_SKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'

# ============================================================== G1 R85-a
sep; log "G1 R85-a：快照同仓 SKU-A + sku-a → 后行 ERROR COM-0001，confirm 422 IMP-4009"
CSV1="$OUT_DIR/snap-case.csv"
cat > "$CSV1" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-A,WH-MAIN,2.000,$D1
sku-a,WH-MAIN,3.000,$D1
CSVEOF
BAL_A0=$(balw 9004 9002); TXN0=$(txn)
log "G1-前置 SKU-A@WH-MAIN=$BAL_A0 流水=$TXN0"

run_chain "$OP" "$CSV1" INVENTORY_SNAPSHOT "$MAP_SNAP"
B1=$LAST_BATCH
log "G1-validate err=$EC warn=$WN errors=$(echo "$VB" | grep -o '"errors":\[[^]]*\]' | head -1)"
chk "G1-校验200" "200" "$VS"
chk "G1-error_rows=1" "1" "$EC"
# 两行都命中同一条已有余额行（库内不可能并存 SKU-A/sku-a，两行解析到同一行）⇒ 2 条 WARNING。
# warning_rows 口径 = 「有 WARNING 且无 ERROR 的行」：第 1 行符合，第 2 行已有 ERROR ⇒ 计 1。
chk "G1-warning_rows=1（第1行有WARNING无ERROR）" "1" "$WN"
chk "G1-两条IMP-4006并存" "2" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND error_level='WARNING' AND error_code='IMP-4006'")"
E1=$(errOf "$B1"); log "G1-行级错误=$E1"
chk "G1-失败行号2（后行）" "2" "$(echo "$E1" | cut -d'|' -f1)"
chk "G1-错误码COM-0001" "COM-0001" "$(echo "$E1" | cut -d'|' -f2)"
chk "G1-错误级别ERROR" "ERROR" "$(echo "$E1" | cut -d'|' -f3)"
contains "G1-文案逐字" "同一仓库、同一 SKU 在文件内重复" "$(msgOf "$B1")"

R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b108-g1-case")
chk "G1-confirm422前置门" "422" "$(st "$R")"
chk "G1-code=IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"
chk "G1-余额不变" "$BAL_A0" "$(balw 9004 9002)"
chk "G1-流水不变" "$TXN0" "$(txn)"

# ============================================================== G2 R85-b
sep; log "G2 R85-b：销售同 (WH-MAIN, SKU-A, 同日期) 但大小写不同 → 后行 ERROR SAL-6004"
CSV2="$OUT_DIR/sales-case.csv"
cat > "$CSV2" <<CSVEOF
sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count
SKU-A,WH-MAIN,$D1,12.000,0.000,1080.00,8
sku-a,wh-main,$D1,6.000,0.000,540.00,4
CSVEOF
SALES0=$(salesn)
run_chain "$OP" "$CSV2" SALES_DAILY "$MAP_SALES"
B2=$LAST_BATCH
log "G2-validate err=$EC warn=$WN"
chk "G2-校验200" "200" "$VS"
chk "G2-error_rows=1" "1" "$EC"
E2=$(errOf "$B2"); log "G2-行级错误=$E2"
chk "G2-失败行号2" "2" "$(echo "$E2" | cut -d'|' -f1)"
chk "G2-错误码SAL-6004" "SAL-6004" "$(echo "$E2" | cut -d'|' -f2)"
chkne "G2-不是COM-0001" "COM-0001" "$(echo "$E2" | cut -d'|' -f2)"
contains "G2-文案逐字" "同一仓库、同一 SKU、同一销售日期出现重复记录" "$(msgOf "$B2")"

R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b108-g2-case")
chk "G2-confirm422前置门" "422" "$(st "$R")"
chk "G2-code=IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"
chk "G2-一行都不落库" "$SALES0" "$(salesn)"

# ============================================================== G3 R85-c
sep; log "G3 R85-c：SKU 主数据 SKU-NEW-1 + sku-new-1 → 后行 ERROR CAT-3002（第三处）"
CSV3="$OUT_DIR/sku-case.csv"
cat > "$CSV3" <<CSVEOF
sku_code,product_code
SKU-NEW-1,P-BAG-001
sku-new-1,P-BAG-001
CSVEOF
SKUS0=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
run_chain "$OP" "$CSV3" SKU "$MAP_SKU"
B3=$LAST_BATCH
log "G3-validate err=$EC warn=$WN"
chk "G3-校验200" "200" "$VS"
chk "G3-error_rows=1" "1" "$EC"
E3=$(errOf "$B3"); log "G3-行级错误=$E3"
chk "G3-失败行号2" "2" "$(echo "$E3" | cut -d'|' -f1)"
chk "G3-错误码CAT-3002" "CAT-3002" "$(echo "$E3" | cut -d'|' -f2)"
chkne "G3-不是COM-0001" "COM-0001" "$(echo "$E3" | cut -d'|' -f2)"
chk "G3-SKU未新增" "$SKUS0" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"

# ============================================================== G4 R85-d 回归
sep; log "G4 R85-d 回归：同仓同 SKU【同大小写】两行仍判重复（R83 不回退）"
CSV4="$OUT_DIR/snap-exact.csv"
cat > "$CSV4" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-B,WH-MAIN,2.000,$D1
SKU-B,WH-MAIN,7.000,$D1
CSVEOF
run_chain "$OP" "$CSV4" INVENTORY_SNAPSHOT "$MAP_SNAP"
B4=$LAST_BATCH
chk "G4-校验200" "200" "$VS"
chk "G4-error_rows=1" "1" "$EC"
E4=$(errOf "$B4")
chk "G4-错误码COM-0001" "COM-0001" "$(echo "$E4" | cut -d'|' -f2)"
chk "G4-错误级别ERROR" "ERROR" "$(echo "$E4" | cut -d'|' -f3)"

# ============================================================== G5 R85-e/f 引用解析
sep; log "G5 R85-e/f：单行【全小写】编码能成功导入（不误报 CAT-3001）+ 全小写也能命中 IMP-4006"
CSV5="$OUT_DIR/snap-lower.csv"
cat > "$CSV5" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
sku-b,wh-main,1.000,$D1
CSVEOF
BAL_B0=$(balw 9004 9003); TXNB=$(txn)
run_chain "$OP" "$CSV5" INVENTORY_SNAPSHOT "$MAP_SNAP"
B5=$LAST_BATCH
log "G5-validate err=$EC warn=$WN（R85 前此处 warn=0，因小写编码查不到引用而被跳过）"
chk "G5-校验200" "200" "$VS"
chk "G5-error_rows=0" "0" "$EC"
chk "G5-warning_rows=1（命中已有余额行）" "1" "$WN"
chk "G5-命中告警码IMP-4006" "IMP-4006" "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$B5 AND error_level='WARNING' LIMIT 1")"

R=$(postn "$OP" "/api/v1/import/batches/$B5/confirm" "b108-g5-lower")
chk "G5-confirm200" "200" "$(st "$R")"
chk "G5-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G5-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G5-本批无行级错误" "0" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B5 AND error_level='ERROR'")"
chk "G5-余额+1.000" "11.000" "$(balw 9004 9003)"
chk "G5-流水+1" "$((TXNB + 1))" "$(txn)"
# 幂等键用【id】(INIT_IMPORT:IMPORT:<batch>:<skuId>:<whId>)，不含编码字面量。
# 本项验证的是：小写编码 sku-b/wh-main 被正确解析成 9003/9004（而非「查不到」→ CAT-3001）
chk "G5-解析到正确的SKU/仓库id(9003/9004)" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=9003 AND warehouse_id=9004 AND idempotency_key LIKE '%:9003:9004'")"

# ============================================================== G6 边界：R80 跨仓不误杀
sep; log "G6 边界1：R80 跨仓不误杀（同一 SKU 两仓，且仓库编码大小写不同写法）"
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE')"
CSV6="$OUT_DIR/snap-cross-wh.csv"
cat > "$CSV6" <<CSVEOF
sku_code,warehouse_code,qty,snapshot_date
SKU-C,WH-MAIN,3.000,$D1
sku-c,wh-b,4.000,$D1
CSVEOF
run_chain "$OP" "$CSV6" INVENTORY_SNAPSHOT "$MAP_SNAP"
B6=$LAST_BATCH
log "G6-validate err=$EC warn=$WN"
chk "G6-校验200" "200" "$VS"
chk "G6-不误杀 error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B6/confirm" "b108-g6-cross")
chk "G6-confirm200" "200" "$(st "$R")"
chk "G6-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G6-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G6-主仓余额10+3" "13.000" "$(balw 9004 9005)"
chk "G6-副仓余额0+4" "4.000" "$(balw 9006 9005)"
chk "G6-幂等键后缀=主仓9004" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=9005 AND warehouse_id=9004 AND idempotency_key LIKE '%:9004'")"
chk "G6-幂等键后缀=副仓9006" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=9005 AND warehouse_id=9006 AND idempotency_key LIKE '%:9006'")"

# ============================================================== G7 边界：R79 PARTIAL_SUCCESS
sep; log "G7 边界2：R79 PARTIAL_SUCCESS 仍可达（SALES_DAILY 写服务内部失败 → SAL-6003）"
CSV7="$OUT_DIR/sales-partial.csv"
# ★ 两行必须是【真正不同】的业务键：R85 修好后 `SKU-B` 与 `sku-b` 折叠为同一个键，
#   若两行只差大小写，会在校验阶段就被判 SAL-6004 重复（这正是 R85 的正确行为）。
#   这里让两行落在【不同 SKU】上，其中一行随后因 listed_date 漂移被写服务拒绝（SAL-6003）。
cat > "$CSV7" <<CSVEOF
sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count
SKU-A,WH-MAIN,$D2,4.000,0.000,360.00,3
SKU-B,WH-MAIN,$D2,9.000,0.000,810.00,7
CSVEOF
run_chain "$OP" "$CSV7" SALES_DAILY "$MAP_SALES"
B7=$LAST_BATCH
log "G7-validate err=$EC warn=$WN"
chk "G7-校验200" "200" "$VS"
chk "G7-校验0错误（两行键不同）" "0" "$EC"
# 制造写服务内部失败：把 SKU-B 的上架日改到 sale_date 之后 ⇒ SAL-6003
sql "UPDATE skus SET listed_date='2026-10-20' WHERE id=9003 AND team_id=7"
log "G7-漂移 listed_date=$(sql "SELECT listed_date FROM skus WHERE id=9003") > sale_date=$D2"
SALES7=$(salesn)
R=$(postn "$OP" "/api/v1/import/batches/$B7/confirm" "b108-g7-partial")
log "G7-confirm=$(st "$R") body=$(body "$R")"
chk "G7-confirm200" "200" "$(st "$R")"
chk "G7-终态PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G7-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G7-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
E7=$(errOf "$B7"); log "G7-行级错误=$E7"
chk "G7-行级码SAL-6003" "SAL-6003" "$(echo "$E7" | cut -d'|' -f2)"
chkne "G7-不是COM-0002" "COM-0002" "$(echo "$E7" | cut -d'|' -f2)"
chk "G7-成功行已落库" "$((SALES7 + 1))" "$(salesn)"

# ============================================================== G8 门槛 3：对账
sep; log "G8 门槛3：seed-b95.sql 后【直接】跑 TEST_STRATEGY §7.3 对账 SQL（不额外补流水）"
log "G8-种子流水=$(sql "SELECT GROUP_CONCAT(CONCAT(sku_id,'/',idempotency_key) ORDER BY sku_id) FROM inventory_transactions WHERE team_id=7")"
RECON=$(sql "SELECT COUNT(*) FROM (SELECT b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty,
  COALESCE(SUM(t.qty_delta),0) AS derived_qty FROM inventory_balances b
  LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
  GROUP BY b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x")
log "G8-对账明细=$(sql "SELECT CONCAT(warehouse_id,'/',sku_id,':',on_hand_qty) FROM inventory_balances WHERE team_id=7 ORDER BY sku_id,warehouse_id" | tr '\n' ' ')"
chk "G8-余额可由流水推导（§7.3）" "0" "$RECON"
chk "G8-qty_after恒等式" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta")"
chk "G8-不变式success+failed=total" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND status IN ('COMPLETED','PARTIAL_SUCCESS') AND success_rows + failed_rows <> total_rows")"
chk "G8-种子未出现无流水余额行" "0" "$(sql "SELECT COUNT(*) FROM inventory_balances b WHERE b.team_id=7 AND NOT EXISTS (SELECT 1 FROM inventory_transactions t WHERE t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id)")"

# ============================================================== 汇总
sep
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(txn) sales=$(salesn) skus=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7") batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
log "B108_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B108_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B108_RESULT=ALL PASS"; else log "B108_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7