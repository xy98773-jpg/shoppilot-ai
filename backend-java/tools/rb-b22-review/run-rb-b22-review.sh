#!/usr/bin/env bash
# =============================================================================
# M4-B22 项目负责人复核探针（M4 大章验收：F-ANL-09 列表 + R148 性能 + R149 负向）
# -----------------------------------------------------------------------------
# 不采信开发自报。独立 MySQL / 独立 fat jar / 独立端口 / 真实 service-ai 容器。
# 断言自建（33 条开发断言不复用）；仅复用其「500 SKU 种子生成器」与「stall 工具」
# 两类**测试基础设施**（纯数据/纯服务，无断言逻辑）。
#
# 重点验三条 SSOT 明文且易漏的点：
#   P1 列表响应字段名是 `list`（不是 items）+ total/page/pageSize（记忆铁律）
#   P2 R148：500 SKU 创建→终态 < 60s 且 analysis_items=500（M4 验收门槛 #13）
#   P3 R149：连接类失败 ⇒ 502/ANL-7002；读超时 ⇒ 504/ANL-7003（两个码都要真栈可达）
# 生命周期：trap cleanup。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b22-review"
GEN="$ROOT/backend-java/tools/rb-b221/gen-seed-500.py"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b22r-mysql"
AI_NAME="rb-b22r-ai"
MYSQL_PORT="${MYSQL_PORT:-3384}"
APP_PORT="${APP_PORT:-18350}"
AI_PORT="${AI_PORT:-18351}"
STALL_PORT="${STALL_PORT:-18352}"
TOKEN="rb-b22r-internal-token-0123456789"
EVI="$OUT/evidence-b22-review.txt"
PASS=0; FAIL=0
mkdir -p "$OUT"
: > "$EVI"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
note(){ log "  NOTE $*"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${STALL_PID:-}" ] && kill "$STALL_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
  docker rm -f "$AI_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: jar 不存在 $JAR"; exit 4; }
log "JAR_SIZE=$(stat -c%s "$JAR")"
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT" "$STALL_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT/$STALL_PORT"

# ---------------------------------------------------------------- MySQL
docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
docker run -d --name "$MYSQL_NAME" -e MYSQL_ROOT_PASSWORD=rootpw -e MYSQL_DATABASE=shoppilot \
  -p "$MYSQL_PORT:3306" mysql:8.0.36 --default-time-zone=+00:00 >/dev/null 2>&1
for _ in $(seq 1 90); do
  docker exec "$MYSQL_NAME" mysqladmin -h127.0.0.1 -uroot -prootpw ping --silent >/dev/null 2>&1 && break
  sleep 2
done
log "MYSQL_READY=1"
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }

# ---------------------------------------------------------------- 真实 service-ai
docker rm -f "$AI_NAME" >/dev/null 2>&1
MSYS_NO_PATHCONV=1 docker run -d --name "$AI_NAME" -p "$AI_PORT:8000" \
  -v "$ROOT/service-ai:/app" -w /app -e PYTHONPATH=/app/src -e AI_INTERNAL_TOKEN="$TOKEN" \
  shoppilot-dev-service-ai:latest uvicorn shoppilot_ai.app.main:app --host 0.0.0.0 --port 8000 >/dev/null 2>&1
AUP=0
for _ in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PORT/internal/v1/health" || echo 000)" = "200" ] && { AUP=1; break; }
  sleep 1
done
[ "$AUP" = 1 ] || { log "FATAL: service-ai 未就绪"; docker logs "$AI_NAME" 2>&1 | tail -15 | tee -a "$EVI"; exit 5; }
log "SERVICE_AI_UP=1 (port $AI_PORT)"

# ---------------------------------------------------------------- 种子（500 SKU）
# ★ 必须在 app 启动（Flyway 跑完迁移）之后导入，否则 DELETE refresh_tokens 会撞「表不存在」。
"$PY" "$GEN" > "$OUT/seed-500.sql" 2>"$OUT/gen.err"
chk "种子生成成功（500 SKU）" "0" "$?"

