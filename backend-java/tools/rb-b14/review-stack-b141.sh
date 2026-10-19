#!/usr/bin/env bash
# =============================================================================
# B14.1 独立复核真栈（项目负责人侧）—— M4-T02 V1.0.9 analysis 迁移最终 DDL
# -----------------------------------------------------------------------------
# 与开发 tools/rb-b14/run-rb-b14.sh 的区别（本脚本是复核，不是复述）：
#   · 端口/容器名全部不同：MySQL 3390 / 应用 18320+10=18330 / 容器 rb-b141-review-mysql；
#   · 断言集独立编写：开发 33 条 + 复核新增 ~17 条（uk 完整列序、外键 UPDATE_RULE、
#     auto_increment、字符集/排序规则、fat jar 内迁移命中、迁移链顺序串等）；
#   · 开发已覆盖的关键项（索引数/最左列/CHECK 数/team_id 无默认值/cv 精度等）交叉重跑，
#     双跑一致才采信——「两套独立脚本同一结论」比「一套脚本跑两遍」更接近独立取证。
# 做法与开发相同（这是 SSOT 要求）：自建 mysql:8.0.36 + backend-java fat jar 真实启动，
#   让 Flyway 走应用真实启动路径执行 V1.0.9，再对 information_schema 断言。
# 生命周期：trap cleanup EXIT（容器与进程必被回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
MYSQL_NAME="rb-b141-review-mysql"
MYSQL_PORT="${MYSQL_PORT:-3390}"
APP_PORT="${APP_PORT:-18330}"
OUT_DIR="$ROOT/backend-java/tools/rb-b14"
EVI="$OUT_DIR/evidence-rb-b141-review.txt"
APP_LOG="$OUT_DIR/app-b141-review.log"
mkdir -p "$OUT_DIR/storage"

PASS=0; FAIL=0
: > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }

# chk <名称> <期望> <实际>
chk(){
  local name="$1" exp="$2" act="$3"
  if [ "$exp" = "$act" ]; then
    PASS=$((PASS+1)); log "[PASS] $name => $act"
  else
    FAIL=$((FAIL+1)); log "[FAIL] $name => expected [$exp] but was [$act]"
  fi
}

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

# ---------------------------------------------------------------- 前置：jar 必须存在
if [ ! -f "$JAR" ]; then
  log "FATAL: fat jar 不存在：$JAR（先让 mvn clean verify 完整通过）"
  exit 4
fi

# ---------------------------------------------------------------- 端口占用自检
log "=== 端口自检 ==="
for p in "$MYSQL_PORT" "$APP_PORT"; do
  if netstat -ano | grep -E ":${p}\b" >/dev/null 2>&1; then
    log "FATAL: 端口 $p 已被占用，换端口重跑（MYSQL_PORT / APP_PORT）"
    exit 6
  fi
  log "PORT_FREE $p"
done

# ---------------------------------------------------------------- 起 MySQL（3390，与开发 3380 不同）
log "=== 起独立 MySQL（$MYSQL_NAME，$MYSQL_PORT:3306）==="
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" \
  -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
MYSQL_READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1; then
    MYSQL_READY=1; log "MYSQL_READY=1"; break
  fi
  sleep 2
done
[ "$MYSQL_READY" = "1" ] || { log "FATAL: MySQL 未就绪"; exit 5; }

log "JAR=$(basename "$JAR")"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"
log "（开发证据里的 SHA256 是 2034937c…；两者不同属正常——本复核 jar 由本机 verify 重新打出， numerically 比对以本机为准）"
log "APP_PORT=$APP_PORT"

# ---------------------------------------------------------------- 起应用（Flyway 随启动执行）
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b141-independent-review-secret-0123456789"
export IMPORT_STORAGE_DIR="$OUT_DIR/storage"
: > "$APP_LOG"

java -jar "$JAR" --server.port="$APP_PORT" >> "$APP_LOG" 2>&1 &
APP_PID=$!

JAVA_UP=0
for i in $(seq 1 120); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  if [ "$c" = "200" ]; then JAVA_UP=1; log "JAVA_UP=1 (after ${i}s)"; break; fi
  sleep 1
done
if [ "$JAVA_UP" != "1" ]; then
  log "FATAL: 应用未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5
fi

# ---------------------------------------------------------------- 查询助手
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N -e "$1" 2>/dev/null | tr -d '\r'; }

# ================================================================ A. 迁移链
log "=== flyway_schema_history（SELECT version, success ORDER BY installed_rank）==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;" 2>/dev/null | tee -a "$EVI"
chk "A1 V1.0.9 success=1 条数" "1" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE version='1.0.9' AND success=1;")"
chk "A2 迁移总数" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history;")"
chk "A3 success=1 条数" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"
chk "A4 version 按 installed_rank 顺序串" "1.0.0,1.0.1,1.0.2,1.0.3,1.0.4,1.0.5,1.0.6,1.0.7,1.0.8,1.0.9" \
  "$(q "SELECT GROUP_CONCAT(version ORDER BY installed_rank) FROM shoppilot.flyway_schema_history;")"

