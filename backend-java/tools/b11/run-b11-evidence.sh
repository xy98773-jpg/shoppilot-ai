#!/usr/bin/env bash
# =============================================================================
# B11（M3-T19 硬门槛）真栈取证脚本 —— 导入 / 重导 / 部分失败 / 对账三时点
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3361 / 18250 —— 任务书指定。已用：3319、3327–3342、3355–3357、3360、18099、
#   18107–18210、18220、18230、18240；★ 3350 被宿主 WorkBuddy.exe 占用（禁用）。
#   启动前仍需 netstat -ano 复核（本脚本不自行改端口，冲突就停）。
#
# 组次（对应任务书【2】A1/A2/B1/B2/C1/D1 与【3】≥12 断言、A/B/C 各 ≥3）：
#   A1  链式重导 A→B→C：attempt_no 2/3、槽位逐级转移、FORCE_REIMPORT 恰 2 条（同 hash 作用域）
#   A2  主数据重导：SKU 行数与 id 不变、余额绝对值 10.000、对账 0
#   B1  SALES_DAILY 部分失败：PARTIAL_SUCCESS + 行级 SAL-6003（≠COM-0002）+ 审计恰 1 条
#   B2  文件内重复（大小写变体）+ 失败行叠加：重复批 422 零落库；漂移批成功恰 1 条、余额恰 +1 行量
#   C1  对账三时点：seed 后 / 导入后 / 重导后，每次打印差异行数
#   D1  反向误杀：全合法文件（同 SKU 跨两仓）必须 COMPLETED
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b11/run-b11-evidence.sh
#   可覆盖： B11_MYSQL_PORT=xxxx B11_APP_PORT=xxxxx
#
# 产物：
#   backend-java/tools/b11/evidence-b11.txt          ← 断言日志（入库）
#   backend-java/tools/b11/evidence-b11-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b11/evidence-b11-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4。
# ★ curl 必须 --noproxy '*'：本机全局 http 代理会劫持 localhost。
# ★ 显式设置 IMPORT_STORAGE_DIR：避免上传文件落进仓库工作区。
# ★ field_mapping_json 只映射该 CSV 真实存在的列（否则 mapping 422、批次停在 PARSED，后续连锁全挂）。
# ★ 大小写断言必须 BINARY 比较（ai_ci 下 'SKU-A' 会匹配 'sku-a'）。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b11.txt"
APP_LOG="$OUT_DIR/evidence-b11-app.log"
HDR_FILE="$OUT_DIR/evidence-b11-headers.log"
MYSQL_CT="b11-mysql"
MYSQL_PORT="${B11_MYSQL_PORT:-3361}"
APP_PORT="${B11_APP_PORT:-18250}"
IMPORT_DIR="${B11_IMPORT_DIR:-$OUT_DIR/.import-storage}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
D0=2026-10-09   # 过去日期（B1 的销售日：必须【早于】漂移后的 listed_date）
D1=2026-10-10   # 过去日期（SAL-6003 只拦「晚于今天 / 早于上架日」）
DFUT=2999-01-01 # 明确非法：晚于今天

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT app=$APP_PORT（任务书指定 3361 / 18250）"
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b11-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队7 仓库9004=WH-MAIN；SKU 9002/9003/9005 各10.000 且各有等额 INIT_IMPORT 流水)" \
  || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
