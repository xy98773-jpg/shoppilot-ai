#!/usr/bin/env bash
# =============================================================================
# M6-T13 收口真栈探针（独立 MySQL + fat jar + service-ai 容器）
# -----------------------------------------------------------------------------
# 覆盖 §5.78（R178 + R124，方案 B′）的端到端证据：
#   P1 登录
#   P2 GET /inventory/balances 的 in_transit_qty = 派生原始在途 130（窗口外 100 也算）
#   P3 DB：inventory_balances.in_transit_qty 仍为 0.000（物化列零写入）
#   P4/P5 到货单 DRAFT → CONFIRMED（真实 HTTP，走 purchasing 全链路）
#   P6 确认 10 件后 in_transit 降为 120（剩余量 = ordered − received，自动跟随）
#   P7 DB：poi.received_qty=10、余额列仍 0 ⇒ 派生而非物化
#   P8 分析运行（enable_llm=false）→ SUCCEEDED；items.effective_in_transit_qty = 20（窗口内），
#       items.in_transit_qty = 120（未过滤快照）
#   P9 DB analysis_items 两列同上（快照落库）
# 环境：MySQL 3361 / app 18250 / service-ai 18251；trap 全回收。
# 用法：bash backend-java/tools/m6-t13/run-m6-t13.sh
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/m6-t13"
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
SEED="$OUT/seed.sql"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
MYSQL_NAME=m6t13-mysql
AI_NAME=m6t13-ai
MYSQL_PORT="${MYSQL_PORT:-3361}"
APP_PORT="${APP_PORT:-18250}"
AI_PORT="${AI_PORT:-18251}"
TOKEN=m6t13-internal-token-0123456789
EVI="$OUT/evidence-m6-t13.txt"
BODY="$OUT/body.json"
BASE="http://127.0.0.1:$APP_PORT"
PASS=0; FAIL=0; SKIP=0
mkdir -p "$OUT"
: > "$EVI"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
skip(){ SKIP=$((SKIP+1)); log "[SKIP] $1"; }
jget(){ "$PY" -c "import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    print('<unparsable>'); raise SystemExit
seen=[]
for k in sys.argv[2].split('.'):
    if k=='': continue
    seen.append(k)
    if isinstance(d,dict) and k in d: d=d[k]
    elif isinstance(d,list) and k.isdigit() and int(k)<len(d): d=d[int(k)]
    else: print('<missing:%s>'%'.'.join(seen)); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }
# jget_str：从字符串体里取值（用于 items 列表逐项）
JQ(){ "$PY" - "$2" "$1" <<'PYEOF'
import json,sys
raw,path=sys.argv[1],sys.argv[2]
d=json.loads(raw)
for k in path.split('.'):
    if k=='': continue
    if isinstance(d,dict): d=d.get(k)
    elif isinstance(d,list): d=d[int(k)]
    else: d=None; break
print('' if d is None else d)
PYEOF
}
sql(){ docker exec -i "$MYSQL_NAME" mysql -ushoppilot -pchangeme_app_password shoppilot \
        --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }
api(){ curl -s --noproxy '*' -o "$BODY" -w "%{http_code}" "$@"; }

APP_PID=""
cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${AI_PID:-}" ] && kill "$AI_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: fat jar 不存在：$JAR（先跑 clean verify）"; exit 4; }
log "JAR_SIZE=$(stat -c%s "$JAR")  JAR_MTIME=$(date -r "$JAR" '+%F %T')"
sep

# ---------------------------------------------------------------- 基础设施
log "[0] 启动 MySQL 容器（$MYSQL_NAME :$MYSQL_PORT）"
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=changeme_root_password \
  -e MYSQL_DATABASE=shoppilot -e MYSQL_USER=shoppilot -e MYSQL_PASSWORD=changeme_app_password \
  -e TZ=UTC mysql:8.0.36 --default-time-zone=+00:00 >/dev/null
for i in $(seq 1 60); do
  docker exec "$MYSQL_NAME" mysqladmin ping -uroot -pchangeme_root_password >/dev/null 2>&1 && break
  sleep 2
done
sql "SELECT 1" >/dev/null && log "    MySQL ready"

log "[1] 启动 service-ai 容器（:$AI_PORT，镜像 shoppilot-dev-service-ai:latest；R186 已于 2026-10-21 重建）"
docker rm -f "$AI_NAME" >/dev/null 2>&1
AI_UP=0
if docker run -d --name "$AI_NAME" -p "127.0.0.1:$AI_PORT:8000" \
     -e AI_INTERNAL_TOKEN="$TOKEN" \
     -e JAVA_BACKEND_BASE_URL="http://host.docker.internal:$APP_PORT" \
     shoppilot-dev-service-ai:latest >/dev/null 2>&1; then
  for i in $(seq 1 40); do
    if curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$AI_PORT/internal/v1/health"; then AI_UP=1; break; fi
    sleep 1
  done
