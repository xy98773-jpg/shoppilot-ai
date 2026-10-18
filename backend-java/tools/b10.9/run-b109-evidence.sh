#!/usr/bin/env bash
# =============================================================================
# B10.9（R86：主数据导入不得改写库内编码字面量）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3360 / 18240 —— 任务书指定。已用：3319、3327–3342、3355–3357、18099、18107–18210、
#   18220、18230；★ 3350 被宿主 WorkBuddy.exe 占用（任务书亦已禁）。
#
# 组次（对应任务书【4】a–h 与【5】门槛 2/3/4）：
#   G1  R86-a  SKU：库里 SKU-A，导入 sku-a → COMPLETED；①库内 code ②GET 对外返回 均仍为 SKU-A
#   G2  R86-b  WAREHOUSE：库里 WH-MAIN，导入 wh-main → code 仍为 WH-MAIN（①②）
#   G3  R86-c  SUPPLIER：库里 SUP-X，导入 sup-x → code 仍为 SUP-X（①②）
#   G4  R86-d  PRODUCT：库里 P-BAG-001，导入 p-bag-001 → code 仍为 P-BAG-001（①②）
#   G5  R86-e  回归：库里无该编码时导入【小写新编码】→ 真的新建且写入小写原样（不报 CAT-3002）
#   G6  R86-f  回归：完全同大小写既有编码仍走命中更新，可变字段确实被更新
#   G7  R86-g  审计：命中分支 biz_no == 库内既有写法（不是文件里的写法）
#   G8  R86-h  REST 回归：PATCH 改 code 仍生效（四类全测）；POST 创建不受影响
#   G9  R86-i  批量出口（Pass 1）：一批内「大写命中 + 小写命中」都命中，不退化为整批回滚
#   G10 门槛 3 边界 1：R85 四处判定键（R6 探针形态）—— 文件内大小写重复仍判 ERROR
#   G11 门槛 3 边界 2：R80 跨仓（同 SKU 两仓）→ COMPLETED，两仓余额各自正确
#   G12 门槛 3 边界 3：R79 PARTIAL_SUCCESS 仍可达（行级 SAL-6003，不是 COM-0002）
#   G13 门槛 4：seed-b95.sql 后【直接】跑 TEST_STRATEGY §7.3 对账 SQL → 0 行（不额外补流水）
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b10.9/run-b109-evidence.sh
#   可覆盖： B109_MYSQL_PORT=xxxx B109_APP_PORT=xxxxx
#
# 产物：
#   backend-java/tools/b10.9/evidence-b109.txt          ← 断言日志（入库）
#   backend-java/tools/b10.9/evidence-b109-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b10.9/evidence-b109-headers.log  ← 最近一次响应头（*.log 已 gitignore）
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
LOG="$OUT_DIR/evidence-b109.txt"
APP_LOG="$OUT_DIR/evidence-b109-app.log"
HDR_FILE="$OUT_DIR/evidence-b109-headers.log"
MYSQL_CT="b109-mysql"
MYSQL_PORT="${B109_MYSQL_PORT:-3360}"
APP_PORT="${B109_APP_PORT:-18240}"
IMPORT_DIR="${B109_IMPORT_DIR:-$OUT_DIR/.import-storage}"
PW="S3cret!Passw0rd"
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
log "PORTS mysql=$MYSQL_PORT app=$APP_PORT（任务书指定 3360 / 18240）"
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b109-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队7 仓库9004=WH-MAIN；SKU 9002=SKU-A / 9003=SKU-B / 9005=SKU-C 各10.000；商品9001=P-BAG-001；团队8)" \
  || { log "FATAL seed failed"; exit 5; }

