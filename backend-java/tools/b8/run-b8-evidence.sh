#!/usr/bin/env bash
# =============================================================================
# B8（M3-T12 / T13 · sales 销售数据）真栈取证脚本
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器（端口刻意错开历史批次：
#   B7 复核用 3327/18107，本脚本用 3328/18108），不触碰 dev 栈、不触碰他人库。
#
# ★ 执行顺序说明：S4（聚合）刻意排在 S2/S3 之前——为了让聚合期望值等价于
#   「纯种子态」（30/5/30、35/30、45/20）。若先做 S2 的 upsert 写入，
#   9002 会变成 37、9004 会变成 42，期望值就不再是种子的语义了。
#   S4e 用 2026-11-xx 写升序探针，避开 S3 的「2026-10-04 零落库」断言。
#
# 组次（对应任务书「七、真栈取证 ≥5 组」）：
#   S1  迁移链 9 条全 success + sales_daily 表结构与约束齐
#   S4  groupBy=sku|warehouse|date 三聚合正确 + 非法维度 COM-0007 + getSeries 依赖的日期升序
#   S2  POST 手工写入 + 同业务键再写不同 qty → 行数仍 1（upsert 覆盖，U-6）
#   S3  跨团队 → SAL-6005(422)；qty<0 → SAL-6002(400)；sale_date 非法 → SAL-6003(400)
#   S5  角色：VIEWER POST → TEAM-2003(403)；OPERATOR POST → 201；审计条数正确
#
# 附加探针组（P）：开发未覆盖但 SSOT 有硬要求的项（评审法论：每批至少 3 条）
#   P1  upsert 可把 sales_amount 覆盖为 NULL（覆盖语义完整性）
#   P2  pageSize=201 → COM-0007（不静默夹取）
#   P3  分页响应字段名为 list（非 items）
#   P4  product_id 冗余列落库值 == sku.product_id
#   P5  审计 change_summary 含「覆盖」行数
#   P6  team_id 由租户线自动补齐为 7（调用方不传 team_id）
#   P7  GET 零副作用（行数不变）
#   P8  读隔离：团队 8 的 owner8 看不到团队 7 的销售数据
#
# 用法：bash backend-java/tools/b8/run-b8-evidence.sh
# 产物：backend-java/tools/b8/evidence-b8.txt        ← 断言日志（入库）
#       backend-java/tools/b8/evidence-b8-app.log    ← 应用 stdout（*.log 已 gitignore）
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖（本脚本第一版即因此丢了 S1 段）。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b8.txt"
APP_LOG="$OUT_DIR/evidence-b8-app.log"
MYSQL_CT="b8-mysql"
MYSQL_PORT=3328
APP_PORT=18108
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found (先执行 ./mvnw -B verify 或 package)"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; pkill -f "server.port=$APP_PORT" 2>/dev/null || true; }
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
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b8-app.log)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

# ★ --default-character-set=utf8mb4 必须加：否则中文断言（如 LIKE '%覆盖%'）的字面量
#   会被客户端按 latin1 发出、中文退化成 '?'，导致假 FAIL。
MYSQL_FLAGS=(--default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot)
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot < "$EVI/seed-b8.sql" >> "$APP_LOG" 2>&1 && log "SEED_OK (seed-b8.sql)"

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST\n  - $1: expected=[$2] actual=[$3]"; fi
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
post(){ # bearer json
  curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"
}
getq(){ curl -s --noproxy '*' -w "|%{http_code}" "$BASE$1" -H "Authorization: Bearer $2"; }
gq(){ # body groupKey -> qty 字符串
  echo "$1" | grep -o "{\"group_key\":\"$2\"[^}]*}" | grep -o '"qty":"[^"]*"' | sed 's/"qty":"//;s/"//'
}

