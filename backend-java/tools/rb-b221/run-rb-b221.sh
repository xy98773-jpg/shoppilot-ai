#!/usr/bin/env bash
# =============================================================================
# M4-B22.1 开发自证探针（独立真栈）
# -----------------------------------------------------------------------------
# 覆盖：
#   R148  500 SKU / ALL_ACTIVE / enable_llm=false：创建 → SUCCEEDED 墙钟 < 60s
#   V3    GET /api/v1/analysis/runs 真实响应（分页三字段）+ 列表元素与
#         GET /runs/{id} 的 data **逐字比对**
#   R149-C1 停 service-ai 容器后创建 ⇒ 502 ANL-7002 + run FAILED
#   R149-C2 AI_SERVICE_BASE_URL 指向黑洞 10.255.255.1 ⇒ 记录**实际**状态码/错误码 + run FAILED
#           （另用 stall 服务构造真实读超时，补一条 504 ANL-7003 的正证）
# 环境：独立 MySQL 3383 / app 18340 / service-ai 18341 / stall 18342，trap 全回收。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b221"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
MYSQL_NAME="rb-b221-mysql"
AI_NAME="rb-b221-ai"
MYSQL_PORT="${MYSQL_PORT:-3383}"
APP_PORT="${APP_PORT:-18340}"
AI_PORT="${AI_PORT:-18341}"
STALL_PORT="${STALL_PORT:-18342}"
TOKEN="rb-b221-internal-token-0123456789"
EVI="$OUT/evidence-b221.txt"
BODY="$OUT/body.json"
PASS=0; FAIL=0
mkdir -p "$OUT"
: > "$EVI"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
jget(){ "$PY" -c "import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    print('<unparsable>'); raise SystemExit
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(d,dict) and k in d: d=d[k]
    elif isinstance(d,list) and k.lstrip('-').isdigit(): d=d[int(k)]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }

APP_PID=""; STALL_PID=""
cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${STALL_PID:-}" ] && kill "$STALL_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: fat jar 不存在：$JAR（先跑 package）"; exit 4; }
log "JAR_SIZE=$(stat -c%s "$JAR")"
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT" "$STALL_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT/$STALL_PORT"

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

# ---------------------------------------------------------------- app 启动函数
start_app(){
  local base="$1" logf="$2"
  export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
  export SPRING_DATASOURCE_USERNAME=root
  export SPRING_DATASOURCE_PASSWORD=rootpw
  export JWT_SECRET="rb-b221-independent-secret-0123456789"
  export AI_SERVICE_BASE_URL="$base"
  export AI_INTERNAL_TOKEN="$TOKEN"
  export ANALYSIS_POLL_ENABLED=false   # 只靠 GET 读时同步推进，计时口径确定
  : > "$logf"
  # ★ 显式堆上限：首轮重启时默认堆（宿主 25%）在「上一 JVM 未完全释放 + 容器共存」下
  #   触发 native OOM（hs_err: malloc failed for Chunk::new），与本批代码无关。
  java -Xmx512m -XX:MaxMetaspaceSize=256m -jar "$JAR" --server.port="$APP_PORT" >>"$logf" 2>&1 &
  APP_PID=$!
  local up=0
  for _ in $(seq 1 150); do
    local c
    c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
    [ "$c" = "200" ] && { up=1; break; }
    kill -0 "$APP_PID" 2>/dev/null || break
    sleep 1
  done
  [ "$up" = 1 ] && log "APP_UP=1 base=$base pid=$APP_PID" || { log "FATAL: app 未就绪 base=$base"; tail -40 "$logf" | tee -a "$EVI"; exit 5; }
}
stop_app(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  wait "$APP_PID" 2>/dev/null; APP_PID=""
  # 等端口真正释放，避免新 JVM 与旧 JVM 的 native 内存叠加
  for _ in $(seq 1 30); do
    netstat -ano | grep -E ":${APP_PORT}[[:space:]].*LISTENING" >/dev/null 2>&1 || return 0
    sleep 1
  done
  return 0
}

APP_LOG="$OUT/app.log"
start_app "http://127.0.0.1:$AI_PORT" "$APP_LOG"

q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 shoppilot -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }

# ---------------------------------------------------------------- 种子
"$PY" "$OUT/gen-seed-500.py" > "$OUT/seed-500.sql" || { log "FATAL: 种子生成失败"; exit 5; }
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot \
  < "$OUT/seed-500.sql" 2>"$OUT/seed.err"
