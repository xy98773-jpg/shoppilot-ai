#!/usr/bin/env bash
# =============================================================================
# M4-B20.4 项目负责人独立复核探针（不采信开发自报）
# -----------------------------------------------------------------------------
# 与开发 V1–V6 的差异（刻意）：
#   1) **真实 service-ai 容器**：validate 接线必须由真实预检端点判定，不用 Mock；
#   2) 独立 MySQL + 独立 fat jar + 独立端口（3363/18271/18282/18283），零共用；
#   3) 重点打「SSOT 明文但开发未覆盖」：
#      X1 validate analyzable=false **端到端**（真 Python 判定 → 422 ANL-7008 + run FAILED）
#      X2 并发取消（两请求同时打，审计必须恰 1 条）
#      X3 取消后 items 仍 409 ANL-7012
#      X4 跨团队取消不得改他团队 run
#      X5 pageSize=0 的 details.field 与 R142 的 snake_case 是否一致（开发自报的既有差异）
#      X6 取消不额外记 FINISH、幂等重放 cancelled_at 逐字不变
#      X7 未登录取消 ⇒ 401（安全链）
# 生命周期：trap cleanup。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b204-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b204r-mysql"
AI_NAME="rb-b204r-ai"
MYSQL_PORT="${MYSQL_PORT:-3363}"
APP_PORT="${APP_PORT:-18271}"
AI_PORT="${AI_PORT:-18282}"
AI_PROXY_PORT="${AI_PROXY_PORT:-18283}"
TOKEN="rb-b204r-internal-token-0123456789"
EVI="$OUT/evidence-b204-review.txt"
CAP="$OUT/ai-captured.jsonl"
BODY_M=$(cygpath -m "$OUT/body.json")
PASS=0; FAIL=0
mkdir -p "$OUT/storage"
: > "$EVI"; : > "$CAP"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
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
log "JAR_SIZE=$(stat -c%s "$JAR")"
for p in "$MYSQL_PORT" "$APP_PORT" "$AI_PORT" "$AI_PROXY_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$AI_PORT/$AI_PROXY_PORT"

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
log "SERVICE_AI_UP=1 (port $AI_PORT)"

"$PY" "$OUT/ai_proxy.py" "$AI_PORT" "$AI_PROXY_PORT" "$CAP" >"$OUT/proxy.out" 2>&1 &
PROXY_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PROXY_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: 抓包反代未就绪"; exit 5; }
log "CAPTURE_PROXY_UP=1"

export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b204r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PROXY_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
export ANALYSIS_POLL_ENABLED=false
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

q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$OUT/seed-b201.sql" 2>"$OUT/seed.err"
chk "种子导入成功" "0" "$?"

login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"1234\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_VW=$(login viewer01); T_T8=$(login owner8)
chk "三账号登录" "1|1|1" "$([ -n "$T_OP" ] && echo 1 || echo 0)|$([ -n "$T_VW" ] && echo 1 || echo 0)|$([ -n "$T_T8" ] && echo 1 || echo 0)"

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
BODY_INSUFF='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9003]},"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
post_create(){ local token="$1" key="$2" body="$3"
  CREATE_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
    -H "Content-Type: application/json" -H "Authorization: Bearer $token" \
    -H "Idempotency-Key: $key" -d "$body")
}
cancel(){ local token="$1" rid="$2"
  CANCEL_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$rid/cancel" \
    -H "Authorization: Bearer $token")
}
get_items(){ ITEMS_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
    -H "Authorization: Bearer $1" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$2/items$3"); }

# ================================ S 结构面
sep; log "=== S 结构面 ==="
JAR_WIN=$(cygpath -w "$JAR")
chk "fat jar 内含 V1.0.9" "1" "$("$PY" -c "import zipfile,sys;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('V1.0.9__analysis.sql')))" "$JAR_WIN")"
chk "迁移版本数 10" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"

# ================================ T1 validate 通过 → 正常创建
sep; log "=== T1 R131：validate 通过 → 202 → 终态 ==="
post_create "$T_OP" "b204-t1-all" "$BODY_ALL"
chk "T1a 创建 202" "202" "$CREATE_STATUS"
RID_OK=$(jf data.run_id)
note "run_id=$RID_OK"
ST=""; for _ in $(seq 1 60); do
  curl -s --noproxy '*' -o "$OUT/body.json" -H "Authorization: Bearer $T_OP" \
    "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_OK" >/dev/null
  ST=$(jf data.status)
  case "$ST" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) break;; esac
  sleep 2
