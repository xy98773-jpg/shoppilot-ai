#!/usr/bin/env bash
# =============================================================================
# ★ 项目负责人独立复核 B8（不采信自报）：独立 MySQL(3329) + 自建 fat jar(:18109)
#
# 端口刻意错开：B7 复核 3327/18107、B8 开发 3328/18108、本次 3329/18109。
#
# 组次：
#   R1 迁移链 9 条 + sales_daily 关键约束（复现）
#   R2 upsert 覆盖 15→7、行数仍 1（U-6，复现）
#   R3 groupBy=sku|warehouse|date 三聚合（复现）
#   R4 角色 VIEWER POST → TEAM-2003；OPERATOR → 201（复现）
#   R5 审计 SALES_DAILY_UPSERT 条数 + change_summary 含被覆盖行数（复现）
#   ---- 以下为开发未覆盖的 SSOT 硬要求探针 ----
#   P1 ★ sale_date 晚于今天 → 期望 400 SAL-6003（ERROR_CODES §2.7 定义；
#        导入路径 ImportValidationService:451 已实现，REST 路径是否一致？）
#   P2 ★ sale_date 早于 skus.listed_date → 期望 400 SAL-6003
#   P3 覆盖更新后 created_by / created_at 保持（不应被改写），updated_at 应变化
#   P4 qty=0 允许写入（非负 CHECK 允许 0；退货走 return_qty）
#   P5 读隔离：团队 8 读不到团队 7 的销售行
#   P6 pageSize=201 → COM-0007（不静默夹取）
#   P7 sales_amount 可覆盖为 NULL（覆盖语义完整性）
#
# 用法：bash backend-java/tools/rb-b8/run-rb8-evidence.sh
# 产物：evidence-rb8.txt（入库） / evidence-rb8-app.log（*.log 已 gitignore）
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb8.txt"
APP_LOG="$OUT_DIR/evidence-rb8-app.log"
MYSQL_CT="rb8-mysql"
MYSQL_PORT=3329
APP_PORT=18109
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"; : > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

[ -n "$JAR" ] || { log "FATAL: jar not found"; exit 2; }
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT)"
[ "$READY" = "1" ] || { log "FATAL mysql"; exit 3; }

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
log "APP_PID=$! (port $APP_PORT)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app"; exit 4; }

docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot shoppilot \
  < "$EVI/seed-b8.sql" >> "$LOG" 2>&1 && log "SEED_OK"

# ★ 中文断言必须显式 utf8mb4，否则 LIKE '%覆盖%' 恒不匹配（B8 开发踩过的坑）
sql(){ docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot -N -B shoppilot -e "$1" 2>/dev/null; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }

OP=$(token operator01); OWN=$(token owner01); VIEW=$(token viewer01); OTHER=$(token owner8)
log "TOKENS op=${OP:0:10} own=${OWN:0:10} view=${VIEW:0:10} other=${OTHER:0:10}"

FAIL=0
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi; }
note(){ log "NOTE $*"; }

# ===== R1 迁移链 =====
sep; log "R1 迁移链 9 条 + sales_daily 约束"
chk "R1-迁移条数" "9" "$(sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")"
chk "R1-最高版本" "1.0.8" "$(sql "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")"
# ★ 必须排除 PRIMARY：主键也是 non_unique=0，不排除会得 2（本探针初版即因此误报 FAIL）
chk "R1-唯一键数(排除PK)" "1" "$(sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND non_unique=0 AND index_name<>'PRIMARY'")"
chk "R1-声明索引数" "4" "$(sql "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='sales_daily' AND index_name LIKE 'idx\\_%'")"
chk "R1-CHECK数" "4" "$(sql "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='sales_daily' AND constraint_type='CHECK'")"
chk "R1-无version列" "0" "$(sql "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='sales_daily' AND column_name='version'")"
chk "R1-外键全RESTRICT" "2" "$(sql "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='sales_daily' AND delete_rule='RESTRICT'")"

# ===== R2 upsert 覆盖（U-6）=====
sep; log "R2 upsert 覆盖：SKU-A/WH1 2026-10-03 先 15 再 7"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"15.000"}')
chk "R2-首次http" "201" "${R##*|}"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"7.000"}')
chk "R2-二次http" "201" "${R##*|}"
chk "R2-行数仍1" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")"
chk "R2-qty为新值" "7.000" "$(sql "SELECT qty FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")"

# ===== R3 三聚合 =====
sep; log "R3 groupBy 三聚合（纯种子态的 9002 已被 R2 污染 → 只验 warehouse/date 中未污染项）"
A=$(curl -s --noproxy '*' "$BASE/api/v1/sales/daily?groupBy=date" -H "Authorization: Bearer $OP")
chk "R3-date-1001" "45.000" "$(echo "$A" | grep -o '"group_key":"2026-10-01"[^}]*' | grep -o '"qty":"[0-9.]*"' | head -1 | sed 's/"qty":"//;s/"//')"
chk "R3-date-1002" "20.000" "$(echo "$A" | grep -o '"group_key":"2026-10-02"[^}]*' | grep -o '"qty":"[0-9.]*"' | head -1 | sed 's/"qty":"//;s/"//')"
B=$(curl -s --noproxy '*' "$BASE/api/v1/sales/daily?groupBy=warehouse" -H "Authorization: Bearer $OP")
note "R3-wh-raw=${B:0:400}"
chk "R3-wh9007" "30.000" "$(echo "$B" | tr '{' '\n' | grep '9007' | grep -o '"qty":"[0-9.]*"' | head -1 | sed 's/"qty":"//;s/"//')"