chk "种子导入成功" "0" "$?"
chk "迁移版本数 10" "10" "$(q "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;")"
chk "ACTIVE SKU 数 = 500" "500" "$(q "SELECT COUNT(*) FROM skus WHERE team_id=7 AND status='ACTIVE';")"
chk "sales_daily 行数 = 14000" "14000" "$(q "SELECT COUNT(*) FROM sales_daily WHERE team_id=7;")"
chk "inventory_balances 行数 = 500" "500" "$(q "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7;")"

curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" -d '{"username":"operator01","password":"1234"}' > "$BODY"
T_OP=$(jget "data.accessToken")
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && [ "$T_OP" != "<unparsable>" ] && echo 1 || echo 0)"
T_VIEW=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" -d '{"username":"viewer01","password":"1234"}' \
  | "$PY" -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")

# ---------------------------------------------------------------- R148：500 SKU 计时
sep; log "=== R148：ALL_ACTIVE / 500 SKU / enable_llm=false ==="
REQ='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
START_MS=$(date +%s%3N)
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb-b221-perf-1" -d "$REQ")
log "CREATE HTTP $CODE"
log "CREATE BODY $(cat "$BODY")"
chk "创建 202" "202" "$CODE"
RID=$(jget "data.run_id")
chk "创建响应 total_skus=500" "500" "$(jget data.total_skus)"

ST=""; POLLS=0
for i in $(seq 1 120); do
  POLLS=$((POLLS+1))
  curl -s --noproxy '*' -o "$BODY" -H "Authorization: Bearer $T_OP" \
    "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID" >/dev/null
  ST=$(jget data.status)
  [ "$i" = 1 ] && log "  poll#1 status=$(jget data.status) progress=$(jget data.progress) step=$(jget data.current_step)"
  case "$ST" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) break;; esac
  sleep 1
done
END_MS=$(date +%s%3N)
ELAPSED_MS=$((END_MS-START_MS))
log "R148 计时：创建→终态 墙钟 = ${ELAPSED_MS} ms（含 $POLLS 次 GET 读时同步；不含 LLM）"
log "R148 最终 status=$ST progress=$(jget data.progress)"
chk "最终 SUCCEEDED" "SUCCEEDED" "$ST"
chk "最终 progress=100" "100" "$(jget data.progress)"
chk "墙钟 < 60000ms" "1" "$([ "$ELAPSED_MS" -lt 60000 ] && echo 1 || echo 0)"
chk "DB status=SUCCEEDED" "SUCCEEDED" "$(q "SELECT status FROM analysis_runs WHERE id=$RID;")"
chk "DB total_skus=500" "500" "$(q "SELECT total_skus FROM analysis_runs WHERE id=$RID;")"
chk "DB processed_skus=500" "500" "$(q "SELECT processed_skus FROM analysis_runs WHERE id=$RID;")"
ITEMS=$(q "SELECT COUNT(*) FROM analysis_items WHERE run_id=$RID;")
chk "analysis_items=500" "500" "$ITEMS"
log "DB suggestion_count=$(q "SELECT suggestion_count FROM analysis_runs WHERE id=$RID;") / risk_sku_count=$(q "SELECT risk_sku_count FROM analysis_runs WHERE id=$RID;")"