fi
if [ "$AI_UP" = "0" ]; then
  log "    WARN: 容器未就绪，回落宿主机 py3.11 源码"
  docker rm -f "$AI_NAME" >/dev/null 2>&1
  AI_INTERNAL_TOKEN="$TOKEN" \
  JAVA_BACKEND_BASE_URL="http://127.0.0.1:$APP_PORT" \
  PYTHONPATH="$ROOT/service-ai/src" \
  py -3.11 -m uvicorn shoppilot_ai.app.main:app --host 127.0.0.1 --port "$AI_PORT" > "$OUT/ai.log" 2>&1 &
  AI_PID=$!
  for i in $(seq 1 40); do
    if curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$AI_PORT/internal/v1/health"; then AI_UP=1; break; fi
    sleep 1
  done
fi
log "    service-ai up=$AI_UP"

log "[2] 启动 fat jar（:$APP_PORT）"
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=shoppilot \
SPRING_DATASOURCE_PASSWORD=changeme_app_password \
SPRING_PROFILES_ACTIVE=dev \
JWT_SECRET="m6-t13-only-placeholder-jwt-secret-32bytes" \
AI_INTERNAL_TOKEN="$TOKEN" \
AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT" \
ANALYSIS_POLL_ENABLED=true \
java -jar "$JAR" --server.port="$APP_PORT" > "$OUT/app.log" 2>&1 &
APP_PID=$!
UP=0
for i in $(seq 1 90); do
  code=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/v1/auth/login" 2>/dev/null)
  if [ "$code" = "405" ] || [ "$code" = "400" ] || [ "$code" = "401" ]; then UP=1; break; fi
  sleep 2
done
[ "$UP" = "1" ] || { log "FATAL: app 未就绪，见 $OUT/app.log"; tail -30 "$OUT/app.log" | tee -a "$EVI"; exit 5; }
log "    app ready（health code=$code）"

log "[3] 灌种子"
docker exec -i "$MYSQL_NAME" mysql -ushoppilot -pchangeme_app_password shoppilot \
  --default-character-set=utf8mb4 < "$SEED" 2>/dev/null
chk "种子：purchase_orders 2 行" "2" "$(sql 'SELECT COUNT(*) FROM purchase_orders')"
chk "种子：purchase_order_items 2 行" "2" "$(sql 'SELECT COUNT(*) FROM purchase_order_items')"
sep

# ---------------------------------------------------------------- 探针
log "[P1] 登录 operator01"
code=$(api -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"operator01","password":"S3cret!Passw0rd"}')
# ★ 登录响应是 camelCase（AuthTokenResponse record，无 @JsonNaming）⇒ data.accessToken
TOK=$(jget "data.accessToken")
chk "P1 login HTTP" "200" "$code"
chk "P1 accessToken 是 JWT（2 个点）" "2" "$(printf '%s' "$TOK" | tr -cd '.' | wc -c | tr -d ' ')"
case "$TOK" in
  *.*.*) : ;;
  *) log "FATAL: accessToken 提取失败（值=$TOK），后续鉴权探针无意义"; FAIL=$((FAIL+1)); \
     log "TOTAL: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"; log "RESULT: HAS FAILURES"; exit 1 ;;
esac
AUTH="Authorization: Bearer $TOK"
sep

log "[P2] GET /inventory/balances ⇒ in_transit_qty = 130（派生原始在途）"
code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=9004&sku_id=9002" -H "$AUTH")
chk "P2 HTTP" "200" "$code"
chk "P2 total" "1" "$(jget 'data.total')"
chk "P2 in_transit_qty" "130.000" "$(jget 'data.list.0.in_transit_qty')"
chk "P2 on_hand_qty" "10.000" "$(jget 'data.list.0.on_hand_qty')"

log "[P3] DB：物化列 in_transit_qty 仍为 0.000（零写入）"
chk "P3 inventory_balances.in_transit_qty" "0.000" "$(sql 'SELECT in_transit_qty FROM inventory_balances WHERE sku_id=9002')"
chk "P3 非零行数" "0" "$(sql 'SELECT COUNT(*) FROM inventory_balances WHERE in_transit_qty <> 0')"
sep