# ★ 任务书【4】c 要求「库里 SUP-X」：seed-b95.sql 不含供应商行，本脚本显式补一行
#   （不修改 seed 文件本身——那是 evi 资产，本批零改动）。
sql_seed_sup(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot -e "$1"; }
sql_seed_sup "INSERT INTO suppliers (id, team_id, code, name, status) VALUES (9003, 7, 'SUP-X', '星海供应链', 'ACTIVE')" \
  && log "SEED_SUPPLIER_OK (9003 = SUP-X)" || { log "FATAL supplier seed failed"; exit 5; }

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
patchq(){ curl -s --noproxy '*' -w "|%{http_code}" -X PATCH "$BASE$2" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postj(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE$2" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
# ★ GET /api/v1/skus|warehouses|suppliers|products/{id} 的 data 里的编码字段
# ★ 必须先剥掉外层信封再取字段：warehouse/supplier 的业务字段就叫 "code"，
#   而信封里也有 "code":"OK" ⇒ 直接 jstr 全文会抓到 OK（首轮 G2/G3 的假 FAIL 根因）。
dataField(){ # resp field —— 只在 data 段内查找
  local d=$(echo "$1" | sed 's/.*"data"://')
  echo "$d" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"
}
apiSkuCode(){ dataField "$(getq "$1" "/api/v1/skus/$2")" sku_code; }
apiWhCode(){ dataField "$(getq "$1" "/api/v1/warehouses/$2")" code; }
apiSupCode(){ dataField "$(getq "$1" "/api/v1/suppliers/$2")" code; }
apiProdCode(){ dataField "$(getq "$1" "/api/v1/products/$2")" product_code; }
dbSkuCode(){ sql "SELECT sku_code FROM skus WHERE id=$1"; }
dbWhCode(){ sql "SELECT code FROM warehouses WHERE id=$1"; }
dbSupCode(){ sql "SELECT code FROM suppliers WHERE id=$1"; }
dbProdCode(){ sql "SELECT product_code FROM products WHERE id=$1"; }
# ★ 必须限定 change_summary='导入更新<资源名>'：REST PATCH 走 @Audited 切面也写 MASTER_DATA_STATUS_CHANGE
#   且【不填 biz_no】（切面不读业务表）⇒ 不限定就会在 G8 之后取到那条 biz_no=NULL 的 REST 审计。
bizNo(){ sql "SELECT biz_no FROM audit_logs WHERE action='MASTER_DATA_STATUS_CHANGE' AND biz_type='$1' AND biz_id=$2 AND change_summary='导入更新$3' ORDER BY id DESC LIMIT 1"; }
errOf(){ sql "SELECT CONCAT(row_no,'|',error_code,'|',error_level) FROM import_row_errors WHERE batch_id=$1 AND error_level='ERROR' ORDER BY row_no LIMIT 1"; }
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
# ★ 只映射【该 CSV 真实存在的列】：映射到文件里没有的列 ⇒ mapping 阶段 422，批次停在 PARSED，
#   后续 validate/confirm 全部连锁失败（首轮 53 个 FAIL 中有一半是这个原因，不是代码缺陷）。
#   barcode 只在 G6 那份 CSV 里出现，故另设 MAP_SKU_BARCODE。
MAP_SKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code","name":"name"},"parse_options_json":{}}'
MAP_SKU_MIN='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'
MAP_SKU_BARCODE='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code","barcode":"barcode"},"parse_options_json":{}}'
MAP_PROD='{"field_mapping_json":{"product_code":"product_code","name":"name"},"parse_options_json":{}}'
MAP_SUP='{"field_mapping_json":{"code":"code","name":"name"},"parse_options_json":{}}'
MAP_WH='{"field_mapping_json":{"code":"code","name":"name"},"parse_options_json":{}}'

# ============================================================== G1 R86-a SKU
sep; log "G1 R86-a：库里 SKU-A(9002)，导入单行 sku-a → COMPLETED；①库内 code ②GET 对外返回 均仍为 SKU-A"
log "G1-前置 库内=$(dbSkuCode 9002) 对外=$(apiSkuCode "$OP" 9002) 行数=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
CSV1="$OUT_DIR/sku-lower.csv"
printf 'sku_code,product_code,name\nsku-a,P-BAG-001,改后的名字\n' > "$CSV1"
run_chain "$OP" "$CSV1" SKU "$MAP_SKU"
B1=$LAST_BATCH
log "G1-validate err=$EC warn=$WN"
chk "G1-校验200" "200" "$VS"
chk "G1-error_rows=0（小写命中不得报错）" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B1/confirm" "b109-g1-sku")
log "G1-confirm=$(st "$R") body=$(body "$R")"
chk "G1-confirm200" "200" "$(st "$R")"
chk "G1-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G1-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G1-无行级错误" "0" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B1 AND error_level='ERROR'")"
chk "G1-①库内sku_code仍SKU-A" "SKU-A" "$(dbSkuCode 9002)"
chk "G1-②对外GET仍SKU-A" "SKU-A" "$(apiSkuCode "$OP" 9002)"
chkne "G1-库内不是sku-a" "sku-a" "$(dbSkuCode 9002)"
chk "G1-行数不变（UPDATE非新增）" "3" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "G1-可变字段name已更新" "改后的名字" "$(sql "SELECT name FROM skus WHERE id=9002")"

# ============================================================== G2 R86-b WAREHOUSE
sep; log "G2 R86-b：库里 WH-MAIN(9004)，导入 wh-main → code 仍为 WH-MAIN（①②）"
log "G2-前置 库内=$(dbWhCode 9004) 对外=$(apiWhCode "$OP" 9004)"
CSV2="$OUT_DIR/wh-lower.csv"
printf 'code,name\nwh-main,改名后的主仓\n' > "$CSV2"
run_chain "$OP" "$CSV2" WAREHOUSE "$MAP_WH"
B2=$LAST_BATCH
chk "G2-校验200" "200" "$VS"
chk "G2-error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B2/confirm" "b109-g2-wh")
log "G2-confirm=$(st "$R") body=$(body "$R")"
chk "G2-confirm200" "200" "$(st "$R")"
chk "G2-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G2-①库内code仍WH-MAIN" "WH-MAIN" "$(dbWhCode 9004)"
chk "G2-②对外GET仍WH-MAIN" "WH-MAIN" "$(apiWhCode "$OP" 9004)"
chkne "G2-库内不是wh-main" "wh-main" "$(dbWhCode 9004)"
chk "G2-行数不变" "1" "$(sql "SELECT COUNT(*) FROM warehouses WHERE team_id=7")"
chk "G2-可变字段name已更新" "改名后的主仓" "$(sql "SELECT name FROM warehouses WHERE id=9004")"

# ============================================================== G3 R86-c SUPPLIER
sep; log "G3 R86-c：库里 SUP-X(9003)，导入 sup-x → code 仍为 SUP-X（①②）"
log "G3-前置 库内=$(dbSupCode 9003) 对外=$(apiSupCode "$OP" 9003)"
CSV3="$OUT_DIR/sup-lower.csv"
printf 'code,name\nsup-x,改名后的供应商\n' > "$CSV3"
run_chain "$OP" "$CSV3" SUPPLIER "$MAP_SUP"
B3=$LAST_BATCH
chk "G3-校验200" "200" "$VS"
chk "G3-error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B3/confirm" "b109-g3-sup")
log "G3-confirm=$(st "$R") body=$(body "$R")"
chk "G3-confirm200" "200" "$(st "$R")"
chk "G3-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G3-①库内code仍SUP-X" "SUP-X" "$(dbSupCode 9003)"
chk "G3-②对外GET仍SUP-X" "SUP-X" "$(apiSupCode "$OP" 9003)"
chkne "G3-库内不是sup-x" "sup-x" "$(dbSupCode 9003)"
chk "G3-行数不变" "1" "$(sql "SELECT COUNT(*) FROM suppliers WHERE team_id=7")"
chk "G3-可变字段name已更新" "改名后的供应商" "$(sql "SELECT name FROM suppliers WHERE id=9003")"

