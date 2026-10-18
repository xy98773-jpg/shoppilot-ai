#!/usr/bin/env bash
# =============================================================================
# B13 真栈取证：Java ↔ Python 的「认证头 + 链路追踪头」贯通
#
# 独立性：自建 fat jar + 独立 MySQL 容器 + 独立 uvicorn 进程，**不**复用任何 dev 栈。
# 端口：MySQL 3365 / Java 18290 / Python 18090（启动前 netstat -ano 实测空闲）。
#   已用：3306、3336、3319、3327–3342、3355–3357、3360、3361、3363、3364、8080、8081、8082、
#         18099、18107–18210、18220、18230、18240、18250、18270、18280。
#   ★ 3350 被宿主 WorkBuddy.exe 占用（禁用）。
#
# 探针（对应任务书【5】P1~P9）：
#   P1 GET  /internal/v1/health（不带 token）           → 200 {"status":"UP"}
#   P2 POST /internal/v1/analysis:validate（不带 token） → 401 AI-0001
#   P3 同上（带错 token）                                → 401 AI-0001
#   P4 同上（带对 token）                                → 非 401（记录真实码）
#   P5 401 必带 X-Request-Id，且 body.request_id 与响应头逐字相等
#   P6 显式 X-Request-Id: req_b13_probe_0001             → 原样回显
#   P7 重启 Python 且清空 AI_INTERNAL_TOKEN              → P2 仍 401、P1 仍 200（fail-closed）
#   P8 Java GET /api/v1/health 带 req_b13_probe_0002     → 响应头回显 + body.requestId 一致
#   P9（加分）非法 JSON body + 对 token → 400 AI-1001     —— 本批无业务端点，记 N/A（见 §汇总）
#
# 用法：
#   cd backend-java && ./mvnw -B clean verify        # 产出 fat jar
#   bash backend-java/tools/rb-b13/run-rb-b13.sh
#   可覆盖： RB13_MYSQL_PORT / RB13_APP_PORT / RB13_PY_PORT / PYTHON_BIN
#
# 产物：
#   backend-java/tools/rb-b13/evidence-rb-b13.txt   ← 断言日志（入库）
#   backend-java/tools/rb-b13/*.log                 ← 应用 stdout（*.log 已 gitignore）
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
EVI="$OUT_DIR/evidence-rb-b13.txt"
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"

MYSQL_CT="rb-b13-mysql"
MYSQL_PORT="${RB13_MYSQL_PORT:-3365}"
APP_PORT="${RB13_APP_PORT:-18290}"
PY_PORT="${RB13_PY_PORT:-18090}"
TOKEN="rb-b13-token"
WRONG_TOKEN="rb-b13-wrong"
REQ_ID_1="req_b13_probe_0001"
REQ_ID_2="req_b13_probe_0002"

JAVA_LOG="$OUT_DIR/java.log"
PY_LOG="$OUT_DIR/python.log"
HDR="$OUT_DIR/last-headers.log"
BODY="$OUT_DIR/last-body.log"

# Python 解释器：默认取环境里的 python；本机受管 venv 可用 PYTHON_BIN 覆盖
PYTHON_BIN="${PYTHON_BIN:-python}"

: > "$EVI"; : > "$JAVA_LOG"; : > "$PY_LOG"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-----------------------------------------------------------------"; }