# ---------------------------------------------------------------- app（polling 关，只靠读时同步）
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b22r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
export ANALYSIS_POLL_ENABLED=false
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -Xmx1024m -jar "$JAR" --server.port="$APP_PORT" >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 150); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -30 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT, ANALYSIS_POLL_ENABLED=false)"

# ---------------------------------------------------------------- 种子导入（app 已跑完 Flyway）
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$OUT/seed-500.sql" 2>"$OUT/seed.err"
chk "种子导入成功" "0" "$?"
chk "迁移版本数 10" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"
chk "ACTIVE SKU = 500" "500" "$(q "SELECT COUNT(*) FROM shoppilot.skus WHERE team_id=7 AND status='ACTIVE';")"

login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"S3cret!Passw0rd\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_VW=$(login viewer01); T_T8=$(login owner8)
chk "operator01 / viewer01 / owner8 登录" "111" "$([ -n "$T_OP" ] && echo -n 1 || echo -n 0)$([ -n "$T_VW" ] && echo -n 1 || echo -n 0)$([ -n "$T_T8" ] && echo -n 1 || echo -n 0)"

jf(){ "$PY" - "$OUT/body.json" "$1" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
cur=d
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(cur,dict) and k in cur: cur=cur[k]
    elif isinstance(cur,list) and k.lstrip('-').isdigit():
        i=int(k)
        if -len(cur) <= i < len(cur): cur=cur[i]
        else: print('<index-oob>'); raise SystemExit
    else: print('<missing:%s>'%k); raise SystemExit
print('' if cur is None else cur)
PY
}

BODY_ALL='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'

# ================================ P2 / R148 性能
sep; log "=== P2 R148：ALL_ACTIVE 500 SKU 创建→终态 < 60s ==="
T0=$(date +%s%3N)
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb22r-r148-500" -H "X-Request-Id: rb22r-r148" \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" -d "$BODY_ALL")
RID=$(jf data.run_id)
chk "创建 202" "202" "$CODE"
chk "创建响应 total_skus=500（装配后实际数）" "500" "$(jf data.total_skus)"
ST=""; for i in $(seq 1 60); do
  curl -s --noproxy '*' -o "$OUT/body.json" -H "Authorization: Bearer $T_OP" \
    "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID" >/dev/null
  ST=$(jf data.status)
  case "$ST" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) break;; esac
  sleep 1
done
T1=$(date +%s%3N); ELAPSED=$((T1 - T0))
note "R148 墙钟 = ${ELAPSED} ms（含读时同步延迟，不含 LLM）"
chk "终态 SUCCEEDED" "SUCCEEDED" "$ST"
chk "墙钟 < 60000ms" "1" "$([ "$ELAPSED" -lt 60000 ] && echo 1 || echo 0)"
chk "DB status=SUCCEEDED" "SUCCEEDED" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID;")"
chk "DB analysis_items=500" "500" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID;")"
chk "DB total_skus=processed_skus=500" "500|500" "$(q "SELECT CONCAT(total_skus,'|',processed_skus) FROM shoppilot.analysis_runs WHERE id=$RID;")"

# ================================ P1 列表端点
sep; log "=== P1 F-ANL-09：GET /runs 列表（字段名/分页/权限/过滤/排序） ==="
CODE=$(curl -s --noproxy '*' -o "$OUT/list.json" -w '%{http_code}' \
  -H "Authorization: Bearer $T_OP" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?page=1&pageSize=20")
chk "列表 200" "200" "$CODE"
cp "$OUT/list.json" "$OUT/body.json"   # 后续 jf 统一读 body.json
lj(){ "$PY" - "$OUT/list.json" "$1" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))['data']
e=sys.argv[2]
if e=='is_list': print(1 if isinstance(d.get('list'),list) and 'items' not in d else 0)
elif e=='total_ge1': print(1 if d['total']>=1 else 0)
elif e=='field_count': print(len(d['list'][0]))
elif e=='fields': print(','.join(sorted(d['list'][0])))
elif e=='has_created_at': print(1 if 'created_at' in d['list'][0] else 0)
PY
}
chk "★ data.list 是数组（不是 items）" "1" "$(lj is_list)"
chk "data.page=1 / pageSize=20" "1|20" "$(jf data.page)|$(jf data.pageSize)"
chk "data.total>=1" "1" "$(lj total_ge1)"
chk "列表项字段数=17（与详情同构）" "17" "$(lj field_count)"
note "列表项字段：$(lj fields)"
chk "R150 核验：列表项是否含 created_at（预期 0=不含）" "0" "$(lj has_created_at)"

