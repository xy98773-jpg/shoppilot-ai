#!/usr/bin/env bash
# =============================================================================
# B13 独立复核真栈（项目负责人自建，不复用开发脚本）
#
# 目的：不采信开发 evidence，自建 fat jar + 独立 MySQL + 独立端口，
#       用「自建断言 + 3 条新探针」验证 B13 的 header 贯通与 fail-closed。
#
# 端口：mysql=3375（已由外部起好 rb-b13rev-mysql） java=18295 python=18095
# 用法：bash review-stack.sh <fat-jar-path>
# =============================================================================
set -uo pipefail

ROOT="/d/Desktop/ShopPilot-AI"
JAR="${1:?usage: review-stack.sh <jar>}"
MYSQL_PORT=3375
APP_PORT=18295
PY_PORT=18095
TOKEN="rb-b13-review-token"
OUT="/tmp/rb-b13-review"
EVI="$ROOT/backend-java/tools/rb-b13/evidence-rb-b13-review.txt"
PYBIN="C:/Users/Shu/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe"

mkdir -p "$OUT"
: > "$EVI"

TOTAL=0; FAIL=0
log(){ echo "$@" | tee -a "$EVI"; }
sep(){ log "-----------------------------------------------------------------"; }
chk(){ # $1 name  $2 expected  $3 actual
  TOTAL=$((TOTAL+1))
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 => expect[$2] actual[$3]"; FAIL=$((FAIL+1)); fi
}
jq_get(){ # $1 json  $2 key  —— 用开发同款 jget.py 之外的独立实现
  echo "$1" | "$PYBIN" -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2','<MISSING>'))" 2>/dev/null || echo "<PARSE_ERR>"
}
keys_of(){
  echo "$1" | "$PYBIN" -c "import sys,json;d=json.load(sys.stdin);print(','.join(sorted(d.keys())))" 2>/dev/null || echo "<PARSE_ERR>"
}
code(){ curl -s -o "$OUT/body.json" -w "%{http_code}" --noproxy '*' "$@" 2>/dev/null; }
hdr(){ # $1 header-name, rest curl args
  local h="$1"; shift
  curl -s -D "$OUT/h.txt" -o "$OUT/body.json" --noproxy '*' "$@" >/dev/null 2>&1
  grep -i "^$h:" "$OUT/h.txt" | tr -d '\r' | sed -E "s/^[^:]+: *//"
}

cleanup(){
  [ -n "${JAVA_PID:-}" ] && kill "$JAVA_PID" 2>/dev/null
  [ -n "${PY_PID:-}" ] && kill "$PY_PID" 2>/dev/null
  sleep 1
}
trap cleanup EXIT

log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT java=$APP_PORT python=$PY_PORT"
log "TOKEN=$TOKEN（复核专用值，非生产密钥）"

# ---------------------------------------------------------------- 起 Java
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=rootpw \
JWT_SECRET="rb-b13-review-secret-0123456789abcdef" \
AI_INTERNAL_TOKEN="$TOKEN" \
IMPORT_STORAGE_DIR="$OUT/storage" \
java -jar "$JAR" --server.port="$APP_PORT" > "$OUT/java.log" 2>&1 &
JAVA_PID=$!
JUP=0
for i in $(seq 1 90); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { JUP=1; break; }
  sleep 2
done
log "JAVA_UP=$JUP (pid=$JAVA_PID)"
[ "$JUP" = "1" ] || { log "FATAL java not up"; tail -40 "$OUT/java.log" >> "$EVI"; exit 4; }

