#!/usr/bin/env bash
# B7 真栈 fat-jar 取证脚本（独立库，与 verify/Testcontainers 完全隔离）
# 流程：独立 MySQL(3319) → fat jar(:18099) → seed → E1~E4 curl 取证 → 收尾 DROP
set -ux
EVI_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$EVI_DIR/../.." && pwd)"
JAR=$(ls "$REPO"/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$EVI_DIR/evidence-B7.log"
MYSQL_CT="b7mysql-b7"
MYSQL_PORT=3319
APP_PORT=18099
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
PW="1234"
BASE="http://localhost:$APP_PORT"

: > "$LOG"
log(){ echo "$*" | tee -a "$LOG"; }

if [ -z "$JAR" ]; then log "FATAL: app jar not found at $REPO/app/target/"; exit 2; fi
log "JAR=$JAR"

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; pkill -f "server.port=$APP_PORT" 2>/dev/null || true; }
trap cleanup EXIT

# 1) 独立 MySQL 8.0.36
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$LOG" 2>&1
log "MySQL container started, waiting for container running..."
for i in $(seq 1 30); do
  st=$(docker inspect -f '{{.State.Running}}' "$MYSQL_CT" 2>/dev/null || echo false)
  [ "$st" = "true" ] && break
  sleep 2
done
log "container running=$st"
docker logs --tail 20 "$MYSQL_CT" >> "$LOG" 2>&1
log "Waiting for MySQL TCP ready (inside container)..."
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MySQL READY=$READY"
[ "$READY" = "1" ] || { docker logs --tail 40 "$MYSQL_CT" >> "$LOG" 2>&1; log "MySQL not ready"; exit 3; }

# 2) fat jar
log "Starting fat jar on :$APP_PORT ..."
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID"

log "Waiting for health ..."
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  if [ "$code" = "200" ]; then break; fi
  sleep 2
done
curl -s --noproxy '*' "$BASE/api/v1/health" | tee -a "$LOG"; echo >> "$LOG"

# 3) seed
log "=== seeding ==="
docker exec -i "$MYSQL_CT" mysql -h127.0.0.1 -uroot -proot shoppilot < "$EVI_DIR/seed-b7.sql" >> "$LOG" 2>&1 && log "seed ok"

# 4) helpers
token(){ # username
  curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'
}
OP=$(token operator01); OWN=$(token owner01); ADM=$(token admin01); VIEW=$(token viewer01); APP=$(token approver01)
log "tokens obtained: op=${OP:0:12}... own=${OWN:0:12}..."

echo_separator(){ log "================================================================"; }

# E1 GET /balances + /transactions + /alerts (operator)
echo_separator; log "E1-a GET /inventory/balances (operator)"
curl -s --noproxy '*' "$BASE/api/v1/inventory/balances" -H "Authorization: Bearer $OP" | tee -a "$LOG"; echo >> "$LOG"
echo_separator; log "E1-b GET /inventory/transactions (operator)"
curl -s --noproxy '*' "$BASE/api/v1/inventory/transactions" -H "Authorization: Bearer $OP" | tee -a "$LOG"; echo >> "$LOG"
echo_separator; log "E1-c GET /inventory/alerts (operator)"
curl -s --noproxy '*' "$BASE/api/v1/inventory/alerts" -H "Authorization: Bearer $OP" | tee -a "$LOG"; echo >> "$LOG"

# E2 角色矩阵（R59）
echo_separator; log "E2-a VIEWER POST /adjustments → 期望 403 TEAM-2003"
curl -s --noproxy '*' -o /tmp/b7r.json -w "HTTP=%{http_code}\n" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $VIEW" -H "Content-Type: application/json" -H "Idempotency-Key: v1" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"12.000","reason":"盘点"}'
cat /tmp/b7r.json | tee -a "$LOG"; echo >> "$LOG"
echo_separator; log "E2-b OPERATOR POST /adjustments MANUAL_ADJUST → 期望 403 INV-5007"
curl -s --noproxy '*' -o /tmp/b7r.json -w "HTTP=%{http_code}\n" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OP" -H "Content-Type: application/json" -H "Idempotency-Key: v2" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"MANUAL_ADJUST","qty_delta":"3.000","reason":"手工"}'
cat /tmp/b7r.json | tee -a "$LOG"; echo >> "$LOG"
echo_separator; log "E2-c OWNER POST /adjustments COUNT_ADJUST → 期望 201"
curl -s --noproxy '*' -o /tmp/b7r.json -w "HTTP=%{http_code}\n" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" -H "Idempotency-Key: v3" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"15.000","reason":"盘点"}'
cat /tmp/b7r.json | tee -a "$LOG"; echo >> "$LOG"

# E3 冲销链路：先 ADMIN 建一笔调整，再 reverse
echo_separator; log "E3-a ADMIN POST /adjustments COUNT_ADJUST → 201, 取 txn id"
RESP=$(curl -s --noproxy '*' -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -H "Idempotency-Key: v4" \
  -d '{"warehouse_id":9004,"sku_id":9006,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"9.000","reason":"盘点冲销前"}')
echo "$RESP" | tee -a "$LOG"; echo >> "$LOG"
TXN_ID=$(echo "$RESP" | grep -o '"transaction_id":[0-9]*' | head -1 | sed 's/"transaction_id"://')
log "TXN_ID=$TXN_ID"
echo_separator; log "E3-b POST /transactions/{id}/reverse (owner) → 期望 200"
curl -s --noproxy '*' -o /tmp/b7r.json -w "HTTP=%{http_code}\n" -X POST "$BASE/api/v1/inventory/transactions/$TXN_ID/reverse" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" -H "Idempotency-Key: rev1" \
  -d '{"reason":"盘点回滚"}'
cat /tmp/b7r.json | tee -a "$LOG"; echo >> "$LOG"

# E4 策略 upsert 幂等
echo_separator; log "E4-a PUT /replenishment-policies (owner) → 200, 取 policy id"
R4=$(curl -s --noproxy '*' -X PUT "$BASE/api/v1/replenishment-policies" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" \
  -d '{"sku_id":9003,"warehouse_id":9004,"lead_time_days":5,"coverage_days":7,"safety_stock_qty":"8.000","is_enabled":true,"remark":"B7 evidence"}')
echo "$R4" | tee -a "$LOG"; echo >> "$LOG"
PID4=$(echo "$R4" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
log "POLICY_ID=$PID4"
echo_separator; log "E4-b 同内容再 PUT → 期望 200 且同一 id（幂等）"
R4b=$(curl -s --noproxy '*' -X PUT "$BASE/api/v1/replenishment-policies" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" \
  -d '{"sku_id":9003,"warehouse_id":9004,"lead_time_days":5,"coverage_days":7,"safety_stock_qty":"8.000","is_enabled":true,"remark":"B7 evidence"}')
echo "$R4b" | tee -a "$LOG"; echo >> "$LOG"
PID4b=$(echo "$R4b" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
if [ "$PID4" = "$PID4b" ]; then log "E4 IDEMPOTENT OK (id=$PID4)"; else log "E4 MISMATCH $PID4 != $PID4b"; fi

echo_separator; log "=== EVIDENCE DONE ==="