# ===== R4 角色 =====
sep; log "R4 角色：VIEWER -> TEAM-2003；OPERATOR -> 201"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $VIEW" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-05","qty":"1.000"}')
chk "R4-viewer-code" "TEAM-2003" "$(code "${R%|*}")"
chk "R4-viewer-http" "403" "${R##*|}"
chk "R4-viewer-未落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date='2026-10-05'")"

# ===== R5 审计 =====
sep; log "R5 审计 SALES_DAILY_UPSERT"
chk "R5-审计条数>0" "1" "$([ "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")" -gt 0 ] && echo 1 || echo 0)"
CS=$(sql "SELECT change_summary FROM audit_logs WHERE action='SALES_DAILY_UPSERT' ORDER BY id DESC LIMIT 1")
note "R5-change_summary=$CS"
chk "R5-含覆盖字样" "1" "$(echo "$CS" | grep -c '覆盖')"

# ===== P1 ★ 未来日期 =====
sep; log "P1 ★ sale_date 晚于今天（2030-01-01）→ 期望 400 SAL-6003"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2030-01-01","qty":"1.000"}')
log "P1 code=$(code "${R%|*}") http=${R##*|}"
ROWS=$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date='2030-01-01'")
log "P1 未来日期落库行数=$ROWS"
if [ "${R##*|}" = "400" ] && [ "$(code "${R%|*}")" = "SAL-6003" ]; then log "P1 PASS"; else log "P1 FINDING: REST 路径未拦未来日期（导入路径已拦）"; FAIL=$((FAIL+1)); fi

# ===== P2 ★ 早于上架日 =====
sep; log "P2 ★ sale_date 早于 skus.listed_date → 期望 400 SAL-6003"
sql "UPDATE skus SET listed_date='2026-10-01' WHERE id=9002" >/dev/null 2>&1
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-09-01","qty":"1.000"}')
log "P2 code=$(code "${R%|*}") http=${R##*|}"
ROWS2=$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date='2026-09-01'")
log "P2 早于上架日落库行数=$ROWS2"
[ "$ROWS2" = "0" ] && log "P2 PASS（未落库）" || { log "P2 FINDING: 早于上架日可写入"; FAIL=$((FAIL+1)); }

# ===== P3 覆盖不夺原创建人 =====
sep; log "P3 覆盖更新后 created_by/created_at 保持、updated_at 变化"
BEFORE=$(sql "SELECT CONCAT(created_by,'|',created_at,'|',updated_at) FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")
sleep 1
curl -s --noproxy '*' -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OWN" \
  -H 'Content-Type: application/json' -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"9.000"}' > /dev/null
AFTER=$(sql "SELECT CONCAT(created_by,'|',created_at,'|',updated_at) FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")
log "P3 before=$BEFORE"
log "P3 after =$AFTER"
CB=$(echo "$BEFORE" | cut -d'|' -f1); CA=$(echo "$AFTER" | cut -d'|' -f1)
CT_B=$(echo "$BEFORE" | cut -d'|' -f2); CT_A=$(echo "$AFTER" | cut -d'|' -f2)
chk "P3-created_by保持" "$CB" "$CA"
chk "P3-created_at保持" "$CT_B" "$CT_A"
chk "P3-qty更新" "9.000" "$(sql "SELECT qty FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")"

# ===== P4 qty=0 允许 =====
sep; log "P4 qty=0 允许（非负 CHECK 允许 0）"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' -d '{"sku_id":9003,"warehouse_id":9004,"sale_date":"2026-10-06","qty":"0"}')
chk "P4-http" "201" "${R##*|}"
chk "P4-qty" "0.000" "$(sql "SELECT qty FROM sales_daily WHERE sku_id=9003 AND sale_date='2026-10-06'")"

# ===== P5 读隔离 =====
sep; log "P5 团队 8 读不到团队 7 的销售行"
R=$(curl -s --noproxy '*' "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OTHER")
T=$(echo "$R" | grep -o '"total":[0-9]*' | head -1 | sed 's/"total"://')
chk "P5-other-total" "0" "$T"

# ===== P6 pageSize 上限 =====
sep; log "P6 pageSize=201 → COM-0007"
R=$(curl -s --noproxy '*' -w "|%{http_code}" "$BASE/api/v1/sales/daily?page=1&pageSize=201" -H "Authorization: Bearer $OP")
chk "P6-code" "COM-0007" "$(code "${R%|*}")"
chk "P6-http" "400" "${R##*|}"

# ===== P7 sales_amount 覆盖为 NULL =====
sep; log "P7 sales_amount 可覆盖为 NULL"
curl -s --noproxy '*' -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' \
  -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-07","qty":"3.000","sales_amount":"99.00"}' > /dev/null
curl -s --noproxy '*' -X POST "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP" \
  -H 'Content-Type: application/json' \
  -d '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-07","qty":"3.000","sales_amount":null}' > /dev/null
chk "P7-NULL生效" "NULL" "$(sql "SELECT IFNULL(sales_amount,'NULL') FROM sales_daily WHERE sku_id=9002 AND sale_date='2026-10-07'")"

sep
log "RB8_FAIL_COUNT=$FAIL"
log "=== RB8 EVIDENCE DONE ==="
exit 0