OWN=$(token owner01); ADM=$(token admin01); OP=$(token operator01); VIEW=$(token viewer01); OTH=$(token owner8)
log "TOKENS own=${OWN:0:8} adm=${ADM:0:8} op=${OP:0:8} view=${VIEW:0:8} other=${OTH:0:8}"
[ -n "$OP" ] || { log "FATAL operator01 login failed"; exit 5; }

# =============================================================== S1 迁移 + 约束
sep; log "S1 迁移链与 sales_daily 结构/约束"
chk "S1-迁移条数" "9" "$(sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1")"
chk "S1-最高版本" "1.0.8" "$(sql "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1")"
log "S1-迁移清单=$(sql "SELECT GROUP_CONCAT(CONCAT(version,'(',IF(success=1,'OK','FAIL'),')') ORDER BY installed_rank) FROM flyway_schema_history")"
chk "S1-表存在" "1" "$(sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='shoppilot' AND table_name='sales_daily'")"
# ★ 统计索引条数必须 COUNT(DISTINCT index_name)：information_schema.statistics 按列展开，COUNT(*) 会多算
chk "S1-唯一键数(DISTINCT)" "1" "$(sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=0 AND index_name<>'PRIMARY'")"
chk "S1-唯一键名" "uk_sales_team_wh_sku_date" "$(sql "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=0 AND index_name<>'PRIMARY'")"
chk "S1-唯一键列序" "team_id,warehouse_id,sku_id,sale_date" "$(sql "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND index_name='uk_sales_team_wh_sku_date'")"
# ★ 二级索引要按 idx_ 前缀过滤：MySQL 会为每个外键自动创建同名（fk_*）支撑索引，
#   因为本表 4 条 idx_sales_team_* 的最左列都是 team_id，无法单独服务 warehouse_id / sku_id 的外键。
#   若直接数 non_unique=1 会得到 6（4 声明 + 2 外键自动），属误判——B5 复核曾因 COUNT(*) 误判 24 条。
chk "S1-声明二级索引数(DISTINCT)" "4" "$(sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=1 AND index_name LIKE 'idx\_%'")"
chk "S1-声明二级索引集" "idx_sales_team_batch,idx_sales_team_date,idx_sales_team_product_date,idx_sales_team_sku_date" \
  "$(sql "SELECT GROUP_CONCAT(DISTINCT index_name ORDER BY index_name SEPARATOR ',') FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=1 AND index_name LIKE 'idx\_%'")"
chk "S1-四索引均team_id最左" "4" "$(sql "SELECT COUNT(*) FROM (SELECT index_name, SUBSTRING_INDEX(GROUP_CONCAT(column_name ORDER BY seq_in_index),',',1) AS firstcol FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=1 AND index_name LIKE 'idx\_%' GROUP BY index_name) t WHERE t.firstcol='team_id'")"
chk "S1-外键自动支撑索引" "2" "$(sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND index_name LIKE 'fk\_%'")"
chk "S1-外键数" "2" "$(sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='sales_daily'")"
chk "S1-外键全RESTRICT" "2" "$(sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='sales_daily' AND delete_rule='RESTRICT' AND update_rule='RESTRICT'")"
chk "S1-外键引用表" "skus,warehouses" "$(sql "SELECT GROUP_CONCAT(DISTINCT referenced_table_name ORDER BY referenced_table_name SEPARATOR ',') FROM information_schema.key_column_usage WHERE table_schema='shoppilot' AND table_name='sales_daily' AND referenced_table_name IS NOT NULL")"
chk "S1-CHECK数" "4" "$(sql "SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema='shoppilot' AND table_name='sales_daily' AND constraint_type='CHECK'")"
log "S1-CHECK清单=$(sql "SELECT GROUP_CONCAT(DISTINCT constraint_name ORDER BY constraint_name SEPARATOR ',') FROM information_schema.table_constraints WHERE constraint_schema='shoppilot' AND table_name='sales_daily' AND constraint_type='CHECK'")"
chk "S1-无version列" "0" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name='version'")"
chk "S1-审计字段集合A" "created_at,created_by,updated_at,updated_by" "$(sql "SELECT GROUP_CONCAT(column_name ORDER BY column_name SEPARATOR ',') FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name IN ('created_at','updated_at','created_by','updated_by')")"
chk "S1-数量精度" "decimal(18,3)" "$(sql "SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name='qty'")"
chk "S1-金额精度" "decimal(18,2)" "$(sql "SELECT COLUMN_TYPE FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name='sales_amount'")"
chk "S1-sale_date类型" "date" "$(sql "SELECT DATA_TYPE FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name='sale_date'")"

