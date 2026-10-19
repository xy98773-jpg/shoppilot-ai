#!/usr/bin/env bash
# =============================================================================
# B13.1 独立复核真栈（项目负责人自建）
#
# 两层目的：
#   ① 复跑 B13 的 20 条基线断言，确认回修没有把已绿的搞红（回归门）；
#   ② 追加 B13.1 专属探针（P 系列），专打开发测试**没有覆盖**的组合：
#        P1 非 /internal 路径的 404 是否也走信封（§5 适用范围 = 全部响应）
#        P2 404 且**未传** request-id 时，body.request_id 与响应头是否逐字相等
#        P3 POST /internal/v1/health/（尾斜杠 + 方法错）→ 405 而非 401（豁免归一 × 路由）
#        P4 405 是否保留协议必需的 Allow 头
#        P5 前缀内**不存在**的路径 + 错 token → 401（鉴权先于路由，不得先泄露 404）
#        P6 404 请求的 request_id 是否也进日志（R98 只在 200/401 上验过）
#        P7 fail-closed 后 health 尾斜杠是否仍 200（R92 × R97 交叉）
#
# 端口：mysql=3375（外部起 rb-b13rev-mysql） java=18296 python=18096
# 用法：bash review-stack-b131.sh <fat-jar-path>
# =============================================================================
set -uo pipefail

ROOT="/d/Desktop/ShopPilot-AI"
JAR="${1:?usage: review-stack-b131.sh <jar>}"
MYSQL_PORT=3375
APP_PORT=18296
PY_PORT=18096
TOKEN="rb-b131-review-token"
OUT="/tmp/rb-b131-review"
EVI="$ROOT/backend-java/tools/rb-b13/evidence-rb-b131-review.txt"
PYBIN="C:/Users/Shu/.workbuddy-ai/binaries/python/envs/default/Scripts/python.exe"

mkdir -p "$OUT"
: > "$EVI"

TOTAL=0; FAIL=0
log(){ echo "$@" | tee -a "$EVI"; }
sep(){ log "-----------------------------------------------------------------"; }
chk(){ # $1 name $2 expected $3 actual
  TOTAL=$((TOTAL+1))
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 => expect[$2] actual[$3]"; FAIL=$((FAIL+1)); fi
}
jq_get(){ echo "$1" | "$PYBIN" -c "import sys,json;d=json.load(sys.stdin);print(d.get('$2','<MISSING>'))" 2>/dev/null || echo "<PARSE_ERR>"; }
keys_of(){ echo "$1" | "$PYBIN" -c "import sys,json;d=json.load(sys.stdin);print(','.join(sorted(d.keys())))" 2>/dev/null || echo "<PARSE_ERR>"; }
code(){ curl -s -o "$OUT/body.json" -w "%{http_code}" --noproxy '*' "$@" 2>/dev/null; }
hdr(){ local h="$1"; shift; curl -s -D "$OUT/h.txt" -o "$OUT/body.json" --noproxy '*' "$@" >/dev/null 2>&1; grep -i "^$h:" "$OUT/h.txt" | tr -d '\r' | sed -E "s/^[^:]+: *//"; }
is_uuid(){ "$PYBIN" -c "import sys,uuid;uuid.UUID(sys.argv[1]);print('1')" "$1" 2>/dev/null || echo "0"; }

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
JWT_SECRET="rb-b131-review-secret-0123456789abcdef" \
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

# =========================== A. B13 基线回归（20 条） ===========================
log ""; log "########## A. B13 基线回归（原 20 条，逐条复跑） ##########"

sep; log "R1  health 不带 token"
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

sep; log "R4  带对 token → 404（本批无业务端点）"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R4-404" "404" "$C"

sep; log "R5  401 路径的 request-id 回显（头/体逐字相等）"
H=$(hdr "X-Request-Id" -X POST -H "X-Request-Id: req_rev_0001" "$BASE/internal/v1/analysis:validate")
B=$(cat "$OUT/body.json")
log "  raw: http=401 header=[$H] body=$B"
chk "R5-头回显" "req_rev_0001" "$H"
chk "R5-体回显" "req_rev_0001" "$(jq_get "$B" request_id)"

sep; log "R6  404 四字段信封"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" "$BASE/internal/v1/analysis:validate"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R6-404四字段信封" "code,detail,message,request_id" "$(keys_of "$B")"