# ============================================================== G4 R86-d PRODUCT
sep; log "G4 R86-d：库里 P-BAG-001(9001)，导入 p-bag-001 → code 仍为 P-BAG-001（①②）"
log "G4-前置 库内=$(dbProdCode 9001) 对外=$(apiProdCode "$OP" 9001)"
CSV4="$OUT_DIR/prod-lower.csv"
printf 'product_code,name\np-bag-001,改名后的商品\n' > "$CSV4"
run_chain "$OP" "$CSV4" PRODUCT "$MAP_PROD"
B4=$LAST_BATCH
chk "G4-校验200" "200" "$VS"
chk "G4-error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B4/confirm" "b109-g4-prod")
log "G4-confirm=$(st "$R") body=$(body "$R")"
chk "G4-confirm200" "200" "$(st "$R")"
chk "G4-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G4-①库内product_code仍P-BAG-001" "P-BAG-001" "$(dbProdCode 9001)"
chk "G4-②对外GET仍P-BAG-001" "P-BAG-001" "$(apiProdCode "$OP" 9001)"
chkne "G4-库内不是p-bag-001" "p-bag-001" "$(dbProdCode 9001)"
chk "G4-行数不变" "1" "$(sql "SELECT COUNT(*) FROM products WHERE team_id=7")"
chk "G4-可变字段name已更新" "改名后的商品" "$(sql "SELECT name FROM products WHERE id=9001")"