chkne(){ # name unexpected actual
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
reimport(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/reimport" \
  -H "Authorization: Bearer $1"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
errOf(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',error_level) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
recon(){ sql "SELECT COUNT(*) FROM (SELECT b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty,
  COALESCE(SUM(t.qty_delta),0) AS derived_qty FROM inventory_balances b
  LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
  GROUP BY b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x"; }
balances(){ sql "SELECT GROUP_CONCAT(CONCAT(warehouse_id,'/',sku_id,'=',on_hand_qty) ORDER BY sku_id) FROM inventory_balances WHERE team_id=7"; }

# 走完整链路 upload→preview→mapping→validate，返回 batch_id 与 validate 响应（全局 VB/EC/WN/VS/LAST_BATCH）
VB=""; EC=""; WN=""; VS=""; LAST_BATCH=""
run_chain(){ # token csvFile bizType mappingJson
  local R=$(upload "$1" x "$(win "$2")" "$3")
  local B=$(jqn "$(body "$R")" batch_id)
  getq "$1" "/api/v1/import/batches/$B/preview?limit=50" >/dev/null
  putm "$1" "$B" "$4" >/dev/null
  R=$(validate "$1" "$B")
  VS=$(st "$R"); VB=$(body "$R"); EC=$(jqn "$VB" error_rows); WN=$(jqn "$VB" warning_rows)
  LAST_BATCH=$B
}
# 把已存在批次（例如重导出来的 UPLOADED）推进到终态：preview→mapping→validate→confirm
finish_batch(){ # token batchId mappingJson idemKey
  getq "$1" "/api/v1/import/batches/$2/preview?limit=50" >/dev/null
  putm "$1" "$2" "$3" >/dev/null
  validate "$1" "$2" >/dev/null
  local R=$(postn "$1" "/api/v1/import/batches/$2/confirm" "$4")
  echo "$(st "$R")|$(jstr "$(body "$R")" status)"
}

OP=$(token operator01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

MAP_SKU_MIN='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'
MAP_SKU_NAME='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code","name":"name"},"parse_options_json":{}}'
MAP_SALES='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}'
MAP_SNAP='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","qty":"qty"},"parse_options_json":{}}'

# ============================================================== A1 链式重导
sep; log "A1 链式重导 A→B→C（attempt_no 2/3、槽位逐级转移、审计恰 2 条）"
CSV_A1="$OUT_DIR/sku-chain.csv"
printf 'sku_code,product_code\nSKU-B11-A1,P-BAG-001\n' > "$CSV_A1"
HASH_A1=$(sha256sum "$CSV_A1" | cut -c1-64)
run_chain "$OP" "$CSV_A1" SKU "$MAP_SKU_MIN"
A1A=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$A1A/confirm" "b11-a1-a")
log "A1-A confirm=$(st "$R") body=$(body "$R")"
chk "A1-A-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "A1-A-attempt_no=1" "1" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$A1A")"
SP_A=$(sql "SELECT storage_path FROM import_batches WHERE id=$A1A")

R=$(reimport "$OP" "$A1A")
log "A1-reimport A->B=$(st "$R") body=$(body "$R")"
A1B=$(jqn "$(body "$R")" batch_id)
chk "A1-reimport-A->B 201" "201" "$(st "$R")"
chk "A1-B-attempt_no=2" "2" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$A1B")"
chk "A1-B占用槽位(dedupe_hash=file_hash)" "$HASH_A1" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$A1B")"
chk "A1-A槽位释放(dedupe_hash IS NULL)" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$A1A")"
chk "A1-A.superseded_by=B" "$A1B" "$(sql "SELECT superseded_by_batch_id FROM import_batches WHERE id=$A1A")"
chk "A1-B复用同一份原文" "$SP_A" "$(sql "SELECT storage_path FROM import_batches WHERE id=$A1B")"

F=$(finish_batch "$OP" "$A1B" "$MAP_SKU_MIN" "b11-a1-b")
log "A1-B finish=$F"
chk "A1-B-终态COMPLETED" "200|COMPLETED" "$F"

R=$(reimport "$OP" "$A1B")
log "A1-reimport B->C=$(st "$R") body=$(body "$R")"
A1C=$(jqn "$(body "$R")" batch_id)
chk "A1-reimport-B->C 201" "201" "$(st "$R")"
chk "A1-C-attempt_no=3" "3" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$A1C")"
chk "A1-C占用槽位" "$HASH_A1" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$A1C")"
chk "A1-C复用同一份原文" "$SP_A" "$(sql "SELECT storage_path FROM import_batches WHERE id=$A1C")"
chk "A1-槽位唯一(同hash仅1行占用)" "1" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND dedupe_hash='$HASH_A1' AND biz_type='SKU'")"
chk "A1-FORCE_REIMPORT审计恰2条(同hash作用域)" "2" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7 AND biz_id IN (SELECT id FROM import_batches WHERE team_id=7 AND file_hash='$HASH_A1')")"
chk "A1-审计biz_id集合={B,C}" "$A1B,$A1C" \
  "$(sql "SELECT GROUP_CONCAT(biz_id ORDER BY biz_id) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7 AND biz_id IN (SELECT id FROM import_batches WHERE team_id=7 AND file_hash='$HASH_A1')" | tr -d '\n')"
contains "A1-B审计change_summary记原批A" "原批次 id=$A1A" \
  "$(sql "SELECT change_summary FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND biz_id=$A1B")"
chk "A1-链式重导不重复建SKU" "1" \
  "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND BINARY sku_code='SKU-B11-A1'")"

# ============================================================== A2 主数据重导
sep; log "A2 主数据重导：SKU 行数/id 不变、余额绝对值 10.000、对账 0"
SKUS_BEFORE=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
CSV_A2="$OUT_DIR/sku-master.csv"
printf 'sku_code,product_code,name\nSKU-A,P-BAG-001,重导后的名字\n' > "$CSV_A2"
run_chain "$OP" "$CSV_A2" SKU "$MAP_SKU_NAME"
A2A=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$A2A/confirm" "b11-a2-a")
chk "A2-首次导入COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
R=$(reimport "$OP" "$A2A")
A2B=$(jqn "$(body "$R")" batch_id)
chk "A2-reimport 201" "201" "$(st "$R")"
F=$(finish_batch "$OP" "$A2B" "$MAP_SKU_NAME" "b11-a2-b")
log "A2-重导批 finish=$F"
chk "A2-重导批COMPLETED" "200|COMPLETED" "$F"
chk "A2-SKU行数不变" "$SKUS_BEFORE" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "A2-SKU-A身份不变(id=9002)" "9002" "$(sql "SELECT id FROM skus WHERE team_id=7 AND BINARY sku_code='SKU-A'")"
chk "A2-编码字面量未被改写(BINARY)" "1" \
  "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND BINARY sku_code='SKU-A'")"
chk "A2-可变字段name已更新" "重导后的名字" "$(sql "SELECT name FROM skus WHERE id=9002")"
chk "A2-余额绝对值仍10.000(非翻倍/非清零)" "10.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "A2-主数据导入不写库存流水" "3" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"
chk "A2-对账0行" "0" "$(recon)"

# ============================================================== B1 部分失败
sep; log "B1 SALES_DAILY 部分失败：PARTIAL_SUCCESS + 行级 SAL-6003 + 审计恰 1 条"
CSV_B1="$OUT_DIR/sales-partial.csv"
# ★ 销售日必须【早于】漂移后的 listed_date（$D1），否则 listed_date == sale_date 不构成 SAL-6003
#   （首轮 8 项 FAIL 的根因：我把两者写成同一天，属夹具错，不是实现错）
printf 'sku_code,warehouse_code,sale_date,qty\nSKU-A,WH-MAIN,%s,4.000\nSKU-B,WH-MAIN,%s,5.000\n' "$D0" "$D0" > "$CSV_B1"
run_chain "$OP" "$CSV_B1" SALES_DAILY "$MAP_SALES"
B1=$LAST_BATCH
chk "B1-校验0错误" "0" "$EC"
sql "UPDATE skus SET listed_date='$D1' WHERE team_id=7 AND id=9003" >/dev/null
log "B1-漂移 listed_date=$(sql "SELECT listed_date FROM skus WHERE id=9003") > sale_date=$D0"
R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b11-b1")
log "B1-confirm=$(st "$R") body=$(body "$R")"
chk "B1-confirm200" "200" "$(st "$R")"
chk "B1-终态PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "B1-success_rows=1" "1" "$(jqn "$(body "$R")" success_rows)"
chk "B1-failed_rows=1" "1" "$(jqn "$(body "$R")" failed_rows)"
chk "B1-success+failed=total(2)" "2" "$(( $(jqn "$(body "$R")" success_rows) + $(jqn "$(body "$R")" failed_rows) ))"
E=$(errOf "$B1")
log "B1-行级错误原始行=$E"
chk "B1-行级码SAL-6003" "SAL-6003" "$(echo "$E" | cut -d'|' -f2)"
chkne "B1-不是COM-0002" "COM-0002" "$(echo "$E" | cut -d'|' -f2)"
chk "B1-成功行已落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
chk "B1-失败行未落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9003")"
# ★ SALES_DAILY_UPSERT 是【批次级】一条（change_summary 记「覆盖 N 行 / 共 M 行」），
#   故「失败行是否留下审计残留」只能靠 M 的取值判定，不能靠条数（条数在失败/成功两种情况下都是 1）。
chk "B1-批次级审计恰1条" "1" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND team_id=7")"
contains "B1-审计摘要为共1行" "共 1 行" "$(sql "SELECT change_summary FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND team_id=7")"
chk "B1-审计摘要不得含共2行" "0" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND team_id=7 AND change_summary LIKE '%共 2 行%'")"
chk "B1-批次完成审计恰1条(限定biz_id)" "1" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_COMPLETE' AND biz_id=$B1")"

# ============================================================== B2 重复 + 失败叠加
sep; log "B2 文件内重复（大小写变体）+ 非法日期叠加：重复批 422 零落库零审计"
CSV_B2="$OUT_DIR/sales-dup.csv"
printf 'sku_code,warehouse_code,sale_date,qty\nSKU-A,WH-MAIN,%s,5.000\nsku-a,WH-MAIN,%s,7.000\nSKU-B,WH-MAIN,%s,3.000\n' "$D1" "$D1" "$DFUT" > "$CSV_B2"
run_chain "$OP" "$CSV_B2" SALES_DAILY "$MAP_SALES"
B2=$LAST_BATCH
chk "B2-校验error_rows=2" "2" "$EC"
chk "B2-第2行判重复SAL-6004" "SAL-6004" \
  "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$B2 AND error_level='ERROR' AND row_no=2")"
