#!/usr/bin/env bash
# =============================================================================
# M4-B20.1 项目负责人独立复核探针（不采信开发自报）
# -----------------------------------------------------------------------------
# 与开发 V1/V2/V3/V4/V5 的差异（刻意）：
#   1) **真实 service-ai 容器**（不是 stub）⇒ 第一次打通 Java→Python 端到端：
#      POST /api/v1/analysis/runs → Java 装配 items → Python 真实编排 → COMPLETED；
#   2) 独立 MySQL + 独立 fat jar + 独立端口（不复用任何开发环境）；
#   3) 覆盖开发未验的项：跨团队 SKU 不建 run、不写 FINISH 审计；
#      无默认仓 / 多默认仓 ⇒ COM-0001；Q15 空范围放行；Q16 ANL-7007 早于存在性；
#      交期四来源（POLICY/SKU/SUPPLIER/DEFAULT）；is_enabled=0 策略忽略；
#      缺余额行 ⇒ 0.000；sales_series 不补 0 且窗口外被过滤；
#      ★ 出站 items 逐字段取自**真实 service-ai 收到的请求体**（用反代抓包，非自报）。
# 生命周期：trap cleanup（容器/进程全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b201-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b201r-mysql"
AI_NAME="rb-b201r-ai"
MYSQL_PORT="${MYSQL_PORT:-3396}"
APP_PORT="${APP_PORT:-18386}"
AI_PORT="${AI_PORT:-18396}"
AI_PROXY_PORT="${AI_PROXY_PORT:-18397}"
TOKEN="rb-b201r-internal-token-0123456789"
EVI="$OUT/evidence-b201-review.txt"
CAP="$OUT/ai-captured.jsonl"
PASS=0; FAIL=0; PROBE=0
mkdir -p "$OUT/storage"
: > "$EVI"; : > "$CAP"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
  else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi
}
note(){ log "  NOTE $*"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: jar 不存在 $JAR"; exit 4; }
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT" "$AI_PROXY_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT/$AI_PROXY_PORT"

# ---------------------------------------------------------------- MySQL
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
READY=0
for _ in $(seq 1 90); do
  docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
[ "$READY" = 1 ] || { log "FATAL: MySQL 未就绪"; exit 5; }
log "MYSQL_READY=1"

# ---------------------------------------------------------------- 真实 service-ai
docker rm -f "$AI_NAME" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 docker run -d --name "$AI_NAME" -p "$AI_PORT:8000" \
  -v "$ROOT/service-ai:/app" -w /app -e PYTHONPATH=/app/src -e AI_INTERNAL_TOKEN="$TOKEN" \
  shoppilot-dev-service-ai:latest uvicorn shoppilot_ai.app.main:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1
AUP=0
for _ in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { AUP=1; break; }
  sleep 1
done
[ "$AUP" = 1 ] || { log "FATAL: service-ai 未就绪"; docker logs "$AI_NAME" 2>&1 | tail -20 | tee -a "$EVI"; exit 5; }
log "SERVICE_AI_UP=1 (real container, port $AI_PORT)"

# ---------------------------------------------------------------- 出站抓包反代
# ★ 目的：拿到 **Java 真实发出的** 请求体（而非开发自报）。
#   Java → 反代(18391) → 真实 service-ai(18390)；反代把 body 落盘后转发。
"$PY" "$OUT/ai_proxy.py" "$AI_PORT" "$AI_PROXY_PORT" "$CAP" >"$OUT/proxy.out" 2>&1 &
PROXY_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PROXY_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: 抓包反代未就绪"; cat "$OUT/proxy.out" | tee -a "$EVI"; exit 5; }
log "CAPTURE_PROXY_UP=1 (18391 -> $AI_PORT)"

# ---------------------------------------------------------------- app
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b201r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PROXY_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -jar "$JAR" --server.port="$APP_PORT" >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 150); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT)"

