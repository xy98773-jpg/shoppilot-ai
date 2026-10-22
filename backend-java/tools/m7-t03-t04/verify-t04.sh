#!/usr/bin/env bash
# =============================================================================
# M7-T04 可观测性验证（NFR-OBS-01：requestId 全链路贯通 Java ↔ Python ↔ 审计）
# -----------------------------------------------------------------------------
# 三条独立断言链：
#   A. 响应头 X-Request-Id 回显（客户端传入 ⇒ 原样返回）
#   B. 同一 requestId 命中 audit_logs（Java 侧审计）
#   C. 同一 requestId 同时命中 backend 日志 与 service-ai 日志（跨进程）
# 前置：T05 已起 dev 栈；dev 库口令为测试值（T03 已重置）。
# 用法：bash backend-java/tools/m7-t03-t04/verify-t04.sh
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/m7-t03-t04"
EVI="$OUT/evidence-t04.txt"
BODY="$OUT/body-t04.json"
BASE="http://127.0.0.1:8081"
MYSQL=shoppilot-dev-mysql-1
MYSQL_ARGS="-ushoppilot -pchangeme_app_password shoppilot"
AI_NAME=shoppilot-dev-service-ai-1
APP_NAME=shoppilot-dev-backend-java-1
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
PASS=0; FAIL=0
mkdir -p "$OUT"; : > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
chk_gt(){ if [ "${3%.*}" -gt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (> $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected > [$2] but was [$3]"; fi; }
api(){ curl -s --noproxy '*' -o "$BODY" -w "%{http_code}" "$@"; }
jget(){ "$PY" -c "import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
for k in sys.argv[2].split('.'):
    if not k: continue
    if isinstance(d,dict) and k in d: d=d[k]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }
sql(){ docker exec "$MYSQL" mysql $MYSQL_ARGS --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }
hdr(){ grep -i "^x-request-id:" "$1" 2>/dev/null | tr -d '\r' | awk '{print $2}'; }

log "M7-T04 可观测性验证  $(date '+%F %T')"
log "目标：$BASE（compose dev 栈）"
sep

log "[0] 前置：为 team=7 造 7+ 天销售（分析预检 min_days=7 要求）"
# 必须取 ACTIVE 的 SKU：INACTIVE 会被分析预检拦成 COM-0001（造数约束，非缺陷）
SKU=$(sql "SELECT id FROM skus WHERE team_id=7 AND status='ACTIVE' ORDER BY id LIMIT 1")
PRD=$(sql "SELECT product_id FROM skus WHERE id=$SKU")
WH=$(sql 'SELECT id FROM warehouses WHERE team_id=7 ORDER BY id LIMIT 1')
log "    SKU_ID=$SKU  PRODUCT_ID=$PRD  WH_ID=$WH"
# product_id 为 NOT NULL 且无默认值，漏填会 1364 静默零行（造数约束，非缺陷）
for i in $(seq 20 -1 0); do
  d=$(date -d "-$i days" +%F)
  sql "INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty, return_qty, sales_amount, order_count, source)
       VALUES (7, $WH, $SKU, $PRD, '$d', 4.000, 0.000, 356.00, 2, 'MANUAL')
       ON DUPLICATE KEY UPDATE qty=VALUES(qty), sales_amount=VALUES(sales_amount)" >/dev/null
done
chk_gt "0 sales_daily 天数" "6" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=$SKU")"
sep

log "[1] 响应头回显：客户端传入 X-Request-Id 必须原样返回"
RID1="m7obs-login-$(date +%s)"
code=$(api -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -H "X-Request-Id: $RID1" -d '{"username":"owner01","password":"1234"}' \
  -D "$OUT/hdr-login.txt")
chk "1 登录 HTTP" "200" "$code"
chk "1 响应头 X-Request-Id 回显" "$RID1" "$(hdr "$OUT/hdr-login.txt")"
chk "1 响应体 requestId 与头一致" "$RID1" "$(jget 'requestId')"
TOK=$(jget 'data.accessToken')
AUTH="Authorization: Bearer $TOK"
# 未传时由服务端生成（非空、非固定值）
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=1" -H "$AUTH" -D "$OUT/hdr-auto.txt")
GEN=$(hdr "$OUT/hdr-auto.txt")
chk "1 未传时服务端生成" "ok" "$( [ -n "$GEN" ] && [ "$GEN" != "$RID1" ] && echo ok || echo "$GEN" )"
sep

log "[2] 审计链：同一 requestId 命中 audit_logs"
N1=$(sql "SELECT COUNT(*) FROM audit_logs WHERE request_id='$RID1'")
chk_gt "2 audit_logs 命中行数" "0" "$N1"
ACT=$(sql "SELECT MIN(action) FROM audit_logs WHERE request_id='$RID1'")
chk "2 审计动作" "AUTH_LOGIN_SUCCESS" "$ACT"
sep

log "[3] 跨进程链：Java → Python 的 X-Request-Id 透传"
RID2="m7obs-run-$(date +%s)"
FROM=$(date -d '-20 days' +%F); TO=$(date +%F)
code=$(api -X POST "$BASE/api/v1/analysis/runs" -H "$AUTH" -H "X-Request-Id: $RID2" \
  -H "Idempotency-Key: m7obs-run-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[$SKU]},\"warehouse_id\":$WH,\"date_from\":\"$FROM\",\"date_to\":\"$TO\",\"params\":{\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\",\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}" \
  -D "$OUT/hdr-run.txt")
chk "3 创建运行 HTTP" "202" "$code"
chk "3 响应头回显" "$RID2" "$(hdr "$OUT/hdr-run.txt")"
RUN_ID=$(jget 'data.run_id')
case "$RUN_ID" in ''|*[!0-9]*) FAIL=$((FAIL+1)); log "[FAIL] 3 未取到 run_id：$(head -c 200 "$BODY")" ;; *) : ;; esac
STATUS=""
for i in $(seq 1 40); do
  api "$BASE/api/v1/analysis/runs/$RUN_ID" -H "$AUTH" >/dev/null
  STATUS=$(jget 'data.status')
  { [ "$STATUS" = "SUCCEEDED" ] || [ "$STATUS" = "DEGRADED" ] || [ "$STATUS" = "FAILED" ]; } && break
  sleep 2
done
chk "3 run 到达终态" "ok" "$( case "$STATUS" in SUCCEEDED|DEGRADED) echo ok ;; *) echo "$STATUS" ;; esac )"
# 等待日志落盘
sleep 3
APP_HITS=$(docker logs --since 10m "$APP_NAME" 2>&1 | grep -c "$RID2")
PY_HITS=$(docker logs --since 10m "$AI_NAME" 2>&1 | grep -c "$RID2")
chk_gt "3 backend 日志命中" "0" "$APP_HITS"
chk_gt "3 service-ai 日志命中" "0" "$PY_HITS"
# 审计里也能查到这次运行（RUN_SUBMIT / ANALYSIS_*）
sep

log "[3b] 异步链：不靠 GET 触发读时同步，由定时轮询线程接手（R191 修复点）"
RID3="m7obs-async-$(date +%s)"
code=$(api -X POST "$BASE/api/v1/analysis/runs" -H "$AUTH" -H "X-Request-Id: $RID3" \
  -H "Idempotency-Key: m7obs-async-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[$SKU]},\"warehouse_id\":$WH,\"date_from\":\"$FROM\",\"date_to\":\"$TO\",\"params\":{\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\",\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}" \
  -D "$OUT/hdr-run3.txt")
chk "3b 创建运行 HTTP" "202" "$code"
RUN3=$(jget 'data.run_id')
log "    等待定时轮询接手（5s 间隔，全程不发 GET）：runId=$RUN3"
sleep 18
POLL_HITS=$(docker logs --since 5m "$APP_NAME" 2>&1 | grep "$RID3" | grep -c "scheduling-")
chk_gt "3b 轮询线程日志带 request_id（异步链，R191）" "0" "$POLL_HITS"
docker logs --since 5m "$APP_NAME" 2>&1 | grep "$RID3" | grep "scheduling-" | head -2 | sed 's/^/    [poll] /' | tee -a "$EVI"
api "$BASE/api/v1/analysis/runs/$RUN3" -H "$AUTH" >/dev/null
STATUS3=$(jget 'data.status')
chk "3b 异步链同样到达终态" "ok" "$( case "$STATUS3" in SUCCEEDED|DEGRADED) echo ok ;; *) echo "$STATUS3" ;; esac )"
sep

log "[4] 汇总：一次请求可在三处串联检索"
log "    requestId=$RID1 → auth（审计 AUTH_LOGIN_SUCCESS）"
log "    requestId=$RID2 → Java 日志 $APP_HITS 行 / Python 日志 $PY_HITS 行"
docker logs --since 10m "$AI_NAME" 2>&1 | grep "$RID2" | head -3 | sed 's/^/    [py] /' | tee -a "$EVI"
docker logs --since 10m "$APP_NAME" 2>&1 | grep "$RID2" | head -3 | sed 's/^/    [java] /' | tee -a "$EVI"
sep
log "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