log "[P4] POST /purchasing/receipts（到货 10 件）"
PO1=$(sql "SELECT id FROM purchase_orders WHERE po_no='PO-T13-1'")
POI1=$(sql "SELECT id FROM purchase_order_items WHERE purchase_order_id=$PO1 AND line_no=1")
POI2=$(sql "SELECT id FROM purchase_order_items WHERE purchase_order_id=(SELECT id FROM purchase_orders WHERE po_no='PO-T13-2')")
TODAY=$(date +%F)
code=$(api -X POST "$BASE/api/v1/purchasing/receipts" -H "$AUTH" \
  -H "Idempotency-Key: m6t13-gr-1" -H 'Content-Type: application/json' \
  -d "{\"purchase_order_id\":$PO1,\"warehouse_id\":9004,\"receipt_date\":\"$TODAY\",\"remark\":\"M6-T13 收口\",\"items\":[{\"purchase_order_item_id\":$POI1,\"received_qty\":\"10.000\",\"unit_price\":\"10.0000\"}]}")
chk "P4 创建到货单 HTTP" "201" "$code"
GR_ID=$(jget 'data.goods_receipt_id')
chk "P4 status" "DRAFT" "$(jget 'data.status')"

log "[P5] POST /purchasing/receipts/$GR_ID/confirm"
case "$GR_ID" in
  ''|*[!0-9]*) skip "P5/P6/P7 到货确认链路（创建失败，GR_ID=$GR_ID）" ;;
  *)
    code=$(api -X POST "$BASE/api/v1/purchasing/receipts/$GR_ID/confirm" -H "$AUTH")
    chk "P5 确认到货 HTTP" "200" "$code"
    chk "P5 status" "CONFIRMED" "$(jget 'data.status')"
    sep

    log "[P6] 确认后 GET balances ⇒ in_transit_qty = 120（30−10 + 100）"
    code=$(api "$BASE/api/v1/inventory/balances?warehouse_id=9004&sku_id=9002" -H "$AUTH")
    chk "P6 HTTP" "200" "$code"
    chk "P6 in_transit_qty" "120.000" "$(jget 'data.list.0.in_transit_qty')"

    log "[P7] DB：poi.received_qty=10、余额列仍 0（派生自动跟随）"
    chk "P7 poi.received_qty" "10.000" "$(sql "SELECT received_qty FROM purchase_order_items WHERE id=$POI1")"
    chk "P7 余额列" "0.000" "$(sql 'SELECT in_transit_qty FROM inventory_balances WHERE sku_id=9002')"
    chk "P7 po.received_qty" "10.000" "$(sql "SELECT received_qty FROM purchase_orders WHERE id=$PO1")"
    ;;
esac
sep

if [ "$AI_UP" = "1" ]; then
  log "[P8] 分析运行（enable_llm=false）：effective=20 / in_transit=120"
  FROM=$(date -d '-28 days' +%F); TO=$(date +%F)
  code=$(api -X POST "$BASE/api/v1/analysis/runs" -H "$AUTH" \
    -H "Idempotency-Key: m6t13-run-1" -H 'Content-Type: application/json' \
    -d "{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[9002]},\"warehouse_id\":9004,\"date_from\":\"$FROM\",\"date_to\":\"$TO\",\"params\":{\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\",\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}")
  chk "P8 创建运行 HTTP" "202" "$code"
  RUN_ID=$(jget 'data.run_id')
  case "$RUN_ID" in
    ''|*[!0-9]*) skip "P8 后续（创建失败，run_id=$RUN_ID）；响应体：$(head -c 200 "$BODY")" ;;
    *)
  STATUS=""
  for i in $(seq 1 40); do
    api "$BASE/api/v1/analysis/runs/$RUN_ID" -H "$AUTH" >/dev/null
    STATUS=$(jget 'data.status')
    [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "FAILED" ] && break
    sleep 2
  done
  chk "P8 run 终态" "SUCCEEDED" "$STATUS"
  if [ "$STATUS" = "SUCCEEDED" ]; then
    code=$(api "$BASE/api/v1/analysis/runs/$RUN_ID/items" -H "$AUTH")
    chk "P8 items HTTP" "200" "$code"
    chk "P8 items.total" "1" "$(jget 'data.total')"
    chk "P8 effective_in_transit_qty" "20.000" "$(jget 'data.list.0.effective_in_transit_qty')"
    chk "P8 in_transit_qty（未过滤快照）" "120.000" "$(jget 'data.list.0.in_transit_qty')"
    chk "P8 lead_time_source" "DEFAULT" "$(jget 'data.list.0.lead_time_source')"
    log "[P9] DB analysis_items 两列"
    chk "P9 analysis_items.effective_in_transit_qty" "20.000" \
      "$(sql "SELECT effective_in_transit_qty FROM analysis_items WHERE run_id=$RUN_ID")"
    chk "P9 analysis_items.in_transit_qty" "120.000" \
      "$(sql "SELECT in_transit_qty FROM analysis_items WHERE run_id=$RUN_ID")"
  fi
      ;;
  esac
else
  skip "P8/P9 分析链路（service-ai 未就绪）"
fi

sep
log "TOTAL: PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