done
chk "T1b 终态 SUCCEEDED（validate 放行）" "SUCCEEDED" "$ST"
chk "T1c 捕获到 analysis:validate 调用" "1" "$(grep -c 'analysis:validate' "$CAP" | awk '{print ($1>0)?1:0}')"
note "T1 抓包路径计数：$(grep -o '"path": *"[^"]*"' "$CAP" | sort | uniq -c | tr '\n' ' ')"
note "T1 DB: $(q "SELECT CONCAT(status,'|',progress,'|',COALESCE(current_step,'-')) FROM shoppilot.analysis_runs WHERE id=$RID_OK;")"
note "T1 读时同步日志: $(grep -c "读时同步\|syncFromPython\|同步失败" "$APP_LOG") 行"
note "T1 app.log 尾部: $(tail -5 "$APP_LOG" | tr '\n' ';')"

# ================================ T2 validate analyzable=false（真 Python 判定）
sep; log "=== T2 R131：analyzable=false（SKU 9003 无销售）→ 422 ANL-7008 + run FAILED ==="
post_create "$T_OP" "b204-t2-insuff" "$BODY_INSUFF"
chk "T2a HTTP 422" "422" "$CREATE_STATUS"
chk "T2b code ANL-7008" "ANL-7008" "$(jf code)"
RID_BAD=$(q "SELECT id FROM shoppilot.analysis_runs WHERE idempotency_key='b204-t2-insuff';")
chk "T2c run 已落库（不是没建）" "1" "$([ -n "$RID_BAD" ] && echo 1 || echo 0)"
chk "T2d run status=FAILED（不留 PENDING）" "FAILED" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_BAD;")"
chk "T2e error_code=ANL-7008" "ANL-7008" "$(q "SELECT error_code FROM shoppilot.analysis_runs WHERE id=$RID_BAD;")"
chk "T2f finished_at 非空" "1" "$(q "SELECT finished_at IS NOT NULL FROM shoppilot.analysis_runs WHERE id=$RID_BAD;")"
chk "T2g FINISH(FAILURE) 恰 1 条" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_BAD AND action='ANALYSIS_RUN_FINISH' AND result='FAILURE';")"
chk "T2h 无 CREATE 审计" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_BAD AND action='ANALYSIS_RUN_CREATE';")"
chk "T2i 未调用 runs 发起（Python 侧无该 run 的 202）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_BAD;")"