# ---------------------------------------------------------------- 结构面
sep; log "=== S1 结构面 ==="
JAR_WIN=$(cygpath -w "$JAR")
JAR_HIT=$("$PY" -c "import zipfile,sys;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('V1.0.9__analysis.sql')))" "$JAR_WIN")
chk "fat jar 内含 V1.0.9__analysis.sql" "1" "$JAR_HIT"
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }
chk "迁移版本数（10）" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"
chk "analysis_runs.warehouse_id 可空（NULL=默认仓的历史语义仍在）" "YES" "$(q "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_runs' AND COLUMN_NAME='warehouse_id';")"

# ---------------------------------------------------------------- 种子 + 登录
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$OUT/seed-b201.sql" 2>"$OUT/seed.err"
SEED_RC=$?
chk "种子导入成功" "0" "$SEED_RC"
[ "$SEED_RC" = 0 ] || { cat "$OUT/seed.err" | tee -a "$EVI"; }
chk "种子：团队 7 的默认仓 9004 is_default=1" "1" "$(q "SELECT is_default FROM shoppilot.warehouses WHERE id=9004;")"
chk "种子：9003 无库存行" "0" "$(q "SELECT COUNT(*) FROM shoppilot.inventory_balances WHERE sku_id=9003;")"
chk "种子：9002 销量 28 条（含窗口外 1 条）" "28" "$(q "SELECT COUNT(*) FROM shoppilot.sales_daily WHERE sku_id=9002;")"

login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"1234\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_T8=$(login owner8)
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && echo 1 || echo 0)"
chk "owner8（团队 8）登录" "1" "$([ -n "$T_T8" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- HTTP 工具
jf(){ "$PY" - "$OUT/body.json" "$1" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
cur=d
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(cur,dict) and k in cur:
        cur=cur[k]
    elif isinstance(cur,list) and k.lstrip('-').isdigit():
        i=int(k)
        if -len(cur) <= i < len(cur):
            cur=cur[i]
        else:
            print('<index-out-of-range:%s>'%k); raise SystemExit
    else:
        print('<missing:%s>'%k); raise SystemExit
print('' if cur is None else cur)
PY
}
POST_STATUS=""; POST_RID=""
post_create(){ # token key body rid
  local token="$1" key="$2" body="$3" rid="$4"
  local hdr=(-H "Content-Type: application/json" -H "Authorization: Bearer $token" -H "X-Request-Id: $rid")
  [ -n "$key" ] && hdr+=(-H "Idempotency-Key: $key")
  POST_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" "${hdr[@]}" -d "$body")
  POST_RID="$rid"
}
replayed(){ grep -qi '^Idempotent-Replayed: true' "$OUT/headers.txt" && echo true || echo false; }
ai_get(){ # path -> body to stdout
  curl -s --noproxy '*' -H "X-Internal-Token: $TOKEN" "$1"
}
cap_get(){ # python expr over captured requests
  "$PY" - "$CAP" "$1" <<'PY'
import json,sys
rows=[]
for line in open(sys.argv[1],encoding='utf-8'):
    line=line.strip()
    if not line: continue
    try: rows.append(json.loads(line))
    except Exception: pass
if not rows:
    print('<no-capture>'); raise SystemExit
print(eval(sys.argv[2], {"rows":rows}))
PY
}

WIN='2026-09-16'; WOUT='2026-10-13'
BODY_A='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_ALL='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_CAT='{"scope_type":"CATEGORY","scope_json":{"category":"CAT-01"},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_CAT_EMPTY='{"scope_type":"CATEGORY","scope_json":{"category":"NO-SUCH-CAT"},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_XTEAM='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9102]},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_INACTIVE='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9005]},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_NOWH='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9010,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'