chk "B2-第3行非法日期SAL-6003" "SAL-6003" \
  "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$B2 AND error_level='ERROR' AND row_no=3")"
R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b11-b2-dup")
log "B2-dup-confirm=$(st "$R") body=$(body "$R")"
chk "B2-重复批confirm 422" "422" "$(st "$R")"
chk "B2-错误码IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"
chk "B2-零落库(sales_daily仍1)" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
chk "B2-零审计残留" "1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND team_id=7")"

sep; log "B2 漂移失败行叠加：成功恰 1 条、余额恰 +1 行量、对账 0"
sql "INSERT INTO skus (id, team_id, product_id, sku_code, name, status) VALUES (9007,7,9001,'SKU-D','Sku D','ACTIVE')" >/dev/null
CSV_B2S="$OUT_DIR/snap-drift.csv"
printf 'sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,5.000\nSKU-D,WH-MAIN,6.000\n' > "$CSV_B2S"
run_chain "$OP" "$CSV_B2S" INVENTORY_SNAPSHOT "$MAP_SNAP"
B2S=$LAST_BATCH
chk "B2s-校验0错误" "0" "$EC"
sql "DELETE FROM skus WHERE team_id=7 AND id=9007" >/dev/null
R=$(postn "$OP" "/api/v1/import/batches/$B2S/confirm" "b11-b2-snap")
log "B2s-confirm=$(st "$R") body=$(body "$R")"
chk "B2s-终态PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "B2s-成功恰1条" "1" "$(jqn "$(body "$R")" success_rows)"
chk "B2s-失败1条" "1" "$(jqn "$(body "$R")" failed_rows)"
E=$(errOf "$B2S")
log "B2s-行级错误原始行=$E"
chk "B2s-行级码CAT-3001" "CAT-3001" "$(echo "$E" | cut -d'|' -f2)"
chk "B2s-余额恰+1行量(15.000)" "15.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "B2s-失败行未建余额行" "0" "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND sku_id=9007")"
chk "B2s-对账0行" "0" "$(recon)"

