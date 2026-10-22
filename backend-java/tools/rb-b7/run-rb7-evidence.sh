#!/usr/bin/env bash
# ★ 项目负责人独立复核 B7（不采信自报）：独立 MySQL(3327) + 自建 fat jar(:18107)
# 重点：① 复现 E1–E4 关键语义；② 新增探针 P1 策略 version 是否递增、P2 分页上限、
#       P3 异团队 CAT-3004、P4 二次冲销 INV-5009、P5 GET 零副作用
set -u
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$(cd "$(dirname "$0")" && pwd)/evidence-rb7.txt"
MYSQL_CT="rb7-mysql"
MYSQL_PORT=3327
APP_PORT=18107
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: jar not found"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA=$(sha256sum "$JAR" | cut -c1-64)"

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; pkill -f "server.port=$APP_PORT" 2>/dev/null || true; }
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
log "MYSQL_READY=$READY"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

docker exec -i "$MYSQL_CT" mysql -h127.0.0.1 -uroot -proot shoppilot < "$EVI/seed-b7.sql" >> "$LOG" 2>&1 && log "SEED_OK"

token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
OP=$(token operator01); OWN=$(token owner01); ADM=$(token admin01); VIEW=$(token viewer01)
log "TOKENS op=${OP:0:10} own=${OWN:0:10} adm=${ADM:0:10} view=${VIEW:0:10}"

sql(){ docker exec -i "$MYSQL_CT" mysql -h127.0.0.1 -uroot -proot -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }

FAIL=0
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi
}

# ===== 复现组 R：E1 三态预警 / E2 角色三岔口 / E3 冲销 / E4 策略幂等 =====
sep; log "R1 GET /inventory/alerts 三态"
A=$(curl -s --noproxy '*' "$BASE/api/v1/inventory/alerts" -H "Authorization: Bearer $OP")
log "ALERTS=$(echo "$A" | grep -o '"alert_type":"[A-Z]*"' | tr '\n' ' ')"
chk "R1-STOCKOUT"  "$(echo "$A" | grep -c 'STOCKOUT')" "1"
chk "R1-LOWSTOCK"  "$(echo "$A" | grep -c 'LOWSTOCK')" "1"
chk "R1-SLOWMOVING" "$(echo "$A" | grep -c 'SLOWMOVING')" "1"

sep; log "R2a VIEWER adjustments -> TEAM-2003"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $VIEW" -H "Content-Type: application/json" -H "Idempotency-Key: rb-v1" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"12.000","reason":"盘点"}')
chk "R2a-code" "TEAM-2003" "$(code "${R%|*}")"
chk "R2a-http" "403" "${R##*|}"

sep; log "R2b OPERATOR MANUAL_ADJUST -> INV-5007"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OP" -H "Content-Type: application/json" -H "Idempotency-Key: rb-v2" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"MANUAL_ADJUST","qty_delta":"3.000","reason":"手工"}')
chk "R2b-code" "INV-5007" "$(code "${R%|*}")"
chk "R2b-http" "403" "${R##*|}"

sep; log "R2c OWNER COUNT_ADJUST -> 201"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" -H "Idempotency-Key: rb-v3" \
  -d '{"warehouse_id":9004,"sku_id":9002,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"15.000","reason":"盘点"}')
chk "R2c-http" "201" "${R##*|}"
log "R2c-body=${R%|*}"

sep; log "R3 冲销链路：ADMIN 建流水 -> OWNER reverse"
R=$(curl -s --noproxy '*' -X POST "$BASE/api/v1/inventory/adjustments" \
  -H "Authorization: Bearer $ADM" -H "Content-Type: application/json" -H "Idempotency-Key: rb-v4" \
  -d '{"warehouse_id":9004,"sku_id":9006,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"9.000","reason":"冲销前"}')
TXN=$(echo "$R" | grep -o '"transaction_id":[0-9]*' | head -1 | sed 's/"transaction_id"://')
log "TXN_ID=$TXN"
BEFORE=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE sku_id=9006 AND warehouse_id=9004")
RV=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$TXN/reverse" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" -H "Idempotency-Key: rb-rev1" \
  -d '{"reason":"回滚"}')
chk "R3-http" "200" "${RV##*|}"
AFTER=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE sku_id=9006 AND warehouse_id=9004")
log "R3 on_hand BEFORE=$BEFORE AFTER=$AFTER"
chk "R3-回退" "5.000" "$AFTER"