# ============================================================== G5 R86-e 回归：新建
sep; log "G5 R86-e 回归：库里无该编码时导入【小写新编码】→ 真的新建且写入小写原样（不报 CAT-3002）"
CSV5A="$OUT_DIR/sku-new-lower.csv"
printf 'sku_code,product_code,name\nsku-brand-new,P-BAG-001,全新SKU\n' > "$CSV5A"
run_chain "$OP" "$CSV5A" SKU "$MAP_SKU"
B5A=$LAST_BATCH
chk "G5-校验200" "200" "$VS"
R=$(postn "$OP" "/api/v1/import/batches/$B5A/confirm" "b109-g5a-sku-new")
log "G5a-confirm=$(st "$R") body=$(body "$R")"
chk "G5a-confirm200（不报CAT-3002）" "200" "$(st "$R")"
chk "G5a-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
# ★ 必须用 BINARY 比较：列排序规则是 utf8mb4_0900_ai_ci，
#   WHERE sku_code='SKU-BRAND-NEW' 会匹配到 'sku-brand-new' 那一行 ⇒ 普通比较无法区分大小写。
chk "G5a-库内写入小写原样(BINARY)" "1" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND BINARY sku_code='sku-brand-new'")"
chk "G5a-库内未出现大写写法(BINARY)" "0" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND BINARY sku_code='SKU-BRAND-NEW'")"
chk "G5a-SKU总数3+1" "4" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"

CSV5B="$OUT_DIR/wh-new-lower.csv"
printf 'code,name\nwh-branch,分仓\n' > "$CSV5B"
run_chain "$OP" "$CSV5B" WAREHOUSE "$MAP_WH"
B5B=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B5B/confirm" "b109-g5b-wh-new")
chk "G5b-confirm200" "200" "$(st "$R")"
chk "G5b-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G5b-仓库写入小写原样" "1" "$(sql "SELECT COUNT(*) FROM warehouses WHERE team_id=7 AND code='wh-branch'")"
chk "G5b-仓库总数1+1" "2" "$(sql "SELECT COUNT(*) FROM warehouses WHERE team_id=7")"

CSV5C="$OUT_DIR/sup-new-lower.csv"
printf 'code,name\nsup-new,新供应商\n' > "$CSV5C"
run_chain "$OP" "$CSV5C" SUPPLIER "$MAP_SUP"
B5C=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B5C/confirm" "b109-g5c-sup-new")
chk "G5c-confirm200" "200" "$(st "$R")"
chk "G5c-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G5c-供应商写入小写原样" "1" "$(sql "SELECT COUNT(*) FROM suppliers WHERE team_id=7 AND code='sup-new'")"
chk "G5c-供应商总数1+1" "2" "$(sql "SELECT COUNT(*) FROM suppliers WHERE team_id=7")"

CSV5D="$OUT_DIR/prod-new-lower.csv"
printf 'product_code,name\np-tote-001,手提袋\n' > "$CSV5D"
run_chain "$OP" "$CSV5D" PRODUCT "$MAP_PROD"
B5D=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B5D/confirm" "b109-g5d-prod-new")
chk "G5d-confirm200" "200" "$(st "$R")"
chk "G5d-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G5d-商品写入小写原样" "1" "$(sql "SELECT COUNT(*) FROM products WHERE team_id=7 AND product_code='p-tote-001'")"
chk "G5d-商品总数1+1" "2" "$(sql "SELECT COUNT(*) FROM products WHERE team_id=7")"