FAIL=0
FAILED_LIST=""
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=[$2] actual=[$3]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi; }
chkne(){ if [ "$2" != "$3" ]; then log "PASS $1 => $3（≠$2）"; else log "FAIL $1 不得等于[$2]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 不得等于[$2]"; fi; }
chknz(){ if [ -n "$2" ]; then log "PASS $1 => 非空[$2]"; else log "FAIL $1 期望非空"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 期望非空"; fi; }
jget(){ printf '%s' "$1" | "$PYTHON_BIN" "$OUT_DIR/jget.py" "$2" 2>/dev/null; }

cleanup(){
  log "--- cleanup ---"
  [ -n "${JAVA_PID:-}" ] && kill "$JAVA_PID" 2>/dev/null
  [ -n "${PY_PID:-}" ] && kill "$PY_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --------------------------------------------------------------- 前置检查
if [ ! -f "$JAR" ]; then log "FATAL fat jar 不存在：$JAR（先 ./mvnw -B clean verify）"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT java=$APP_PORT python=$PY_PORT"
log "PYTHON_BIN=$PYTHON_BIN ($("$PYTHON_BIN" -V 2>&1))"
log "TOKEN=$TOKEN（写进日志是测试专用值，非生产密钥）"

# --------------------------------------------------------------- 起 MySQL
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$JAVA_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1 && { READY=1; break; }
  sleep 2
done
log "MYSQL_READY=$READY (独立容器 $MYSQL_CT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

# --------------------------------------------------------------- 起 Java
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="rb-b13-probe-secret-0123456789abcdef" \
AI_INTERNAL_TOKEN="$TOKEN" \
IMPORT_STORAGE_DIR="$OUT_DIR/storage" \
java -jar "$JAR" --server.port="$APP_PORT" >> "$JAVA_LOG" 2>&1 &
JAVA_PID=$!
log "JAVA_PID=$JAVA_PID port=$APP_PORT"
JUP=0
for i in $(seq 1 90); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { JUP=1; break; }
  sleep 2
done
log "JAVA_UP=$JUP"
[ "$JUP" = "1" ] || { log "FATAL java not up"; tail -30 "$JAVA_LOG" >> "$EVI"; exit 4; }

# --------------------------------------------------------------- 起 Python
start_python(){ # $1 = token（可为空串）
  # ★ exec：让子 shell 被 uvicorn 替换，$! 才是 uvicorn 的 pid，
  #   否则 kill $! 只杀掉外层 subshell，uvicorn 变孤儿继续占用端口（P7 重启会失败）。
  ( cd "$ROOT/service-ai" && AI_INTERNAL_TOKEN="$1" PYTHONPATH=src \
      exec "$PYTHON_BIN" -m uvicorn shoppilot_ai.app.main:app \
      --host 127.0.0.1 --port "$PY_PORT" >> "$PY_LOG" 2>&1 ) &
  PY_PID=$!
}
wait_python(){
  for i in $(seq 1 60); do
    c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$PY_PORT/internal/v1/health" || echo 000)
    [ "$c" = "200" ] && return 0
    sleep 1
  done
  return 1
}

start_python "$TOKEN"
if wait_python; then log "PYTHON_UP=1 (pid=$PY_PID, token=$TOKEN)"; else
  log "PYTHON_UP=0"; tail -30 "$PY_LOG" >> "$EVI"; exit 5; fi

PY_BASE="http://127.0.0.1:$PY_PORT"
# 统一出口：写入 $BODY / $HDR，回显 "code|body"
call(){ # method path [extra curl args...]
  local method="$1" path="$2"; shift 2
  local code
  code=$(curl -s --noproxy '*' -X "$method" -D "$HDR" -o "$BODY" -w "%{http_code}" "$@" "$PY_BASE$path")
  echo "$code"
}
hdr_of(){ grep -i "^$1:" "$HDR" | head -1 | sed "s/^[^:]*: //;s/\r$//"; }
body_of(){ cat "$BODY"; }

# --------------------------------------------------------------- P1
sep; log "P1 GET /internal/v1/health（不带 token）→ 200 status=UP"
C=$(call GET "/internal/v1/health")
B=$(body_of); log "  raw: http=$C body=$B"
chk "P1-health-200" "200" "$C"
chk "P1-status-UP" "UP" "$(jget "$B" status)"

# --------------------------------------------------------------- P2
sep; log "P2 POST /internal/v1/analysis:validate（不带 token）→ 401 AI-0001"
C=$(call POST "/internal/v1/analysis:validate")
B=$(body_of); log "  raw: http=$C body=$B"
chk "P2-no-token-401" "401" "$C"
chk "P2-code-AI-0001" "AI-0001" "$(jget "$B" code)"
# ★ 必须【当场】存下这次响应的头：$HDR 会被后续请求覆盖，
#   等到 P5 再读就会拿 P3 的头去比 P2 的 body（跨请求比较 ⇒ 必然假 FAIL）。
P2_BODY="$B"; P2_CODE="$C"; P2_H=$(hdr_of "X-Request-Id")

# --------------------------------------------------------------- P3
sep; log "P3 同上（带错 token）→ 401 AI-0001"
C=$(call POST "/internal/v1/analysis:validate" -H "X-Internal-Token: $WRONG_TOKEN")
B=$(body_of); log "  raw: http=$C body=$B"
chk "P3-wrong-token-401" "401" "$C"
chk "P3-code-AI-0001" "AI-0001" "$(jget "$B" code)"

# --------------------------------------------------------------- P5
sep; log "P5 401 必带 X-Request-Id，且 body.request_id 与响应头逐字相等"
log "  raw: P2.header=[$P2_H] P2.body.request_id=[$(jget "$P2_BODY" request_id)]"
chknz "P5-401有X-Request-Id" "$P2_H"
chk "P5-body.request_id==响应头" "$P2_H" "$(jget "$P2_BODY" request_id)"
chk "P5-四键齐全" "code,detail,message,request_id" \
  "$(printf '%s' "$P2_BODY" | "$PYTHON_BIN" -c 'import json,sys;print(",".join(sorted(json.load(sys.stdin).keys())))' 2>/dev/null)"

# --------------------------------------------------------------- P6
sep; log "P6 显式 X-Request-Id: $REQ_ID_1 → 响应头原样回显"
C=$(call POST "/internal/v1/analysis:validate" -H "X-Request-Id: $REQ_ID_1")
B=$(body_of); H=$(hdr_of "X-Request-Id")
log "  raw: http=$C header=[$H] body.request_id=[$(jget "$B" request_id)]"
chk "P6-响应头回显" "$REQ_ID_1" "$H"
chk "P6-body回显" "$REQ_ID_1" "$(jget "$B" request_id)"

# --------------------------------------------------------------- P4
sep; log "P4 同上（带对 token）→ 非 401（记录真实状态码）"
C=$(call POST "/internal/v1/analysis:validate" -H "X-Internal-Token: $TOKEN")
B=$(body_of); log "  raw: http=$C body=$B"
chkne "P4-对token未被鉴权拦下" "401" "$C"
log "  note: 本批无业务端点，$C 属预期（404/405）"

# --------------------------------------------------------------- P8
sep; log "P8 Java GET /api/v1/health 带 $REQ_ID_2 → 响应头回显 + body.requestId 一致"
JB=$(curl -s --noproxy '*' -D "$HDR" -o "$BODY" -w "%{http_code}" \
      -H "X-Request-Id: $REQ_ID_2" "http://127.0.0.1:$APP_PORT/api/v1/health")
JBODY=$(body_of); JH=$(hdr_of "X-Request-Id")
log "  raw: http=$JB header=[$JH] body=$JBODY"
chk "P8-java-200" "200" "$JB"
chk "P8-java头回显" "$REQ_ID_2" "$JH"
chk "P8-java体requestId一致" "$REQ_ID_2" "$(jget "$JBODY" requestId)"

# --------------------------------------------------------------- P9
sep; log "P9（加分）非法 JSON + 对 token → 期望 400 AI-1001"
# ★ 必须排在 P7【之前】：P7 之后 AI_INTERNAL_TOKEN 已清空，届时任何请求都是 401，
#   贴出来的原始响应会误导读者。
C=$(call POST "/internal/v1/analysis:validate" -H "X-Internal-Token: $TOKEN" \
      -H "Content-Type: application/json" --data '{"broken":')
B=$(body_of); log "  raw: http=$C body=$B"
log "  verdict: N/A —— 本批不实现 /internal/v1/analysis* 业务端点（G3），无路由可触发 body 校验；"
log "           该路径已由 pytest tests/test_error_envelope.py::test_validation_error_returns_400_ai_1001 覆盖。"

# --------------------------------------------------------------- P7
sep; log "P7 重启 Python 且清空 AI_INTERNAL_TOKEN → P2 仍 401、P1 仍 200（fail-closed）"
kill "$PY_PID" 2>/dev/null; wait "$PY_PID" 2>/dev/null
sleep 2
start_python ""
if wait_python; then log "PYTHON_RESTART_UP=1 (pid=$PY_PID, token=<empty>)"; else
  log "PYTHON_RESTART_UP=0"; tail -30 "$PY_LOG" >> "$EVI"; fi
C=$(call POST "/internal/v1/analysis:validate")
B=$(body_of); log "  raw: http=$C body=$B"
chk "P7-未配置token仍401" "401" "$C"
chk "P7-未配置token仍AI-0001" "AI-0001" "$(jget "$B" code)"
C=$(call GET "/internal/v1/health")
B=$(body_of); log "  raw: http=$C body=$B"
chk "P7-health仍200" "200" "$C"
chk "P7-health仍UP" "UP" "$(jget "$B" status)"

# --------------------------------------------------------------- 汇总
sep
log "RB13_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$EVI") + FAIL ))"
log "RB13_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB13_RESULT=ALL PASS"; else log "RB13_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