# ---------------------------------------------------------------- T1 端到端
sep; log "=== T1 端到端：Java 202 → 真实 service-ai 编排 → COMPLETED ==="
post_create "$T_OP" "b201r-k1" "$BODY_A" "req_b201r_t1"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T1 HTTP" "202" "$POST_STATUS"
chk "T1 code" "OK" "$(jf code)"
RID1=$(jf data.run_id)
chk "T1 DB status=RUNNING（Java 侧已发起）" "RUNNING" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "T1 DB warehouse_id 回填为默认仓 9004（请求未带仓）" "9004" "$(q "SELECT warehouse_id FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "T1 DB team_id=7（来自 JWT）" "7" "$(q "SELECT team_id FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "T1 审计 CREATE=1" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID1;")"
chk "T1 审计 FINISH=0（B20.2 才落库，本批不该有）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH' AND biz_id=$RID1;")"
note "等 Python 编排完成（Java 侧轮询属 B20.2，本批直接查 Python 内部端点）"
PYSTATE=""
for _ in $(seq 1 40); do
  PYSTATE=$(ai_get "http://127.0.0.1:$AI_PORT/internal/v1/analysis/runs/$RID1" | "$PY" -c "import json,sys;d=json.load(sys.stdin);print(d.get('data',{}).get('execution_status',''))" 2>/dev/null)
  [ "$PYSTATE" = "COMPLETED" ] || [ "$PYSTATE" = "FAILED" ] && break
  sleep 1
done
chk "T1 ★Python 侧 execution_status=COMPLETED（items 真实装配 ⇒ 端到端打通）" "COMPLETED" "$PYSTATE"
PYSBODY=$(ai_get "http://127.0.0.1:$AI_PORT/internal/v1/analysis/runs/$RID1/result")
echo "$PYSBODY" > "$OUT/py-result.json"
chk "T1 Python result 200 + OK" "OK" "$(echo "$PYSBODY" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('code',''))")"
chk "T1 Python summary.total_skus=1" "1" "$(echo "$PYSBODY" | "$PY" -c "import json,sys;print(json.load(sys.stdin)['data']['summary']['total_skus'])")"
chk "T1 Python item.avg_daily_sales=6.000（27 天×6.000 / 27 天）" "6.000" "$(echo "$PYSBODY" | "$PY" -c "import json,sys;print(json.load(sys.stdin)['data']['items'][0]['avg_daily_sales'])")"
chk "T1 Python item.valid_days=27（不补 0）" "27" "$(echo "$PYSBODY" | "$PY" -c "import json,sys;print(json.load(sys.stdin)['data']['items'][0]['valid_days'])")"
chk "T1 Python item.inventory_position=14.000（10+5-1）" "14.000" "$(echo "$PYSBODY" | "$PY" -c "import json,sys;print(json.load(sys.stdin)['data']['items'][0]['inventory_position'])")"

# ---------------------------------------------------------------- T2 出站抓包
sep; log "=== T2 出站请求体（抓包，非自报） ==="
CAPN=$(wc -l < "$CAP")
chk "T2 抓到 1 次出站 create" "1" "$CAPN"
chk "T2 出站 warehouse_id=9004（解析后的默认仓）" "9004" "$(cap_get "rows[0]['body']['warehouse_id']")"
chk "T2 出站 items 条数=1" "1" "$(cap_get "len(rows[0]['body']['items'])")"
chk "T2 出站 item.sku_id=9002" "9002" "$(cap_get "rows[0]['body']['items'][0]['sku_id']")"
chk "T2 出站 on_hand_qty=10.000" "10.000" "$(cap_get "rows[0]['body']['items'][0]['on_hand_qty']")"
chk "T2 出站 committed_qty=1.000" "1.000" "$(cap_get "rows[0]['body']['items'][0]['committed_qty']")"
chk "T2 出站 effective_in_transit_qty=5.000（R124 余额列口径）" "5.000" "$(cap_get "rows[0]['body']['items'][0]['effective_in_transit_qty']")"
chk "T2 出站 lead_time_source=POLICY（策略 4 压住 sku 的 3）" "POLICY" "$(cap_get "rows[0]['body']['items'][0]['lead_time_source']")"
chk "T2 出站 lead_time_days=4" "4" "$(cap_get "rows[0]['body']['items'][0]['lead_time_days']")"
chk "T2 出站 safety_stock_override=12.500（策略）" "12.500" "$(cap_get "rows[0]['body']['items'][0]['safety_stock_override']")"
chk "T2 出站 moq=50.000（策略）" "50.000" "$(cap_get "rows[0]['body']['items'][0]['moq']")"
chk "T2 出站 order_multiple=10.000（策略）" "10.000" "$(cap_get "rows[0]['body']['items'][0]['order_multiple']")"
chk "T2 出站 qty_step=2.000（策略）" "2.000" "$(cap_get "rows[0]['body']['items'][0]['qty_step']")"
chk "T2 出站 listed_date=2026-01-15" "2026-01-15" "$(cap_get "rows[0]['body']['items'][0]['listed_date']")"
chk "T2 出站 sales_series 条数=27（不补 0、窗口外被过滤）" "27" "$(cap_get "len(rows[0]['body']['items'][0]['sales_series'])")"
chk "T2 出站 sales_series 不含 2026-09-17（缺失日不补）" "0" "$(cap_get "sum(1 for p in rows[0]['body']['items'][0]['sales_series'] if p['date']=='2026-09-17')")"
chk "T2 出站 sales_series 不含 2026-10-20（窗口外被过滤）" "0" "$(cap_get "sum(1 for p in rows[0]['body']['items'][0]['sales_series'] if p['date']=='2026-10-20')")"
chk "T2 出站 sales_series 首条 qty=6.000（字符串 3 位小数）" "6.000" "$(cap_get "rows[0]['body']['items'][0]['sales_series'][0]['qty']")"
chk "T2 出站 items 字段集合（Python Item extra=forbid，多一个就 400）" \
  "committed_qty, effective_in_transit_qty, lead_time_days, lead_time_source, listed_date, moq, on_hand_qty, order_multiple, qty_step, safety_stock_override, sales_series, sku_id" \
  "$(cap_get "', '.join(sorted(rows[0]['body']['items'][0].keys()))")"