# ================================================================ B. analysis_runs
log "=== SHOW CREATE TABLE analysis_runs ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SHOW CREATE TABLE analysis_runs\G" 2>/dev/null | tee -a "$EVI"
chk "B1 uk_run_team_no 列序" "team_id,run_no" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='uk_run_team_no';")"
chk "B2 uk_run_team_idem 列序" "team_id,idempotency_key" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='uk_run_team_idem';")"
chk "B3 idx_run_team_status 完整列序" "team_id,status,created_at" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='idx_run_team_status';")"
chk "B4 idx_run_team_created 完整列序" "team_id,created_at" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='idx_run_team_created';")"
chk "B5 idx_run_timeout 完整列序（R103：status 最左）" "status,timeout_at" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='idx_run_timeout';")"
chk "B6 runs 二级索引(idx_)数" "3" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name LIKE 'idx%';")"
chk "B7 runs 外键数（§5.14 无外键）" "0" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_runs';")"
chk "B8 runs.team_id NOT NULL" "NO" "$(q "SELECT is_nullable FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='team_id';")"
chk "B9 runs.team_id 无 DEFAULT" "" "$(q "SELECT COALESCE(column_default,'') FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='team_id';")"
chk "B10 runs 列数（30 业务 + 4 审计）" "34" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs';")"
chk "B11 runs 有 updated_at/updated_by（集合 A）" "2" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name IN ('updated_at','updated_by');")"
chk "B12 runs.id AUTO_INCREMENT" "auto_increment" "$(q "SELECT extra FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='id';")"
chk "B13 runs CHECK 数" "5" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND constraint_type='CHECK';")"
chk "B14 runs 唯一约束数" "2" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND constraint_type='UNIQUE';")"
chk "B15 runs 字符集" "utf8mb4" "$(q "SELECT table_collation FROM information_schema.tables WHERE table_schema='shoppilot' AND table_name='analysis_runs';" | sed 's/_.*//')"
chk "B16 runs 无 version 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='version';")"

# ================================================================ C. analysis_items
log "=== SHOW CREATE TABLE analysis_items ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SHOW CREATE TABLE analysis_items\G" 2>/dev/null | tee -a "$EVI"
chk "C1 items 二级索引(idx_)数" "2" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name LIKE 'idx%';")"
chk "C2 uk_ai_run_sku_wh 列序（R89 不带 team_id）" "run_id,sku_id,warehouse_id" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='uk_ai_run_sku_wh';")"
chk "C3 idx_ai_team_run 完整列序" "team_id,run_id,risk_level" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='idx_ai_team_run';")"
chk "C4 idx_ai_team_sku_time 完整列序" "team_id,sku_id,created_at" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='idx_ai_team_sku_time';")"
chk "C5 items 外键数" "3" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items';")"
chk "C6 items 外键 delete_rule 全 RESTRICT" "3" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items' AND delete_rule='RESTRICT';")"
chk "C7 items 外键 update_rule 全 RESTRICT" "3" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items' AND update_rule='RESTRICT';")"
chk "C8 items 外键目标表集合" "analysis_runs,skus,warehouses" "$(q "SELECT GROUP_CONCAT(DISTINCT referenced_table_name ORDER BY referenced_table_name) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items';")"
chk "C9 items.team_id NOT NULL" "NO" "$(q "SELECT is_nullable FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='team_id';")"
chk "C10 items.cv 精度（★ 4 位）" "decimal(18,4)" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='cv';")"
chk "C11 items 无 updated_at/updated_by/version（合计 0）" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name IN ('updated_at','updated_by','version');")"
chk "C12 items 列数（33 业务 + 2 审计）" "35" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items';")"
chk "C13 items CHECK 数" "6" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_items' AND constraint_type='CHECK';")"
chk "C14 items.created_at NOT NULL + 默认 CURRENT_TIMESTAMP(3)" "datetime(3)|NO|CURRENT_TIMESTAMP(3)" "$(q "SELECT CONCAT_WS('|',column_type,is_nullable,column_default) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='created_at';")"
chk "C15 InnoDB 外键自动索引（引擎行为，记录在案）" "2" "$(q "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name IN ('fk_ai_sku','fk_ai_warehouse');")"
chk "C16 items.id AUTO_INCREMENT" "auto_increment" "$(q "SELECT extra FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='id';")"

# ================================================================ D. fat jar 装配
log "=== fat jar 内迁移文件命中 ==="
chk "D1 jar 内 db/migration/V1.0.9__analysis.sql 命中" "1" "$(unzip -l "$JAR" | grep -c 'db/migration/V1.0.9__analysis.sql')"

# ================================================================ E. 全仓 CASCADE 口径（G10 复核）
log "=== 全仓 DDL 级联口径（对库内全部表）==="
chk "E1 全库 DELETE CASCADE 外键（应恰 1：fk_rt_user）" "1" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND delete_rule='CASCADE';")"
chk "E2 库内 analysis 两表外键 CASCADE 数（应 0）" "0" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name IN ('analysis_runs','analysis_items') AND delete_rule='CASCADE';")"

sep
log "RB141_CHECK_TOTAL=$((PASS+FAIL))"
log "RB141_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB141_RESULT=ALL PASS"; else log "RB141_RESULT=HAS FAILURES"; fi
[ "$FAIL" = "0" ] || exit 1
exit 0