sep; log "R4 策略 upsert 幂等"
P1=$(curl -s --noproxy '*' -X PUT "$BASE/api/v1/replenishment-policies" -H "Authorization: Bearer $OWN" \
  -H "Content-Type: application/json" \
  -d '{"sku_id":9003,"warehouse_id":9004,"lead_time_days":5,"coverage_days":7,"safety_stock_qty":"8.000","is_enabled":true,"remark":"rb"}')
PID=$(echo "$P1" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
P2=$(curl -s --noproxy '*' -X PUT "$BASE/api/v1/replenishment-policies" -H "Authorization: Bearer $OWN" \
  -H "Content-Type: application/json" \
  -d '{"sku_id":9003,"warehouse_id":9004,"lead_time_days":5,"coverage_days":7,"safety_stock_qty":"9.000","is_enabled":true,"remark":"rb2"}')
PID2=$(echo "$P2" | grep -o '"id":[0-9]*' | head -1 | sed 's/"id"://')
chk "R4-幂等同id" "$PID" "$PID2"
chk "R4-仅一行" "1" "$(sql "SELECT COUNT(*) FROM replenishment_policies WHERE sku_id=9003 AND warehouse_id=9004")"

# ===== 新增探针组 P =====
sep; log "P1 ★ 策略 version 是否递增（§7.4 表 A 要求 version）"
V=$(sql "SELECT version FROM replenishment_policies WHERE id=$PID")
log "P1 version(两次 upsert 后)=$V"
if [ "$V" = "0" ]; then log "P1 FINDING: version 未递增（乐观锁形同虚设）"; FAIL=$((FAIL+1)); else log "P1 PASS version=$V"; fi
BV=$(sql "SELECT version FROM inventory_balances WHERE sku_id=9006 AND warehouse_id=9004")
log "P1b 对照：inventory_balances version=$BV（B6 手写 version+1，应为 2）"
[ "$BV" = "2" ] && log "P1b PASS" || { log "P1b FAIL expected=2 actual=$BV"; FAIL=$((FAIL+1)); }

sep; log "P2 pageSize=201 -> COM-0007"
R=$(curl -s --noproxy '*' -w "|%{http_code}" "$BASE/api/v1/inventory/balances?page=1&pageSize=201" -H "Authorization: Bearer $OP")
chk "P2-code" "COM-0007" "$(code "${R%|*}")"
chk "P2-http" "400" "${R##*|}"

sep; log "P3 异团队 SKU（team8 sku）upsert 策略 -> CAT-3004"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/replenishment-policies" -H "Authorization: Bearer $OWN" \
  -H "Content-Type: application/json" \
  -d '{"sku_id":9201,"warehouse_id":9004,"lead_time_days":5}')
log "P3 code=$(code "${R%|*}") http=${R##*|}"

sep; log "P4 二次冲销 -> INV-5009"
R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/inventory/transactions/$TXN/reverse" \
  -H "Authorization: Bearer $OWN" -H "Content-Type: application/json" -H "Idempotency-Key: rb-rev2" \
  -d '{"reason":"再冲一次"}')
log "P4 code=$(code "${R%|*}") http=${R##*|}"
chk "P4-code" "INV-5009" "$(code "${R%|*}")"

sep; log "P5 GET 零副作用：alerts 调两次，流水数不变"
N1=$(sql "SELECT COUNT(*) FROM inventory_transactions")
curl -s --noproxy '*' "$BASE/api/v1/inventory/alerts" -H "Authorization: Bearer $OP" > /dev/null
curl -s --noproxy '*' "$BASE/api/v1/inventory/alerts" -H "Authorization: Bearer $OP" > /dev/null
N2=$(sql "SELECT COUNT(*) FROM inventory_transactions")
chk "P5-流水数不变" "$N1" "$N2"

sep; log "P6 分页响应字段名为 list（非 items）"
B=$(curl -s --noproxy '*' "$BASE/api/v1/inventory/balances" -H "Authorization: Bearer $OP")
chk "P6-has-list" "1" "$(echo "$B" | grep -c '"list":')"
chk "P6-has-total" "1" "$(echo "$B" | grep -c '"total":')"

sep
log "RB_FAIL_COUNT=$FAIL"
log "=== RB EVIDENCE DONE ==="
exit 0