# ============================================================== C1 对账三时点
sep; log "C1 对账三时点（seed / 导入后 / 重导后）—— 每点打印差异行数"
# ★ 前面各组已经改过库存，故本组用「当前基线」作为时点1，再连续两点
C1_0=$(recon)
log "C1-时点1 差异行数=$C1_0 余额=$(balances)"
chk "C1-时点1对账0" "0" "$C1_0"
CSV_C1="$OUT_DIR/snap-c1.csv"
printf 'sku_code,warehouse_code,qty\nSKU-B,WH-MAIN,5.000\n' > "$CSV_C1"
BAL_C1_BEFORE=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")
run_chain "$OP" "$CSV_C1" INVENTORY_SNAPSHOT "$MAP_SNAP"
C1B=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$C1B/confirm" "b11-c1-import")
chk "C1-导入批COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
C1_1=$(recon)
log "C1-时点2 差异行数=$C1_1 余额=$(balances)（SKU-B 由 $BAL_C1_BEFORE 起）"
chk "C1-时点2对账0" "0" "$C1_1"
R=$(reimport "$OP" "$C1B")
C1C=$(jqn "$(body "$R")" batch_id)
chk "C1-reimport 201" "201" "$(st "$R")"
F=$(finish_batch "$OP" "$C1C" "$MAP_SNAP" "b11-c1-reimport")
log "C1-重导批 finish=$F"
chk "C1-重导批COMPLETED" "200|COMPLETED" "$F"
C1_2=$(recon)
log "C1-时点3 差异行数=$C1_2 余额=$(balances)"
chk "C1-时点3对账0" "0" "$C1_2"
chk "C1-qty_after恒等式" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta")"
chk "C1-不变式success+failed=total" "0" \
  "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND status IN ('COMPLETED','PARTIAL_SUCCESS') AND success_rows + failed_rows <> total_rows")"