# ---------------------------------------------------------------- 起 Python
start_python(){
  ( cd "$ROOT/service-ai" && AI_INTERNAL_TOKEN="$1" PYTHONPATH=src \
      exec "$PYBIN" -m uvicorn shoppilot_ai.app.main:app \
      --host 127.0.0.1 --port "$PY_PORT" > "$OUT/python.log" 2>&1 ) &
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
wait_python && log "PYTHON_UP=1 (pid=$PY_PID, token=<set>)" || { log "PYTHON_UP=0"; tail -30 "$OUT/python.log" >> "$EVI"; exit 5; }

BASE="http://127.0.0.1:$PY_PORT"

sep; log "R1  health 不带 token（ACCEPTANCE_CRITERIA:82 的验收形态）"
C=$(code "$BASE/internal/v1/health"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R1-health-200" "200" "$C"
chk "R1-health-UP" "UP" "$(jq_get "$B" status)"

sep; log "R2  analysis:validate 不带 token → 401 AI-0001 + 四字段"
C=$(code -X POST "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R2-401" "401" "$C"
chk "R2-code" "AI-0001" "$(jq_get "$B" code)"
chk "R2-四键" "code,detail,message,request_id" "$(keys_of "$B")"

sep; log "R3  带错 token → 401 AI-0001"
C=$(code -X POST -H "X-Internal-Token: wrong" "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R3-401" "401" "$C"
chk "R3-code" "AI-0001" "$(jq_get "$B" code)"

sep; log "R4  带对 token → 非 401（本批无业务端点）"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R4-非401" "404" "$C"

sep; log "R5  X-Request-Id 回显 + body.request_id 与响应头逐字相等（★用 401 路径：404 无信封）"
H=$(hdr "X-Request-Id" -X POST -H "X-Request-Id: req_rev_0001" "$BASE/internal/v1/analysis:validate")
B=$(cat "$OUT/body.json")
log "  raw: http=401 header=[$H] body=$B"
chk "R5-头回显" "req_rev_0001" "$H"
chk "R5-体回显" "req_rev_0001" "$(jq_get "$B" request_id)"

sep; log "R6  §5「内部统一错误响应体」是否覆盖 404（★项目负责人新增探针）"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R6-404四字段信封" "code,detail,message,request_id" "$(keys_of "$B")"

sep; log "R7  §5 是否覆盖 405（★项目负责人新增探针）"
C=$(code -X POST "$BASE/internal/v1/health"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R7-405四字段信封" "code,detail,message,request_id" "$(keys_of "$B")"

sep; log "R8  health 尾斜杠变体是否仍豁免（★项目负责人新增探针）"
C=$(code "$BASE/internal/v1/health/"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R8-health尾斜杠非401" "200" "$C"

sep; log "R9  HTTP 头名大小写不敏感（RFC 7230；契约未写）"
C=$(code -X POST -H "x-internal-token: $TOKEN" "$BASE/internal/v1/analysis:validate")
log "  raw: http=$C"
chk "R9-小写头名通过鉴权" "404" "$C"

sep; log "R10 Java 侧 /api/v1/health 的 requestId 贯通（既有链路未被破坏）"
H=$(hdr "X-Request-Id" -H "X-Request-Id: req_rev_0002" "http://127.0.0.1:$APP_PORT/api/v1/health")
B=$(cat "$OUT/body.json")
log "  raw: header=[$H] body=$B"
chk "R10-头回显" "req_rev_0002" "$H"
chk "R10-体requestId" "req_rev_0002" "$(jq_get "$B" requestId)"

sep; log "R12 §2:75「Python 必须…记入自身日志」——日志能否按 request_id 关联（★新增）"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" -H "X-Request-Id: req_rev_log_0003" "$BASE/internal/v1/analysis:validate")
N=$(grep -c "req_rev_log_0003" "$OUT/python.log" 2>/dev/null || echo 0)
MINUS=$(grep -c "req_id=-" "$OUT/python.log" 2>/dev/null || echo 0)
log "  raw: http=$C  python.log 中出现该 id 的行数=$N  「req_id=-」行数=$MINUS"
chk "R12-日志含request_id" "1" "$([ "$N" -ge 1 ] && echo 1 || echo 0)"

sep; log "R11 ★R92 fail-closed：重启 Python 且清空 AI_INTERNAL_TOKEN"
kill "$PY_PID" 2>/dev/null; sleep 2
start_python ""
wait_python && log "PYTHON_RESTART_UP=1 (pid=$PY_PID, token=<empty>)" || { log "PYTHON_RESTART_UP=0"; exit 6; }
C=$(code -X POST "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R11-未配置仍401" "401" "$C"
chk "R11-未配置仍AI-0001" "AI-0001" "$(jq_get "$B" code)"
C=$(code "$BASE/internal/v1/health")
chk "R11-health仍200" "200" "$C"

sep
log "RB13_REVIEW_TOTAL=$TOTAL  RB13_REVIEW_FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && log "RB13_REVIEW_RESULT=ALL PASS" || log "RB13_REVIEW_RESULT=HAS FAIL"
sep; log "--- cleanup ---"