# ============================================================== G6 R86-f 回归：命中更新
sep; log "G6 R86-f 回归：完全同大小写既有编码导入仍走命中更新，可变字段确实被更新"
CSV6="$OUT_DIR/sku-exact.csv"
printf 'sku_code,product_code,barcode\nSKU-B,P-BAG-001,6901234567890\n' > "$CSV6"
run_chain "$OP" "$CSV6" SKU "$MAP_SKU_BARCODE"
B6=$LAST_BATCH
chk "G6-校验200" "200" "$VS"
R=$(postn "$OP" "/api/v1/import/batches/$B6/confirm" "b109-g6-exact")
log "G6-confirm=$(st "$R") body=$(body "$R")"
chk "G6-confirm200" "200" "$(st "$R")"
chk "G6-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G6-行数不变（仍走命中更新）" "4" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "G6-库内code仍SKU-B" "SKU-B" "$(dbSkuCode 9003)"
chk "G6-可变字段barcode确实被更新" "6901234567890" "$(sql "SELECT barcode FROM skus WHERE id=9003")"

# ============================================================== G7 R86-g 审计
sep; log "G7 R86-g 审计：命中分支 biz_no == 库内既有写法（不是文件里的写法）"
CSV7="$OUT_DIR/sku-audit.csv"
printf 'sku_code,product_code,name\nsku-a,P-BAG-001,审计用名字\n' > "$CSV7"
run_chain "$OP" "$CSV7" SKU "$MAP_SKU"
B7=$LAST_BATCH
R=$(postn "$OP" "/api/v1/import/batches/$B7/confirm" "b109-g7-audit")
chk "G7-confirm200" "200" "$(st "$R")"
# ★ 本脚本里 G1 与 G7 两次导入都命中了 9002 ⇒ 恰好【两条】导入更新审计（一次导入一条，不是缺陷）。
#   真正要验的是 biz_no：两条都必须是【库内既有写法 SKU-A】，不得出现文件里的小写。
chk "G7-SKU导入更新审计恰好2条（G1+G7各一次）" "2" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='MASTER_DATA_STATUS_CHANGE' AND biz_type='SKU' AND biz_id=9002 AND change_summary='导入更新 SKU'")"
chk "G7-两条审计biz_no均为库内写法SKU-A" "2" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='MASTER_DATA_STATUS_CHANGE' AND biz_type='SKU' AND biz_id=9002 AND change_summary='导入更新 SKU' AND BINARY biz_no='SKU-A'")"
chk "G7-无任何审计biz_no被写成小写" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='MASTER_DATA_STATUS_CHANGE' AND biz_type='SKU' AND biz_id=9002 AND BINARY biz_no='sku-a'")"
chk "G7-SKU审计biz_no=SKU-A" "SKU-A" "$(bizNo SKU 9002 " SKU")"
chkne "G7-SKU审计biz_no≠sku-a" "sku-a" "$(bizNo SKU 9002 " SKU")"
CSV7B="$OUT_DIR/wh-audit.csv"
printf 'code,name\nwh-main,审计仓库\n' > "$CSV7B"
run_chain "$OP" "$CSV7B" WAREHOUSE "$MAP_WH"
B7B=$LAST_BATCH
postn "$OP" "/api/v1/import/batches/$B7B/confirm" "b109-g7b-wh" >/dev/null
chk "G7-WH审计biz_no=WH-MAIN" "WH-MAIN" "$(bizNo WAREHOUSE 9004 "仓库")"
CSV7C="$OUT_DIR/sup-audit.csv"
printf 'code,name\nsup-x,审计供应商\n' > "$CSV7C"
run_chain "$OP" "$CSV7C" SUPPLIER "$MAP_SUP"
B7C=$LAST_BATCH
postn "$OP" "/api/v1/import/batches/$B7C/confirm" "b109-g7c-sup" >/dev/null
chk "G7-SUP审计biz_no=SUP-X" "SUP-X" "$(bizNo SUPPLIER 9003 "供应商")"
CSV7D="$OUT_DIR/prod-audit.csv"
printf 'product_code,name\np-bag-001,审计商品\n' > "$CSV7D"
run_chain "$OP" "$CSV7D" PRODUCT "$MAP_PROD"
B7D=$LAST_BATCH
postn "$OP" "/api/v1/import/batches/$B7D/confirm" "b109-g7d-prod" >/dev/null
chk "G7-PROD审计biz_no=P-BAG-001" "P-BAG-001" "$(bizNo PRODUCT 9001 "商品")"