# ---------------------------------------------------------------- T3 幂等（Q3 核心）
T_OP=$(login operator01)   # 重新登录：避免长跑后 token 过期
sep; log "=== T3 幂等：请求不带仓，重投仍同体（Q3 核心回归） ==="
post_create "$T_OP" "b201r-k1" "$BODY_A" "req_b201r_t3"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
chk "T3 HTTP" "202" "$POST_STATUS"
chk "T3 Idempotent-Replayed" "true" "$(replayed)"
chk "T3 run_id 与首次一致" "$RID1" "$(jf data.run_id)"
chk "T3 同键行数仍为 1" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE idempotency_key='b201r-k1';")"
chk "T3 出站未新增 create（第 2 次被幂等拦截）" "1" "$(wc -l < "$CAP")"

# ---------------------------------------------------------------- T4 跨团队 SKU
sep; log "=== T4 跨团队 SKU ⇒ COM-0001，且不建 run、不写 FINISH 审计（Q5） ==="
RUNS_BEFORE=$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")
FIN_BEFORE=$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH';")
post_create "$T_OP" "b201r-k-xteam" "$BODY_XTEAM" "req_b201r_t4"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T4 HTTP" "400" "$POST_STATUS"
chk "T4 code" "COM-0001" "$(jf code)"
chk "T4 details[0].field=scope.sku_ids" "scope.sku_ids" "$(jf details.0.field)"
chk "T4 details[0].rejectedValue=9102" "9102" "$(jf details.0.rejectedValue)"
chk "T4 run 行数未增" "$RUNS_BEFORE" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")"
chk "T4 FINISH 审计未增" "$FIN_BEFORE" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH';")"

# ---------------------------------------------------------------- T5 INACTIVE SKU
sep; log "=== T5 INACTIVE SKU ⇒ COM-0001 ==="
post_create "$T_OP" "b201r-k-inactive" "$BODY_INACTIVE" "req_b201r_t5"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T5 HTTP" "400" "$POST_STATUS"
chk "T5 code" "COM-0001" "$(jf code)"