# 列表项 vs 详情
curl -s --noproxy '*' -o "$OUT/detail.json" -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID"
chk "列表首项与详情 data 逐字一致" "IDENTICAL" "$("$PY" - "$OUT/list.json" "$OUT/detail.json" <<'PY'
import json,sys
l=json.load(open(sys.argv[1],encoding='utf-8'))['data']['list'][0]
d=json.load(open(sys.argv[2],encoding='utf-8'))['data']
print('IDENTICAL' if l==d else 'DIFF')
PY
)"

# 权限
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_VW" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?page=1&pageSize=20")
chk "VIEWER 列表 200" "200" "$CODE"
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_T8" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?page=1&pageSize=20")
chk "跨团队列表 200（隔离为 0 条）" "200|0" "$CODE|$(jf data.total)"

# 过滤与排序
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?status=SUCCEEDED")
chk "status=SUCCEEDED 过滤命中" "1" "$([ "$(jf data.total)" -ge 1 ] && echo 1 || echo 0)"
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?status=FAILED")
chk "status=FAILED 过滤为 0 条" "0" "$(jf data.total)"
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?sort=not_a_column,desc")
chk "非法 sort ⇒ 400 COM-0001" "400|COM-0001" "$CODE|$(jf code)"
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs?pageSize=201")
chk "pageSize 超上限 ⇒ 400 COM-0001" "400|COM-0001" "$CODE|$(jf code)"

# ================================ P3 / R149 负向（破坏性，放最后）
sep; log "=== P3 R149-C1：停 service-ai ⇒ 502 / ANL-7002 + run FAILED ==="
docker rm -f "$AI_NAME" >/dev/null 2>&1
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb22r-r149-c1" -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" -d "$BODY_ALL")
chk "C1 HTTP 502" "502" "$CODE"
chk "C1 code=ANL-7002" "ANL-7002" "$(jf code)"
chk "C1 run FAILED|ANL-7002" "FAILED|ANL-7002" "$(q "SELECT CONCAT(status,'|',error_code) FROM shoppilot.analysis_runs WHERE idempotency_key='rb22r-r149-c1';")"

sep; log "=== P3 R149-C2：stall（连上不回包）⇒ 504 / ANL-7003 + run FAILED ==="
"$PY" "$OUT/stall_server.py" "$STALL_PORT" >"$OUT/stall.out" 2>&1 &
STALL_PID=$!
sleep 1
kill "$APP_PID" 2>/dev/null; wait "$APP_PID" 2>/dev/null
export AI_SERVICE_BASE_URL="http://127.0.0.1:$STALL_PORT"
APP_LOG2="$OUT/app-stall.log"; : > "$APP_LOG2"
java -Xmx1024m -jar "$JAR" --server.port="$APP_PORT" >>"$APP_LOG2" 2>&1 &
APP_PID=$!
for _ in $(seq 1 150); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)" = "200" ] && break
  sleep 1
done
U2=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health")
chk "app(stall 模式) 就绪" "200" "$U2"
CODE=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
  -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
  -H "Idempotency-Key: rb22r-r149-c2" -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" -d "$BODY_ALL")
chk "C2 HTTP 504" "504" "$CODE"
chk "C2 code=ANL-7003" "ANL-7003" "$(jf code)"
chk "C2 run FAILED|ANL-7003" "FAILED|ANL-7003" "$(q "SELECT CONCAT(status,'|',error_code) FROM shoppilot.analysis_runs WHERE idempotency_key='rb22r-r149-c2';")"

sep
log "TOTAL PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ] && log "RESULT=ALL_PASS" || log "RESULT=HAS_FAIL"
exit 0