# ============================================================== G9 R86-i 批量出口
sep; log "G9 R86-i 批量出口(Pass 1)：一批内「大写命中+小写命中」都命中更新，不退化为整批回滚"
# 用 9003(SKU-B) 与 9005(SKU-C)；一个写大写、一个写小写
CSV9="$OUT_DIR/sku-batch-case.csv"
printf 'sku_code,product_code,name\nSKU-B,P-BAG-001,大写命中\nsku-c,P-BAG-001,小写命中\n' > "$CSV9"
run_chain "$OP" "$CSV9" SKU "$MAP_SKU"
B9=$LAST_BATCH
chk "G9-校验200" "200" "$VS"
chk "G9-error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B9/confirm" "b109-g9-batch")
log "G9-confirm=$(st "$R") body=$(body "$R")"
chk "G9-confirm200" "200" "$(st "$R")"
chk "G9-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G9-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G9-本批无行级错误（小写那行不得CAT-3002）" "0" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$B9 AND error_level='ERROR'")"
chk "G9-行数不变（两行都是UPDATE）" "4" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "G9-大写命中行name已更新" "大写命中" "$(sql "SELECT name FROM skus WHERE id=9003")"
chk "G9-小写命中行code仍SKU-C" "SKU-C" "$(dbSkuCode 9005)"
chk "G9-小写命中行name已更新" "小写命中" "$(sql "SELECT name FROM skus WHERE id=9005")"

# ============================================================== G10 边界：R85 四处判定键
sep; log "G10 门槛3边界1：R85 四处判定键（R6 探针形态）—— 文件内大小写重复仍判 ERROR"
CSV10A="$OUT_DIR/snap-case.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date\nSKU-A,WH-MAIN,2.000,%s\nsku-a,wh-main,3.000,%s\n' "$D1" "$D1" > "$CSV10A"
run_chain "$OP" "$CSV10A" INVENTORY_SNAPSHOT "$MAP_SNAP"
B10A=$LAST_BATCH
chk "G10a-校验200" "200" "$VS"
chk "G10a-error_rows=1（快照判定键）" "1" "$EC"
E=$(errOf "$B10A")
chk "G10a-失败行号2（后行）" "2" "$(echo "$E" | cut -d'|' -f1)"
chk "G10a-错误码COM-0001（R83既定）" "COM-0001" "$(echo "$E" | cut -d'|' -f2)"
chk "G10a-错误级别ERROR（不得降级）" "ERROR" "$(echo "$E" | cut -d'|' -f3)"
R=$(postn "$OP" "/api/v1/import/batches/$B10A/confirm" "b109-g10a-case")
chk "G10a-confirm422前置门" "422" "$(st "$R")"
chk "G10a-code=IMP-4009" "IMP-4009" "$(jstr "$(body "$R")" code)"

CSV10B="$OUT_DIR/sales-case.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-A,WH-MAIN,%s,12.000,0.000,1080.00,8\nsku-a,wh-main,%s,6.000,0.000,540.00,4\n' "$D2" "$D2" > "$CSV10B"
run_chain "$OP" "$CSV10B" SALES_DAILY "$MAP_SALES"
B10B=$LAST_BATCH
chk "G10b-error_rows=1（销售三元组判定键）" "1" "$EC"
E=$(errOf "$B10B")
chk "G10b-错误码SAL-6004（既定）" "SAL-6004" "$(echo "$E" | cut -d'|' -f2)"
chkne "G10b-不是COM-0001" "COM-0001" "$(echo "$E" | cut -d'|' -f2)"

CSV10C="$OUT_DIR/sku-case.csv"
printf 'sku_code,product_code\nSKU-NEW-1,P-BAG-001\nsku-new-1,P-BAG-001\n' > "$CSV10C"
# ★ 这份 CSV 只有两列，用两列映射（用 MAP_SKU 会因映射到不存在的 name 列而 422）
run_chain "$OP" "$CSV10C" SKU "$MAP_SKU_MIN"
B10C=$LAST_BATCH
chk "G10c-error_rows=1（主数据duplicateCode判定键）" "1" "$EC"
E=$(errOf "$B10C")
chk "G10c-错误码CAT-3002（既定）" "CAT-3002" "$(echo "$E" | cut -d'|' -f2)"