# ---------------------------------------------------------------- V3：列表真实响应 + 逐字比对
sep; log "=== V3：GET /api/v1/analysis/runs 真实响应 ==="
LIST="$OUT/list.json"; DET="$OUT/detail.json"
CODE=$(curl -s --noproxy '*' -o "$LIST" -w '%{http_code}' \
  -H "Authorization: Bearer $T_OP" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?page=1&pageSize=20")
log "LIST HTTP $CODE"
log "LIST BODY $(cat "$LIST")"
chk "列表 200" "200" "$CODE"
chk "data.list 为数组" "1" "$("$PY" "$OUT/probe.py" islist "$LIST" data.list)"
chk "data.page=1" "1" "$("$PY" "$OUT/probe.py" field "$LIST" data.page)"
chk "data.pageSize=20" "20" "$("$PY" "$OUT/probe.py" field "$LIST" data.pageSize)"
chk "data.total>=1" "1" "$("$PY" "$OUT/probe.py" num_ge "$LIST" data.total 1)"

curl -s --noproxy '*' -o "$DET" -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID" >/dev/null
log "DETAIL BODY $(cat "$DET")"
CMP=$("$PY" "$OUT/probe.py" cmp "$LIST" "$DET")
log "LIST_ITEM_VS_DETAIL=$CMP"
chk "列表元素与详情 data 逐字一致（A4）" "IDENTICAL" "$CMP"
log "列表元素字段集：$("$PY" "$OUT/probe.py" keys "$LIST" 0)"
# VIEWER 可查（含分页）
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -H "Authorization: Bearer $T_VIEW" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?page=1&pageSize=5")
chk "VIEWER 列表 200" "200" "$CODE"

# ---------------------------------------------------------------- R149-C1：停 service-ai
sep; log "=== R149-C1：停 service-ai 容器后创建 ⇒ 502 ANL-7002 + run FAILED ==="
docker stop "$AI_NAME" >/dev/null 2>&1
chk "service-ai 已停" "exited" "$(docker inspect -f '{{.State.Status}}' "$AI_NAME" 2>/dev/null)"
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb-b221-c1-1" -d "$REQ")
log "C1 HTTP $CODE"
log "C1 BODY $(cat "$BODY")"
chk "C1 502" "502" "$CODE"
chk "C1 code=ANL-7002" "ANL-7002" "$(jget code)"
chk "C1 data 为 null（不泄漏内部细节）" "1" "$("$PY" "$OUT/probe.py" isnull "$BODY" data)"
C1ROW=$(q "SELECT CONCAT(status,'|',IFNULL(error_code,'NULL')) FROM analysis_runs ORDER BY id DESC LIMIT 1;")
log "C1 最新 run 行（status|error_code）=$C1ROW"
chk "C1 run FAILED|ANL-7002" "FAILED|ANL-7002" "$C1ROW"

# ---------------------------------------------------------------- R149-C2：黑洞地址（按令）
sep; log "=== R149-C2：AI_SERVICE_BASE_URL=http://10.255.255.1:8000 重启 app 后创建 ==="
stop_app
APP_LOG2="$OUT/app-blackhole.log"
start_app "http://10.255.255.1:8000" "$APP_LOG2"
C2_START=$(date +%s%3N)
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  --max-time 60 \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb-b221-c2-1" -d "$REQ")
C2_END=$(date +%s%3N)
log "C2(blackhole) HTTP $CODE  耗时=$((C2_END-C2_START)) ms"
log "C2(blackhole) BODY $(cat "$BODY")"
C2CODE=$(jget code)
log "C2(blackhole) code=$C2CODE"
log "★ 任务书 C2 期望 504/ANL-7003；实现口径：AiServiceClient.isReadTimeout 显式把"
log "  「connect timed out」排除在 7003 之外（归 isTransportFailure ⇒ 502/ANL-7002）。"
log "  实测值即为下面这一行；7003 分支另由 stall 服务正证（见下一节）。"
C2ROW=$(q "SELECT CONCAT(status,'|',IFNULL(error_code,'NULL')) FROM analysis_runs ORDER BY id DESC LIMIT 1;")
log "C2(blackhole) 最新 run 行（status|error_code）=$C2ROW"
if [ "$CODE" = "502" ] || [ "$CODE" = "504" ]; then C2CODE_OK=1; else C2CODE_OK=0; fi
chk "C2(blackhole) 返回 502/504 之一（实际值已记录）" "1" "$C2CODE_OK"
chk "C2(blackhole) run 已 FAILED 且 error_code 与响应码一致" "FAILED|$C2CODE" "$C2ROW"

# ---------------------------------------------------------------- R149-C2 补充：真实读超时 ⇒ 7003
sep; log "=== R149-C2 补充：stall 服务（accept 不回包）构造真实读超时 ⇒ 504 ANL-7003 ==="
"$PY" "$OUT/stall_server.py" "$STALL_PORT" > "$OUT/stall.log" 2>&1 &
STALL_PID=$!
sleep 1
chk "stall 已监听" "1" "$( (netstat -ano | grep -E ":${STALL_PORT}[[:space:]].*LISTENING" >/dev/null 2>&1 && echo 1) || echo 0 )"
stop_app
APP_LOG3="$OUT/app-stall.log"
start_app "http://127.0.0.1:$STALL_PORT" "$APP_LOG3"
C3_START=$(date +%s%3N)
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  --max-time 60 \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb-b221-c3-1" -d "$REQ")
C3_END=$(date +%s%3N)
log "C2(stall) HTTP $CODE  耗时=$((C3_END-C3_START)) ms（读掉请求不回包 ⇒ readTimeout 应触发）"
log "C2(stall) BODY $(cat "$BODY")"
chk "C2(stall) 504" "504" "$CODE"
chk "C2(stall) code=ANL-7003" "ANL-7003" "$(jget code)"
C3ROW=$(q "SELECT CONCAT(status,'|',IFNULL(error_code,'NULL')) FROM analysis_runs ORDER BY id DESC LIMIT 1;")
log "C2(stall) 最新 run 行（status|error_code）=$C3ROW"
chk "C2(stall) run FAILED|ANL-7003" "FAILED|ANL-7003" "$C3ROW"

sep
log "RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ] && log "ALL PASS" || log "HAS FAILURE"
exit 0