# ---------------------------------------------------------------- T6 交期四来源
T_OP=$(login operator01)
sep; log "=== T6 交期四来源（ALL_ACTIVE 一次装全） ==="
post_create "$T_OP" "b201r-k-all" "$BODY_ALL" "req_b201r_t6"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T6 HTTP" "202" "$POST_STATUS"
chk "T6 出站 items=5（9002/9003/9004/9006/9007；9005 INACTIVE 排除）" "5" "$(cap_get "len(rows[-1]['body']['items'])")"
chk "T6 出站 sku_id 升序" "[9002, 9003, 9004, 9006, 9007]" "$(cap_get "[i['sku_id'] for i in rows[-1]['body']['items']]")"
chk "T6 交期来源四分支（9002=POLICY/9007=SKU/9004=SUPPLIER/9003,9006=DEFAULT）" \
  "9002:POLICY:4|9003:DEFAULT:7|9004:SUPPLIER:9|9006:DEFAULT:7|9007:SKU:2" \
  "$(cap_get "'|'.join('%s:%s:%s'%(i['sku_id'],i['lead_time_source'],i['lead_time_days']) for i in rows[-1]['body']['items'])")"
chk "T6 9003（无余额行）三项兜 0.000" "0.000" "$(cap_get "[i['on_hand_qty'] for i in rows[-1]['body']['items'] if i['sku_id']==9003][0]")"
chk "T6 9003（策略 is_enabled=0）不下发 safety_stock_override" "None" "$(cap_get "[i.get('safety_stock_override') for i in rows[-1]['body']['items'] if i['sku_id']==9003][0]")"
chk "T6 9003 无销量 ⇒ sales_series 空数组" "0" "$(cap_get "len([i for i in rows[-1]['body']['items'] if i['sku_id']==9003][0]['sales_series'])")"
chk "T6 9002 销量 27 条（不补 0、窗口外过滤）" "27" "$(cap_get "len([i for i in rows[-1]['body']['items'] if i['sku_id']==9002][0]['sales_series'])")"
chk "T6 9007 有 sku 级交期 2 但无策略 ⇒ 不下发采购参数" "None" "$(cap_get "[i.get('moq') for i in rows[-1]['body']['items'] if i['sku_id']==9007][0]")"

# ---------------------------------------------------------------- T6b CATEGORY
T_OP=$(login operator01)
sep; log "=== T6b CATEGORY 按 products.category 取 SKU ==="
post_create "$T_OP" "b201r-k-cat" "$BODY_CAT" "req_b201r_t6b"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T6b HTTP" "202" "$POST_STATUS"
chk "T6b CATEGORY CAT-01 ⇒ 命中 9002/9003/9004/9006/9007（9005 INACTIVE 排除）" "[9002, 9003, 9004, 9006, 9007]" "$(cap_get "[i['sku_id'] for i in rows[-1]['body']['items']]")"

# ---------------------------------------------------------------- T7 Q15 空范围
T_OP=$(login operator01)
sep; log "=== T7 Q15：CATEGORY 匹配为空 ⇒ 放行（建 run，不报错） ==="
RUNS_BEFORE=$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")
post_create "$T_OP" "b201r-k-catempty" "$BODY_CAT_EMPTY" "req_b201r_t7"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T7 HTTP" "202" "$POST_STATUS"
chk "T7 run 行数 +1（范围空是业务事实）" "$((RUNS_BEFORE+1))" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")"
chk "T7 出站 items=0" "0" "$(cap_get "len(rows[-1]['body']['items'])")"

# ---------------------------------------------------------------- T8 Q16
sep; log "=== T8 Q16：2001 个 SKU ⇒ ANL-7007（早于存在性校验） ==="
SKUS=$(for i in $(seq 1 2001); do printf "%s," $((10000+i)); done | sed 's/,$//')
BODY_MANY="{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[$SKUS]},\"date_from\":\"2026-09-16\",\"date_to\":\"2026-10-13\",\"params\":{\"window_days\":28,\"min_days\":7,\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\"}"
RUNS_BEFORE=$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")
post_create "$T_OP" "b201r-k-many" "$BODY_MANY" "req_b201r_t8"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T8 HTTP" "400" "$POST_STATUS"
chk "T8 code=ANL-7007（不是 COM-0001）" "ANL-7007" "$(jf code)"
chk "T8 run 行数未增" "$RUNS_BEFORE" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs;")"