# ============================================================== G11 边界：R80 跨仓
sep; log "G11 门槛3边界2：R80 跨仓不误杀（同 SKU 两仓）→ COMPLETED，两仓余额各自正确"
sql "INSERT INTO warehouses (id, team_id, code, name, status) VALUES (9006,7,'WH-B','副仓','ACTIVE')" >/dev/null
CSV11="$OUT_DIR/snap-cross-wh.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date\nSKU-A,WH-MAIN,3.000,%s\nsku-a,wh-b,4.000,%s\n' "$D1" "$D1" > "$CSV11"
run_chain "$OP" "$CSV11" INVENTORY_SNAPSHOT "$MAP_SNAP"
B11=$LAST_BATCH
chk "G11-error_rows=0" "0" "$EC"
R=$(postn "$OP" "/api/v1/import/batches/$B11/confirm" "b109-g11-cross")
log "G11-confirm=$(st "$R") body=$(body "$R")"
chk "G11-confirm200" "200" "$(st "$R")"
chk "G11-终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
chk "G11-成功2行" "2" "$(jqn "$(body "$R")" success_rows)"
chk "G11-主仓余额10+3" "13.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "G11-副仓余额0+4" "4.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9006 AND sku_id=9002")"
chk "G11-幂等键后缀=主仓9004" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=9002 AND warehouse_id=9004 AND idempotency_key LIKE '%:9002:9004'")"
chk "G11-幂等键后缀=副仓9006" "1" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND sku_id=9002 AND warehouse_id=9006 AND idempotency_key LIKE '%:9002:9006'")"

# ============================================================== G12 边界：R79 PARTIAL_SUCCESS
sep; log "G12 门槛3边界3：R79 PARTIAL_SUCCESS 仍可达（SALES_DAILY 写服务内部失败 → SAL-6003）"
CSV12="$OUT_DIR/sales-partial.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-A,WH-MAIN,%s,4.000,0.000,360.00,3\nSKU-B,WH-MAIN,%s,9.000,0.000,810.00,7\n' "$D2" "$D2" > "$CSV12"
run_chain "$OP" "$CSV12" SALES_DAILY "$MAP_SALES"
B12=$LAST_BATCH
chk "G12-校验200" "200" "$VS"
chk "G12-校验0错误（两行键不同）" "0" "$EC"
sql "UPDATE skus SET listed_date='2026-10-20' WHERE id=9003 AND team_id=7" >/dev/null
log "G12-漂移 listed_date=$(sql "SELECT listed_date FROM skus WHERE id=9003") > sale_date=$D2"
SALES12=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")
R=$(postn "$OP" "/api/v1/import/batches/$B12/confirm" "b109-g12-partial")
log "G12-confirm=$(st "$R") body=$(body "$R")"
chk "G12-confirm200" "200" "$(st "$R")"
chk "G12-终态PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(jstr "$(body "$R")" status)"
chk "G12-成功1行" "1" "$(jqn "$(body "$R")" success_rows)"
chk "G12-失败1行" "1" "$(jqn "$(body "$R")" failed_rows)"
E=$(errOf "$B12")
chk "G12-行级码SAL-6003" "SAL-6003" "$(echo "$E" | cut -d'|' -f2)"
chkne "G12-不是COM-0002" "COM-0002" "$(echo "$E" | cut -d'|' -f2)"
chk "G12-成功行已落库" "$((SALES12 + 1))" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"

# ============================================================== G13 门槛 4：对账
sep; log "G13 门槛4：seed-b95.sql 后【直接】跑 TEST_STRATEGY §7.3 对账 SQL（不额外补流水）"
log "G13-种子流水=$(sql "SELECT GROUP_CONCAT(CONCAT(sku_id,'/',idempotency_key) ORDER BY sku_id) FROM inventory_transactions WHERE team_id=7 AND biz_type='IMPORT'")"
RECON=$(sql "SELECT COUNT(*) FROM (SELECT b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty,
  COALESCE(SUM(t.qty_delta),0) AS derived_qty FROM inventory_balances b
  LEFT JOIN inventory_transactions t ON t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id
  GROUP BY b.team_id,b.warehouse_id,b.sku_id,b.on_hand_qty
  HAVING b.on_hand_qty <> COALESCE(SUM(t.qty_delta),0)) x")