# =============================================================== S4 聚合（先于任何写入）
sep; log "S4a groupBy=sku 聚合（纯种子态，期望 9002=30、9003=5、9005=30）"
G=$(getq "/api/v1/sales/daily?groupBy=sku" "$OWN")
chk "S4a-http" "200" "${G##*|}"
BODY="${G%|*}"
for pair in "9002 30" "9003 5" "9005 30"; do
  k=${pair%% *}; v=${pair##* }
  chk "S4a-sku$k(qty)" "$v.000" "$(gq "$BODY" "$k")"
done

sep; log "S4b groupBy=warehouse 聚合（期望 9004=35、9007=30）"
G=$(getq "/api/v1/sales/daily?groupBy=warehouse" "$OWN")
BODY="${G%|*}"
for pair in "9004 35" "9007 30"; do
  k=${pair%% *}; v=${pair##* }
  chk "S4b-wh$k(qty)" "$v.000" "$(gq "$BODY" "$k")"
done

sep; log "S4c groupBy=date 聚合（期望 2026-10-01=45、2026-10-02=20）"
G=$(getq "/api/v1/sales/daily?groupBy=date" "$OWN")
BODY="${G%|*}"
for pair in "2026-10-01 45" "2026-10-02 20"; do
  k=${pair%% *}; v=${pair##* }
  chk "S4c-date$k(qty)" "$v.000" "$(gq "$BODY" "$k")"
done

sep; log "S4d groupBy 非法维度 → COM-0007"
G=$(getq "/api/v1/sales/daily?groupBy=product" "$OWN")
chk "S4d-code" "COM-0007" "$(code "${G%|*}")"
chk "S4d-http" "400" "${G##*|}"

sep; log "S4e 日期升序（getSeries 依赖的 ORDER BY sale_date ASC；用 2026-11-xx 避开 S3 断言）"
for d in 06 04 05; do
  post "$OP" "{\"sku_id\":9003,\"warehouse_id\":9004,\"sale_date\":\"2026-11-$d\",\"qty\":\"1\"}" > /dev/null
done
log "S4e-插入序(id序)=$(sql "SELECT GROUP_CONCAT(sale_date ORDER BY id) FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003 AND sale_date>='2026-11-01'")"
chk "S4e-升序结果" "2026-11-04,2026-11-05,2026-11-06" \
  "$(sql "SELECT GROUP_CONCAT(sale_date ORDER BY sale_date ASC) FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003 AND sale_date>='2026-11-01'")"
log "NOTE S4e 说明：getSeries 本批无 HTTP 端点（供 analysis 内部调用），此处取证其发出的"
log "     ORDER BY sale_date ASC 语义；Java 层完整断言见 SalesQueryServiceTest（同为真 MySQL + 真 Spring Bean）。"

# =============================================================== S2 upsert 覆盖
sep; log "S2 POST 手工写入 + 同业务键再写不同 qty → 行数仍 1"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"15","sales_amount":"150.00"}')
chk "S2-首次http" "201" "${R##*|}"
log "S2-首次body=${R%|*}"
ID1=$(echo "${R%|*}" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
chk "S2-首行qty" "15.000" "$(sql "SELECT qty FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND sale_date='2026-10-03'")"
chk "S2-首行source" "MANUAL" "$(sql "SELECT source FROM sales_daily WHERE id=$ID1")"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"7","sales_amount":"70.00"}')
chk "S2-二次http" "201" "${R##*|}"
ID2=$(echo "${R%|*}" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
chk "S2-同行数仍1" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND sale_date='2026-10-03'")"
chk "S2-同主键" "$ID1" "$ID2"
chk "S2-qty被覆盖为7" "7.000" "$(sql "SELECT qty FROM sales_daily WHERE id=$ID1")"
chk "S2-无累加行" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND sale_date='2026-10-03' AND qty=22.000")"

# =============================================================== S3 校验类错误码
sep; log "S3 跨团队 / qty 负 / sale_date 非法"
R=$(post "$OP" '{"sku_id":9102,"warehouse_id":9004,"sale_date":"2026-10-04","qty":"1"}')
chk "S3a-跨团队SKU-code" "SAL-6005" "$(code "${R%|*}")"
chk "S3a-跨团队SKU-http" "422" "${R##*|}"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9103,"sale_date":"2026-10-04","qty":"1"}')
chk "S3b-跨团队仓库-code" "SAL-6005" "$(code "${R%|*}")"
chk "S3b-跨团队仓库-http" "422" "${R##*|}"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-04","qty":"-1"}')
chk "S3c-qty负-code" "SAL-6002" "$(code "${R%|*}")"
chk "S3c-qty负-http" "400" "${R##*|}"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-13-40","qty":"1"}')
chk "S3d-日期非法-code" "SAL-6003" "$(code "${R%|*}")"
chk "S3d-日期非法-http" "400" "${R##*|}"
R=$(post "$OP" '{"warehouse_id":9004,"sale_date":"2026-10-04","qty":"1"}')
chk "S3e-缺sku-code" "COM-0001" "$(code "${R%|*}")"
chk "S3e-缺sku-http" "400" "${R##*|}"
chk "S3-负向用例零落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date='2026-10-04'")"

# =============================================================== S5 角色 + 审计
sep; log "S5 角色与控制：VIEWER POST / OPERATOR POST / 审计条数"
A0=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")
R=$(post "$VIEW" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-07","qty":"1"}')
chk "S5a-VIEWER-code" "TEAM-2003" "$(code "${R%|*}")"
chk "S5a-VIEWER-http" "403" "${R##*|}"
chk "S5a-VIEWER未落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date='2026-10-07'")"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-07","qty":"1"}')
chk "S5b-OPERATOR-http" "201" "${R##*|}"
A1=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")
chk "S5c-审计+1" "$((A0+1))" "$A1"
R=$(post "$ADM" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-08","qty":"1"}')
chk "S5d-ADMIN-http" "201" "${R##*|}"
G=$(getq "/api/v1/sales/daily" "$VIEW")
chk "S5e-VIEWER可读" "200" "${G##*|}"
G=$(getq "/api/v1/sales/daily" "")
chk "S5f-无令牌401" "401" "${G##*|}"

# =============================================================== P 附加探针
sep; log "P1 upsert 可将 sales_amount 覆盖为 NULL"
post "$OP" '{"sku_id":9005,"warehouse_id":9007,"sale_date":"2026-10-09","qty":"3","sales_amount":"33.00"}' > /dev/null
post "$OP" '{"sku_id":9005,"warehouse_id":9007,"sale_date":"2026-10-09","qty":"3"}' > /dev/null
chk "P1-amount为NULL" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND warehouse_id=9007 AND sku_id=9005 AND sale_date='2026-10-09' AND sales_amount IS NULL")"

sep; log "P2 pageSize=201 → COM-0007（不静默夹取）"
G=$(getq "/api/v1/sales/daily?pageSize=201" "$OWN")
chk "P2-code" "COM-0007" "$(code "${G%|*}")"
chk "P2-http" "400" "${G##*|}"

sep; log "P3 分页响应字段名 list（非 items）"
G=$(getq "/api/v1/sales/daily" "$OWN")
chk "P3-has-list" "1" "$(echo "${G%|*}" | grep -c '"list":')"
chk "P3-has-total" "1" "$(echo "${G%|*}" | grep -c '"total":')"
chk "P3-no-items" "0" "$(echo "${G%|*}" | grep -c '"items":')"

sep; log "P4 product_id 冗余列 == sku.product_id"
chk "P4-不一致行数" "0" "$(sql "SELECT COUNT(*) FROM sales_daily s JOIN skus k ON k.id=s.sku_id WHERE s.product_id<>k.product_id")"
chk "P4-样例值" "9001" "$(sql "SELECT product_id FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND sale_date='2026-10-03'")"

sep; log "P5 审计 change_summary 记被覆盖行数（受控两连写：新键→覆盖 0 行；同键再写→覆盖 1 行）"
post "$OP" '{"sku_id":9003,"warehouse_id":9007,"sale_date":"2026-10-10","qty":"1"}' > /dev/null
S5A=$(sql "SELECT change_summary FROM audit_logs WHERE action='SALES_DAILY_UPSERT' ORDER BY id DESC LIMIT 1")
post "$OP" '{"sku_id":9003,"warehouse_id":9007,"sale_date":"2026-10-10","qty":"2"}' > /dev/null
S5B=$(sql "SELECT change_summary FROM audit_logs WHERE action='SALES_DAILY_UPSERT' ORDER BY id DESC LIMIT 1")
log "P5-首次摘要=[$S5A]"
log "P5-覆盖摘要=[$S5B]"
chk "P5a-新键覆盖0行" "1" "$(echo "$S5A" | grep -c '覆盖 0 行 / 共 1 行')"
chk "P5b-同键覆盖1行" "1" "$(echo "$S5B" | grep -c '覆盖 1 行 / 共 1 行')"
chk "P5c-摘要格式全合规" "0" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT' AND change_summary NOT LIKE 'upsert 日销售：覆盖 %'")"

sep; log "P6 team_id 由租户线自动补齐（调用方不传 team_id）"
chk "P6-落库team_id" "7" "$(sql "SELECT team_id FROM sales_daily WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND sale_date='2026-10-03'")"
chk "P6-越界team_id" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id NOT IN (7,8)")"

sep; log "P7 GET 零副作用"
N1=$(sql "SELECT COUNT(*) FROM sales_daily")
getq "/api/v1/sales/daily" "$OWN" > /dev/null
getq "/api/v1/sales/daily?groupBy=date" "$OWN" > /dev/null
N2=$(sql "SELECT COUNT(*) FROM sales_daily")
chk "P7-行数不变" "$N1" "$N2"

sep; log "P8 读隔离：团队 8 的 owner8 看不到团队 7 数据"
T7=$(getq "/api/v1/sales/daily?pageSize=200" "$OWN")
T8=$(getq "/api/v1/sales/daily?pageSize=200" "$OTH")
T7N=$(echo "${T7%|*}" | grep -o '"total":[0-9]*' | head -1 | sed 's/"total"://')
T8N=$(echo "${T8%|*}" | grep -o '"total":[0-9]*' | head -1 | sed 's/"total"://')
log "P8 total: team7=$T7N team8=$T8N"
chk "P8-团队8为空" "0" "$T8N"
chk "P8-团队7非空" "1" "$([ "${T7N:-0}" -gt 0 ] && echo 1 || echo 0)"

sep
TOTAL=$(grep -c "^PASS \|^FAIL " "$LOG")
log "B8_CHECK_TOTAL=$TOTAL"
log "B8_FAIL_COUNT=$FAIL"
if [ "$FAIL" != "0" ]; then log "FAILED LIST:"; echo -e "$FAILED_LIST" | tee -a "$LOG"; fi
log "=== B8 EVIDENCE DONE ==="
[ "$FAIL" = "0" ] || exit 6
exit 0