# ---------------------------------------------------------------- T9 无默认仓
T_OP=$(login operator01)
sep; log "=== T9 无默认仓 ⇒ COM-0001（details=未配置默认仓） ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw shoppilot -e "UPDATE warehouses SET is_default=0 WHERE team_id=7;" >/dev/null 2>&1
post_create "$T_OP" "b201r-k-nowh" "$BODY_A" "req_b201r_t9"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T9 HTTP" "400" "$POST_STATUS"
chk "T9 code" "COM-0001" "$(jf code)"
chk "T9 details[0].message=未配置默认仓" "未配置默认仓" "$(jf details.0.message)"
chk "T9 details[0].field=warehouse_id" "warehouse_id" "$(jf details.0.field)"
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw shoppilot -e "UPDATE warehouses SET is_default=1 WHERE id=9004;" >/dev/null 2>&1

# ---------------------------------------------------------------- T10 多默认仓
T_OP=$(login operator01)
sep; log "=== T10 默认仓命中多行 ⇒ COM-0001（数据异常不猜） ==="
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw shoppilot -e "UPDATE warehouses SET is_default=1 WHERE id=9010;" >/dev/null 2>&1
post_create "$T_OP" "b201r-k-multidef" "$BODY_A" "req_b201r_t10"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T10 HTTP" "400" "$POST_STATUS"
chk "T10 code" "COM-0001" "$(jf code)"
chk "T10 details[0].message 含「默认仓数据异常」" "1" "$(echo "$(jf details.0.message)" | grep -c '默认仓数据异常')"
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw shoppilot -e "UPDATE warehouses SET is_default=0 WHERE id=9010;" >/dev/null 2>&1

# ---------------------------------------------------------------- T11 显式指定非默认仓
T_OP=$(login operator01)
sep; log "=== T11 显式指定非默认仓 9010（is_default=0）⇒ 原样使用 ==="
post_create "$T_OP" "b201r-k-wh9010" "$BODY_NOWH" "req_b201r_t11"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T11 HTTP" "202" "$POST_STATUS"
RID11=$(jf data.run_id)
chk "T11 DB warehouse_id=9010" "9010" "$(q "SELECT warehouse_id FROM shoppilot.analysis_runs WHERE id=$RID11;")"
chk "T11 出站 warehouse_id=9010" "9010" "$(cap_get "rows[-1]['body']['warehouse_id']")"
chk "T11 该仓无余额 ⇒ 三项兜 0.000" "0.000" "$(cap_get "rows[-1]['body']['items'][0]['on_hand_qty']")"

# ---------------------------------------------------------------- T12 R124 WARN
sep; log "=== T12 R124：每次装配打一条 WARN ==="
chk "T12 app.log 含 R124 WARN" "1" "$(grep -c 'R124' "$APP_LOG" | awk '{print ($1>0)?1:0}')"
chk "T12 WARN 提到 M6 换回 §10.4" "1" "$(grep 'R124' "$APP_LOG" | grep -c '10.4' | awk '{print ($1>0)?1:0}')"

# ---------------------------------------------------------------- T13 越界检查
sep; log "=== T13 越界与依赖检查（静态） ==="
chk "T13 analysis 主代码零 identity 引用（Q1）" "0" "$(grep -rn 'com.shoppilot.identity' "$ROOT/backend-java/analysis/src/main" --include=*.java | wc -l)"
chk "T13 装配器零 SQL 字符串拼接" "0" "$(grep -rnE '\"(SELECT|FROM|WHERE|JOIN) ' "$ROOT/backend-java/analysis/src/main/java/com/shoppilot/analysis/infrastructure/sampling/AnalysisItemAssembler.java" | wc -l)"
chk "T13 装配器未引用 Team 的默认仓 getter（R125）" "0" "$(grep -cE 'getDefaultWarehouseId|DefaultWarehouseId|TeamMapper' "$ROOT/backend-java/analysis/src/main/java/com/shoppilot/analysis/infrastructure/sampling/AnalysisItemAssembler.java")"
# ★ R126 取证：跨模块直接注入对方 Mapper 的数量（MODULE_BOUNDARIES §1.3/§5.3 的「只能走 application 层服务接口」）
XM=$(grep -c 'import com.shoppilot.catalog.infrastructure.persistence.mapper\|import com.shoppilot.inventory.infrastructure.persistence.mapper' "$ROOT/backend-java/analysis/src/main/java/com/shoppilot/analysis/infrastructure/sampling/AnalysisItemAssembler.java")
note "T13 装配器直接注入的对方 Mapper 导入行数 = $XM（MODULE_BOUNDARIES §1.3/§5.3 口径只允许走 application 层服务接口）"
note "T13 既有先例：importdata 的 ImportValidationService 也直接注入 catalog Mapper（$(grep -c 'import com.shoppilot.catalog.infrastructure.persistence.mapper' "$ROOT/backend-java/importdata/src/main/java/com/shoppilot/importdata/application/ImportValidationService.java") 行）"
note "T13 catalog 侧已有 WarehouseQueryService.getDefaultWarehouse()（注释写明「供 inventory/analysis 等下游使用」）但走 LIMIT 1，检测不到 Q1 要求的「多默认仓」异常"

