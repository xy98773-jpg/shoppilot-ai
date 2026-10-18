#!/usr/bin/env bash
# B12 复核用：自建 fat jar + 独立 MySQL(3364) 起停包装器（生命周期由 trap 管）
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
APP_PORT="${APP_PORT:-18280}"
OUT="$ROOT/.workbuddy-ai/rb-b12"
mkdir -p "$OUT/storage"
APP_LOG="$OUT/app.log"
: > "$APP_LOG"

cleanup(){ [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null; }
trap cleanup EXIT

export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3364/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=shoppilot
export SPRING_DATASOURCE_PASSWORD=changeme_app_password
export JWT_SECRET="rb-b12-independent-review-secret-0123456789"
export IMPORT_STORAGE_DIR="$OUT/storage"

java -jar "$JAR" --server.port="$APP_PORT" >> "$APP_LOG" 2>&1 &
APP_PID=$!
echo "APP_PID=$APP_PID port=$APP_PORT"

for i in $(seq 1 90); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { echo "HEALTH OK after ${i}s"; break; }
  sleep 1
done
c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health")
if [ "$c" != "200" ]; then echo "FATAL health=$c"; tail -40 "$APP_LOG"; exit 5; fi

echo "=== 种子 ==="
# 夹具幂等：seed-b95 只清主数据，不清数据类表；上一轮留下的 sales_daily 会挡住 DELETE skus（fk_sales_sku RESTRICT）
docker exec -i rb-b12-mysql mysql -uroot -proot --default-character-set=utf8mb4 shoppilot -e "
DELETE FROM sales_daily; DELETE FROM import_row_errors; DELETE FROM import_batches;" 2>/dev/null
docker exec -i rb-b12-mysql mysql -uroot -proot --default-character-set=utf8mb4 shoppilot < "$ROOT/backend-java/tools/evi/seed-b95.sql" && echo "SEED OK"

echo "=== 探针 ==="
OUT_DIR="$OUT" APP_PORT="$APP_PORT" bash "$ROOT/backend-java/tools/rb-b12/run-rb-b12.sh"
RC=$?
echo "PROBE_RC=$RC"
exit "$RC"
