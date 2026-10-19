#!/usr/bin/env bash
# =============================================================================
# B14.1（M4-T02 收尾）真栈取证：V1.0.9 迁移在真实 MySQL 上的最终 DDL
# -----------------------------------------------------------------------------
# 做法：自建 mysql:8.0.36 容器 + 跑 backend-java fat jar（Spring Boot 启动即执行 Flyway），
#       等服务健康后从库里导出 SHOW CREATE TABLE 原文，并对约束/索引/审计字段跑断言。
#   为什么用 fat jar 而不是客户端手工执行 SQL：只有「应用真实启动路径上的 Flyway」
#   才能证明 V1.0.9 真的被应用（而不是脚本里另写了一遍 DDL）。
#   与 Testcontainers 单测的区别：单测库是随机端口的临时库；本脚本落独立端口 + 独立容器，
#   供复核者 docker exec 复看。
# 生命周期：trap cleanup EXIT（容器与进程必被回收，不残留）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
MYSQL_NAME="rb-b14-mysql"
MYSQL_PORT="${MYSQL_PORT:-3380}"
APP_PORT="${APP_PORT:-18320}"
OUT_DIR="$ROOT/backend-java/tools/rb-b14"
EVI="$OUT_DIR/evidence-rb-b14.txt"
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
  log "FATAL: fat jar 不存在：$JAR"
  log "（verify 未跑到 spring-boot:repackage 就不会有 jar；先让 verify 完整通过再跑本脚本）"
  exit 4
fi

# ---------------------------------------------------------------- 端口占用自检
log "=== 端口自检 ==="
for p in "$MYSQL_PORT" "$APP_PORT"; do
  if netstat -ano | grep -E ":${p}\b" >/dev/null 2>&1; then
    log "FATAL: 端口 $p 已被占用，换端口后重跑（MYSQL_PORT / APP_PORT）"
    exit 6
  fi
  log "PORT_FREE $p"
done

# ---------------------------------------------------------------- 起 MySQL
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
log "APP_PORT=$APP_PORT"

# ---------------------------------------------------------------- 起应用（Flyway 随启动执行）
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b14-independent-review-secret-0123456789"
export IMPORT_STORAGE_DIR="$OUT_DIR/storage"
APP_LOG="$OUT_DIR/app.log"
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

# ---------------------------------------------------------------- 查询助手（stderr 引走，不污染证据文件）
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N -e "$1" 2>/dev/null | tr -d '\r'; }

# ---------------------------------------------------------------- ★ V3-1：迁移链
log "=== flyway_schema_history（SELECT version, success ORDER BY installed_rank）==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;" 2>/dev/null | tee -a "$EVI"
chk "V109-Success" "1" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE version='1.0.9' AND success=1;")"
chk "迁移总数" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history;")"
chk "success=1 条数" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"

# ---------------------------------------------------------------- ★ V3-2：SHOW CREATE TABLE 原文
log "=== SHOW CREATE TABLE analysis_runs ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SHOW CREATE TABLE analysis_runs\G" 2>/dev/null | tee -a "$EVI"
log "=== SHOW CREATE TABLE analysis_items ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  -e "SHOW CREATE TABLE analysis_items\G" 2>/dev/null | tee -a "$EVI"

# ---------------------------------------------------------------- 结构断言（防「只贴结论」）
sep; log "=== 结构断言 ==="
chk "analysis_runs 二级索引数" "3" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name LIKE 'idx%';")"
chk "analysis_runs 索引名集合" "idx_run_team_created,idx_run_team_status,idx_run_timeout" "$(q "SELECT GROUP_CONCAT(DISTINCT index_name ORDER BY index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name LIKE 'idx%';")"
chk "analysis_items 二级索引数" "2" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name LIKE 'idx%';")"
chk "analysis_items 索引名集合" "idx_ai_team_run,idx_ai_team_sku_time" "$(q "SELECT GROUP_CONCAT(DISTINCT index_name ORDER BY index_name) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name LIKE 'idx%';")"
chk "未建 idx_ai_team_run_risk（R102）" "0" "$(q "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='idx_ai_team_run_risk';")"
chk "idx_run_timeout 最左列=status（R103）" "status" "$(q "SELECT column_name FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='idx_run_timeout' AND seq_in_index=1;")"
chk "idx_run_team_status 最左列=team_id" "team_id" "$(q "SELECT column_name FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND index_name='idx_run_team_status' AND seq_in_index=1;")"
chk "idx_ai_team_run 最左列=team_id" "team_id" "$(q "SELECT column_name FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='idx_ai_team_run' AND seq_in_index=1;")"
chk "uk_ai_run_sku_wh 列序（R89 不带 team_id）" "run_id,sku_id,warehouse_id" "$(q "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics WHERE table_schema='shoppilot' AND table_name='analysis_items' AND index_name='uk_ai_run_sku_wh';")"
chk "analysis_runs 唯一约束数" "2" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND constraint_type='UNIQUE';")"
chk "analysis_items 唯一约束数" "1" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_items' AND constraint_type='UNIQUE';")"
chk "analysis_runs CHECK 数" "5" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND constraint_type='CHECK';")"
chk "analysis_items CHECK 数" "6" "$(q "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='shoppilot' AND table_name='analysis_items' AND constraint_type='CHECK';")"
chk "analysis_items 外键数" "3" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items';")"
chk "analysis_items 外键全 RESTRICT" "3" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_items' AND delete_rule='RESTRICT';")"
chk "analysis_runs 外键数（§5.14：无外键）" "0" "$(q "SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema='shoppilot' AND table_name='analysis_runs';")"
chk "analysis_runs.team_id 默认值（须空）" "" "$(q "SELECT COALESCE(column_default,'') FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='team_id';")"
chk "analysis_items.team_id 默认值（须空）" "" "$(q "SELECT COALESCE(column_default,'') FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='team_id';")"
chk "analysis_runs 无 version 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='version';")"
chk "analysis_items 无 updated_at 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='updated_at';")"
chk "analysis_items 无 updated_by 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='updated_by';")"
chk "analysis_items 无 version 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='version';")"
chk "cv 类型 = decimal(18,4)" "decimal(18,4)" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='cv';")"
chk "progress 类型 = tinyint unsigned" "tinyint unsigned" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='progress';")"
chk "llm_explanation 类型 = text" "text" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items' AND column_name='llm_explanation';")"
chk "params_json 类型 = json" "json" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='params_json';")"
chk "timeout_at 类型 = datetime(3)" "datetime(3)" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='timeout_at';")"
chk "idempotency_key 类型 = varchar(128)" "varchar(128)" "$(q "SELECT column_type FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs' AND column_name='idempotency_key';")"
chk "analysis_runs 列数（30 业务 + 4 审计）" "34" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_runs';")"
chk "analysis_items 列数（33 业务 + 2 审计）" "35" "$(q "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='shoppilot' AND table_name='analysis_items';")"

sep
log "RB14_CHECK_TOTAL=$((PASS+FAIL))"
log "RB14_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB14_RESULT=ALL PASS"; else log "RB14_RESULT=HAS FAILURES"; fi
exit 0