sep; log "R7  405 四字段信封"
C=$(code -X POST "$BASE/internal/v1/health"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R7-405四字段信封" "code,detail,message,request_id" "$(keys_of "$B")"

sep; log "R8  health 尾斜杠 200"
C=$(code "$BASE/internal/v1/health/"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "R8-health尾斜杠非401" "200" "$C"

sep; log "R9  头名大小写不敏感"
C=$(code -X POST -H "x-internal-token: $TOKEN" "$BASE/internal/v1/analysis:validate")
log "  raw: http=$C"
chk "R9-小写头名通过鉴权" "404" "$C"

sep; log "R10 Java 侧 requestId 贯通（既有链路未破坏）"
H=$(hdr "X-Request-Id" -H "X-Request-Id: req_rev_0002" "http://127.0.0.1:$APP_PORT/api/v1/health")
B=$(cat "$OUT/body.json")
log "  raw: header=[$H] body=$B"
chk "R10-头回显" "req_rev_0002" "$H"
chk "R10-体requestId" "req_rev_0002" "$(jq_get "$B" requestId)"

sep; log "R12 日志按 request_id 可关联"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" -H "X-Request-Id: req_rev_log_0003" "$BASE/internal/v1/analysis:validate")
N=$(grep -c "req_rev_log_0003" "$OUT/python.log" 2>/dev/null || echo 0)
MINUS=$(grep -c "req_id=-" "$OUT/python.log" 2>/dev/null || echo 0)
log "  raw: http=$C  日志中出现该 id 的行数=$N  「req_id=-」行数=$MINUS"
chk "R12-日志含request_id" "1" "$([ "$N" -ge 1 ] && echo 1 || echo 0)"

# =========================== B. B13.1 专属新探针 ===========================
log ""; log "########## B. B13.1 新探针（开发测试未覆盖的组合） ##########"

sep; log "P1  ★非 /internal 路径的 404 是否也走四字段信封（§5 适用范围 = 全部响应）"
C=$(code "$BASE/nope-not-a-route"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "P1-404四字段" "code,detail,message,request_id" "$(keys_of "$B")"
chk "P1-code" "AI-1003" "$(jq_get "$B" code)"

sep; log "P2  ★404 且【未传】request-id：响应头必非空，且 body.request_id 与头逐字相等"
H=$(hdr "X-Request-Id" -H "X-Internal-Token: $TOKEN" -X POST "$BASE/internal/v1/analysis:validate")
B=$(cat "$OUT/body.json"); BODY_RID=$(jq_get "$B" request_id)
log "  raw: header=[$H] body.request_id=[$BODY_RID] body=$B"
chk "P2-头非空" "1" "$([ -n "$H" ] && echo 1 || echo 0)"
chk "P2-头体相等" "$H" "$BODY_RID"
chk "P2-生成值合法uuid" "1" "$(is_uuid "$H")"

sep; log "P3  ★POST /internal/v1/health/（尾斜杠 + 方法错）→ 应为 405 AI-1004，不是 401"
C=$(code -X POST "$BASE/internal/v1/health/"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "P3-405" "405" "$C"
chk "P3-code" "AI-1004" "$(jq_get "$B" code)"

sep; log "P4  ★405 是否保留协议必需的 Allow 头（信封改造不得吞掉）"
A=$(hdr "Allow" -X POST "$BASE/internal/v1/health")
log "  raw: Allow=[$A]"
chk "P4-Allow含GET" "1" "$(echo "$A" | grep -q "GET" && echo 1 || echo 0)"

sep; log "P5  ★前缀内【不存在】的路径 + 错 token → 401（鉴权先于路由，不得先泄露 404）"
C=$(code -X POST -H "X-Internal-Token: wrong" "$BASE/internal/v1/no-such-endpoint"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "P5-401" "401" "$C"
chk "P5-code" "AI-0001" "$(jq_get "$B" code)"

sep; log "P6  ★404 请求的 request_id 是否也进日志（R98 原只在 200/401 上验过）"
C=$(code -X POST -H "X-Internal-Token: $TOKEN" -H "X-Request-Id: req_b131_log_0404" "$BASE/internal/v1/analysis:validate")
N=$(grep -c "req_b131_log_0404" "$OUT/python.log" 2>/dev/null || echo 0)
log "  raw: http=$C  日志中出现该 id 的行数=$N"
chk "P6-404日志含id" "1" "$([ "$N" -ge 1 ] && echo 1 || echo 0)"

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

sep; log "P7  ★fail-closed 之后 health 尾斜杠是否仍 200（R92 × R97 交叉）"
C=$(code "$BASE/internal/v1/health/"); B=$(cat "$OUT/body.json")
log "  raw: http=$C body=$B"
chk "P7-尾斜杠仍200" "200" "$C"
chk "P7-UP" "UP" "$(jq_get "$B" status)"

sep
log "RB131_REVIEW_TOTAL=$TOTAL  RB131_REVIEW_FAIL=$FAIL"
[ "$FAIL" -eq 0 ] && log "RB131_REVIEW_RESULT=ALL PASS" || log "RB131_REVIEW_RESULT=HAS FAIL"
sep; log "--- cleanup ---"