# ============================================================== D1 反向误杀
sep; log "D1 反向误杀：全合法文件（同 SKU 跨两仓）必须 COMPLETED"
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9005,7,'WH-B','分仓','ACTIVE')" >/dev/null
CSV_D1="$OUT_DIR/snap-legal.csv"
printf 'sku_code,warehouse_code,qty\nSKU-A,WH-MAIN,4.000\nSKU-A,WH-B,6.000\nSKU-C,WH-MAIN,2.000\n' > "$CSV_D1"
run_chain "$OP" "$CSV_D1" INVENTORY_SNAPSHOT "$MAP_SNAP"
D1B=$LAST_BATCH
# 第 1/3 行命中既有余额行 ⇒ 两条 IMP-4006 WARNING（覆盖/增量提示，非错误）；本组判据是 ERROR=0
chk "D1-校验0错误(告警2=IMP-4006)" "0|2" "$EC|$WN"
R=$(postn "$OP" "/api/v1/import/batches/$D1B/confirm" "b11-d1")
log "D1-confirm=$(st "$R") body=$(body "$R")"
chk "D1-confirm200" "200" "$(st "$R")"
chk "D1-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chkne "D1-不得PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "D1-failed_rows=0" "0" "$(jqn "$(body "$R")" failed_rows)"
chk "D1-无ERROR行" "0" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$D1B AND error_level='ERROR'")"
chk "D1-同SKU跨两仓各仓余额成立" "2" \
  "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND sku_id=9002 AND warehouse_id IN (9004,9005)")"
chk "D1-对账0行" "0" "$(recon)"

# ============================================================== 汇总
sep
log "余额全景 team7：$(balances)"
log "导入批次全景 team7：$(sql "SELECT GROUP_CONCAT(CONCAT(id,'/',biz_type,'/',status,'/attempt=',attempt_no) ORDER BY id) FROM import_batches WHERE team_id=7")"
log "B11_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B11_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B11_RESULT=ALL PASS"; else log "B11_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