log "G13-对账明细=$(sql "SELECT CONCAT(warehouse_id,'/',sku_id,':',on_hand_qty) FROM inventory_balances WHERE team_id=7 ORDER BY sku_id,warehouse_id" | tr '\n' ' ')"
chk "G13-余额可由流水推导（§7.3）" "0" "$RECON"
chk "G13-qty_after恒等式" "0" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE qty_after <> qty_before + qty_delta")"
chk "G13-不变式success+failed=total" "0" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND status IN ('COMPLETED','PARTIAL_SUCCESS') AND success_rows + failed_rows <> total_rows")"
chk "G13-种子未出现无流水余额行" "0" "$(sql "SELECT COUNT(*) FROM inventory_balances b WHERE b.team_id=7 AND NOT EXISTS (SELECT 1 FROM inventory_transactions t WHERE t.team_id=b.team_id AND t.warehouse_id=b.warehouse_id AND t.sku_id=b.sku_id)")"

# ============================================================== G8 R86-h REST 回归
sep; log "G8 R86-h REST 回归：PATCH 改 code 仍生效（四类全测）；POST 创建不受影响"
R=$(patchq "$OP" "/api/v1/skus/9002" '{"sku_code":"SKU-A-RENAMED","name":"REST改名"}')
chk "G8-PATCH sku 200" "200" "$(st "$R")"
chk "G8-①库内sku_code=SKU-A-RENAMED" "SKU-A-RENAMED" "$(dbSkuCode 9002)"
chk "G8-②对外GET同步" "SKU-A-RENAMED" "$(apiSkuCode "$OP" 9002)"
R=$(patchq "$OP" "/api/v1/warehouses/9004" '{"code":"WH-RENAMED"}')
chk "G8-PATCH wh 200" "200" "$(st "$R")"
chk "G8-仓库code=WH-RENAMED" "WH-RENAMED" "$(dbWhCode 9004)"
R=$(patchq "$OP" "/api/v1/suppliers/9003" '{"code":"SUP-RENAMED"}')
chk "G8-PATCH sup 200" "200" "$(st "$R")"
chk "G8-供应商code=SUP-RENAMED" "SUP-RENAMED" "$(dbSupCode 9003)"
R=$(patchq "$OP" "/api/v1/products/9001" '{"product_code":"P-RENAMED"}')
chk "G8-PATCH prod 200" "200" "$(st "$R")"
chk "G8-商品product_code=P-RENAMED" "P-RENAMED" "$(dbProdCode 9001)"
R=$(postj "$OP" "/api/v1/products" '{"product_code":"p-post-001","name":"REST建的"}')
chk "G8-POST products 201（create分支不变）" "201" "$(st "$R")"
chk "G8-POST小写原样落库" "1" "$(sql "SELECT COUNT(*) FROM products WHERE team_id=7 AND product_code='p-post-001'")"

# ============================================================== 汇总
sep
log "编码全景 team7：skus=$(sql "SELECT GROUP_CONCAT(CONCAT(id,'=',sku_code) ORDER BY id) FROM skus WHERE team_id=7")"
log "            wh=$(sql "SELECT GROUP_CONCAT(CONCAT(id,'=',code) ORDER BY id) FROM warehouses WHERE team_id=7")"
log "            sup=$(sql "SELECT GROUP_CONCAT(CONCAT(id,'=',code) ORDER BY id) FROM suppliers WHERE team_id=7")"
log "            prod=$(sql "SELECT GROUP_CONCAT(CONCAT(id,'=',product_code) ORDER BY id) FROM products WHERE team_id=7")"
log "审计biz_no：SKU9002=$(bizNo SKU 9002 " SKU") WH9004=$(bizNo WAREHOUSE 9004 "仓库") SUP9003=$(bizNo SUPPLIER 9003 "供应商") PROD9001=$(bizNo PRODUCT 9001 "商品")"
log "B109_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B109_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B109_RESULT=ALL PASS"; else log "B109_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