# ---------------------------------------------------------------- T14 存量 NULL 仓行的幂等重投
sep; log "=== T14 存量 NULL 仓行 + 同 Idempotency-Key 重投（Q3 裁定的边界：存量行） ==="
# 造一条 **B20.1 之前** 创建的 run：warehouse_id IS NULL（同键、进行中）
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot -e "
INSERT INTO analysis_runs (team_id, run_no, status, scope_type, scope_json, warehouse_id,
  params_json, algorithm_version, llm_status, progress, idempotency_key)
VALUES (7, 'RUN-20200101-0900', 'RUNNING', 'SELECTED_SKUS', '{\"sku_ids\":[9002]}', NULL,
  '{}', 'avg-ma-3sigma-v1', 'NOT_REQUESTED', 0, 'b201r-legacy-nullwh');" >/dev/null 2>&1
T_OP=$(login operator01)
post_create "$T_OP" "b201r-legacy-nullwh" "$BODY_A" "req_b201r_t14"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
note "存量行的 warehouse_id 是 NULL（Q3 之前写入），而重投请求解析出 9004 ⇒ 两者不等"
chk "T14 现状：被判异体 COM-0009（不重放）" "COM-0009" "$(jf code)"
note "★ 判为**已知边界**（Q3 只保证『新写行』用解析值；存量 NULL 行仍会误判异体）"

# ---------------------------------------------------------------- T15 跨团队仓库
sep; log "=== T15 请求显式指定**他团队**的仓库 9103（AI_SERVICE_CONTRACT §7 第 3 条归 B20.2） ==="
BODY_XWH='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9103,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
T_OP=$(login operator01)
post_create "$T_OP" "b201r-k-xwh" "$BODY_XWH" "req_b201r_t15"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T15 HTTP 202（仓库归属校验属 B20.2 落库前二次校验，本批不校验）" "202" "$POST_STATUS"
RID15=$(jf data.run_id)
chk "T15 DB warehouse_id 原样落 9103（未改写）" "9103" "$(q "SELECT warehouse_id FROM shoppilot.analysis_runs WHERE id=$RID15;")"
chk "T15 出站 warehouse_id=9103" "9103" "$(cap_get "rows[-1]['body']['warehouse_id']")"
chk "T15 ★他团队仓无本团队余额 ⇒ 三项兜 0.000（租户线过滤，非数据泄漏）" "0.000" "$(cap_get "rows[-1]['body']['items'][0]['on_hand_qty']")"
chk "T15 ★他团队仓无本团队销量 ⇒ sales_series 空" "0" "$(cap_get "len(rows[-1]['body']['items'][0]['sales_series'])")"
note "★ 观察：跨团队仓库当前不报错、只会装配出全 0 快照 ⇒ Python 侧判 AI-2002（不可分析）；"

# ---------------------------------------------------------------- 汇总
sep; log "=== SUMMARY ==="
log "RB201R_PASS=$PASS RB201R_FAIL=$FAIL"
log "CAPTURE_LINES=$(wc -l < "$CAP")"
log "RB201R_DONE"
exit 0