# ================================ T3 取消
sep; log "=== T3 M4-T14 取消：PENDING / RUNNING / 终态 / 幂等 ==="
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B204-PEND','PENDING','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','NOT_REQUESTED',5,0,0,1001,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_P=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-PEND';")
cancel "$T_OP" "$RID_P"
chk "T3a PENDING→取消 200" "200" "$CANCEL_STATUS"
chk "T3b data 三字段" "$RID_P|CANCELLED" "$(jf data.run_id)|$(jf data.status)"
chk "T3c cancelled_at ISO UTC 毫秒" "1" "$("$PY" -c "import json,re;d=json.load(open('$BODY_M',encoding='utf-8'))['data'];print(1 if re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z',d['cancelled_at']) else 0)")"
chk "T3d DB status/current_step" "CANCELLED|CANCELLED" "$(q "SELECT CONCAT(status,'|',current_step) FROM shoppilot.analysis_runs WHERE id=$RID_P;")"
chk "T3e finished_at 非空" "1" "$(q "SELECT finished_at IS NOT NULL FROM shoppilot.analysis_runs WHERE id=$RID_P;")"
chk "T3f 审计 CANCEL 恰 1 条" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P AND action='ANALYSIS_RUN_CANCEL' AND result='SUCCESS';")"
chk "T3g 不额外记 FINISH" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P AND action='ANALYSIS_RUN_FINISH';")"
FIRST_AT=$(jf data.cancelled_at)
cancel "$T_OP" "$RID_P"
chk "T3h 重复取消 200 幂等" "200" "$CANCEL_STATUS"
chk "T3i cancelled_at 逐字不变" "$FIRST_AT" "$(jf data.cancelled_at)"
chk "T3j 重放不重复审计" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P AND action='ANALYSIS_RUN_CANCEL';")"
# RUNNING
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B204-RUN','RUNNING','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','PENDING',5,2,40,1001,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_R=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-RUN';")
cancel "$T_OP" "$RID_R"
chk "T3k RUNNING→取消 200" "200|CANCELLED" "$CANCEL_STATUS|$(jf data.status)"
get_items "$T_OP" "$RID_R" ""
chk "T3l 取消后 items 仍 409 ANL-7012" "409|ANL-7012" "$ITEMS_STATUS|$(jf code)"
# 终态
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B204-SUCC','SUCCEEDED','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','SKIPPED',0,0,100,1001,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_S=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-SUCC';")
cancel "$T_OP" "$RID_S"
chk "T3m 终态 ⇒ 422 ANL-7011" "422|ANL-7011" "$CANCEL_STATUS|$(jf code)"
chk "T3n 终态状态不变" "SUCCEEDED" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_S;")"
chk "T3o 终态无 CANCEL 审计" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_S AND action='ANALYSIS_RUN_CANCEL';")"

# 不存在 / 跨团队 / VIEWER / 未登录
sep; log "=== T4 取消的边界与权限 ==="
cancel "$T_OP" "999999"
chk "T4a 不存在 ⇒ 404 ANL-7005" "404|ANL-7005" "$CANCEL_STATUS|$(jf code)"
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (8,'RUN-B204-OTHER','RUNNING','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','PENDING',1,0,0,1002,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_O=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-OTHER';")
cancel "$T_OP" "$RID_O"
chk "T4b 跨团队 ⇒ 404 ANL-7005" "404|ANL-7005" "$CANCEL_STATUS|$(jf code)"
chk "T4c 他团队 run 未被改" "RUNNING" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_O;")"
cancel "$T_T8" "$RID_P"
note "T4d 他团队 token 取消本团队已取消 run 实测=$CANCEL_STATUS/$(jf code)（应为 404）"
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B204-VW','PENDING','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','NOT_REQUESTED',1,0,0,1001,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_V=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-VW';")
cancel "$T_VW" "$RID_V"
chk "T4e VIEWER ⇒ 403 TEAM-2003" "403|TEAM-2003" "$CANCEL_STATUS|$(jf code)"
chk "T4f VIEWER 取消后状态不变" "PENDING" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_V;")"
CANCEL_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
  -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_V/cancel")
chk "T4g 未登录取消 ⇒ 401" "401" "$CANCEL_STATUS"

# 并发取消（两请求同时）
sep; log "=== T5 并发取消（两请求同时打，审计必须恰 1） ==="
q "INSERT INTO shoppilot.analysis_runs (team_id, run_no, status, scope_type, params_json, algorithm_version, llm_status, total_skus, processed_skus, progress, triggered_by, timeout_at) VALUES (7,'RUN-B204-RACE','PENDING','ALL_ACTIVE','{\"window_days\":28}','avg-ma-3sigma-v1','NOT_REQUESTED',1,0,0,1001,NOW(3)+INTERVAL 300 SECOND);" 2>/dev/null
RID_C=$(q "SELECT id FROM shoppilot.analysis_runs WHERE run_no='RUN-B204-RACE';")
curl -s --noproxy '*' -o "$OUT/c1.json" -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_C/cancel" -H "Authorization: Bearer $T_OP" > "$OUT/c1.code" &
P1=$!
curl -s --noproxy '*' -o "$OUT/c2.json" -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_C/cancel" -H "Authorization: Bearer $T_OP" > "$OUT/c2.code" &
P2=$!
wait $P1 $P2
chk "T5a 两个并发请求都 200" "200|200" "$(cat "$OUT/c1.code")|$(cat "$OUT/c2.code")"
chk "T5b 并发下审计恰 1 条" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_C AND action='ANALYSIS_RUN_CANCEL';")"
chk "T5c 最终状态 CANCELLED" "CANCELLED" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_C;")"
chk "T5d 两个响应 cancelled_at 一致" "$("$PY" -c "import json;print(json.load(open('$OUT/c1.json',encoding='utf-8'))['data']['cancelled_at'])")" "$("$PY" -c "import json;print(json.load(open('$OUT/c2.json',encoding='utf-8'))['data']['cancelled_at'])")"

# ================================ T6 R142
sep; log "=== T6 R142：类型转换 400 COM-0001 ==="
raw_items(){ ITEMS_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -w '%{http_code}' \
  -H "Authorization: Bearer $T_OP" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_S/items$1"); }
raw_items "?page=abc"
chk "T6a page=abc ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
chk "T6b field=page" "page" "$(jf details.0.field)"
chk "T6c rejectedValue=abc" "abc" "$(jf details.0.rejectedValue)"
raw_items "?pageSize=abc"
chk "T6d pageSize=abc ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
chk "T6e field=page_size（snake_case）" "page_size" "$(jf details.0.field)"
raw_items "?pageSize=0"
chk "T6f pageSize=0 ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
note "T6g pageSize=0 的 details.field 实测=[$(jf details.0.field)]（R142 handler=page_size，手工 badParam 可能是 pageSize——观察项）"
raw_items "?page=0"
chk "T6h page=0 ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
note "T6i page=0 的 details.field 实测=[$(jf details.0.field)]"
raw_items "?risk_level=BOGUS"
chk "T6j risk_level 非法 ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"
note "T6k risk_level 的 details.field 实测=[$(jf details.0.field)]"
raw_items "?sort=illegal,desc"
chk "T6l 非法 sort ⇒ 400 COM-0001" "400|COM-0001" "$ITEMS_STATUS|$(jf code)"

# ================================ T7 取消后结果丢弃
sep; log "=== T7 取消后 Python 结果不得落库 ==="
q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_R;"
chk "T7a 被取消 run 的 items 恒 0" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_R;")"
curl -s --noproxy '*' -o "$OUT/body.json" -H "Authorization: Bearer $T_OP" \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RID_R" >/dev/null
chk "T7b 取消后 GET status 仍 CANCELLED（读时同步不复活）" "CANCELLED" "$(jf data.status)"

# ---------------------------------------------------------------- 汇总
sep
log "RB204_CHECK_TOTAL=$((PASS+FAIL))  RB204_FAIL_COUNT=$FAIL  RB204_RESULT=$([ "$FAIL" = 0 ] && echo 'ALL PASS' || echo 'HAS FAILURES')"
exit 0
