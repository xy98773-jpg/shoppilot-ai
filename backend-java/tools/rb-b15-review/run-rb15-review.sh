#!/usr/bin/env bash
# =============================================================================
# M4-B15 项目负责人独立复核探针（不采信开发自报）
# -----------------------------------------------------------------------------
# 与开发 V4 的差异（刻意）：
#   1) 对端换成「契约级假对端」stub_ai.py（202 ACCEPTED + 五种错误注入），
#      使**成功路径**可端到端验证（R107：真 service-ai 本批零端点）；
#   2) 覆盖开发未验的项：幂等体比对（改日期）、ANL-7001 data 字段、
#      跨团队同键、128/129 键边界、team_id 注入忽略、CATEGORY 含引号、
#      AI-6002→ANL-7012、未知码兜底、非 JSON 2xx→ANL-7004、FAILED 不阻塞。
#   3) 全部断言先 note 原始响应再判因（避免带先验判因）。
# 生命周期：trap cleanup（容器/进程/后台 stub 全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b15-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b15r-mysql"
MYSQL_PORT="${MYSQL_PORT:-3393}"
APP_PORT="${APP_PORT:-18360}"
STUB_PORT="${STUB_PORT:-18361}"
TOKEN="rb-b15r-internal-token-0123456789"
MODE_FILE="$OUT/mode.txt"
STUB_LOG="$OUT/stub-requests.jsonl"
EVI="$OUT/evidence-rb15-review.txt"
PASS=0; FAIL=0; PROBE=0
mkdir -p "$OUT/storage"
: > "$EVI"; : > "$STUB_LOG"; echo ok > "$MODE_FILE"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
  else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi
}
note(){ log "  NOTE $*"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  [ -n "${STUB_PID:-}" ] && kill "$STUB_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: jar 不存在 $JAR"; exit 4; }
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

for p in "$MYSQL_PORT" "$APP_PORT" "$STUB_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT/$STUB_PORT"

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

# ---------------------------------------------------------------- stub 对端
"$PY" "$OUT/stub_ai.py" "$MODE_FILE" "$STUB_LOG" "$STUB_PORT" >"$OUT/stub.out" 2>&1 &
STUB_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$STUB_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: stub 未就绪"; cat "$OUT/stub.out" | tee -a "$EVI"; exit 5; }
log "STUB_UP=1 (port $STUB_PORT, mode=ok)"

# ---------------------------------------------------------------- app
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b15r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$STUB_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -jar "$JAR" --server.port="$APP_PORT" >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 120); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT)"

# ---------------------------------------------------------------- 结构面
sep; log "=== S1/S2 结构面（迁移与隔离） ==="
JAR_WIN=$(cygpath -w "$JAR")
JAR_HIT=$("$PY" -c "import zipfile,sys;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('V1.0.9__analysis.sql')))" "$JAR_WIN")
chk "fat jar 内含 V1.0.9__analysis.sql" "1" "$JAR_HIT"
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }
chk "analysis_runs.team_id IS_NULLABLE" "NO" "$(q "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_runs' AND COLUMN_NAME='team_id';")"
chk "analysis_runs.team_id COLUMN_DEFAULT 为空" "NULL" "$(q "SELECT IFNULL(COLUMN_DEFAULT,'NULL') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_runs' AND COLUMN_NAME='team_id';")"
chk "uk_run_team_no 存在" "1" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_runs' AND index_name='uk_run_team_no';")"
chk "uk_run_team_idem 存在" "1" "$(q "SELECT COUNT(DISTINCT index_name) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_runs' AND index_name='uk_run_team_idem';")"
chk "迁移版本数（V1.0.0..V1.0.9 = 10）" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"

# ---------------------------------------------------------------- 种子 + 登录
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$ROOT/backend-java/tools/evi/seed-b7.sql" 2>/dev/null
login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"1234\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_VIEW=$(login viewer01); T_T8=$(login owner8)
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && echo 1 || echo 0)"
chk "viewer01 登录" "1" "$([ -n "$T_VIEW" ] && echo 1 || echo 0)"
chk "owner8（团队 8）登录" "1" "$([ -n "$T_T8" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- HTTP 工具
jf(){ "$PY" - "$OUT/body.json" "$1" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
cur=d
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(cur,dict) and k in cur: cur=cur[k]
    else: print('<missing:%s>'%k); raise SystemExit
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

BODY_A='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_A2='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9004,"date_from":"2026-09-01","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_A3='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9003]},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_ALL='{"scope_type":"ALL_ACTIVE","warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_5='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9005]},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'

# ---------------------------------------------------------------- T1 成功路径
sep; log "=== T1 成功路径（202 / 11 字段 / RUNNING / 审计） ==="
post_create "$T_OP" "rb15r-k1" "$BODY_A" "req_rb15r_t1"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T1 HTTP" "202" "$POST_STATUS"
chk "T1 code" "OK" "$(jf code)"
chk "T1 status" "RUNNING" "$(jf data.status)"
chk "T1 progress" "5" "$(jf data.progress)"
chk "T1 current_step" "VALIDATING" "$(jf data.current_step)"
chk "T1 total_skus" "1" "$(jf data.total_skus)"
chk "T1 processed_skus" "0" "$(jf data.processed_skus)"
chk "T1 llm_status（enable_llm=false）" "NOT_REQUESTED" "$(jf data.llm_status)"
chk "T1 requestId 回显" "$POST_RID" "$(jf requestId)"
chk "T1 响应 data 字段数（契约 11）" "11" "$(cat "$OUT/body.json" | "$PY" -c "import json,sys;print(len(json.load(sys.stdin)['data']))")"
RID1=$(jf data.run_id); RUNNO1=$(jf data.run_no)
chk "T1 run_no 形态 RUN-8位-4位" "1" "$(echo "$RUNNO1" | grep -cE '^RUN-[0-9]{8}-[0-9]{4}$')"
chk "T1 DB status=RUNNING" "RUNNING" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "T1 DB team_id=7（来自 JWT）" "7" "$(q "SELECT team_id FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "T1 DB timeout_at = created+300s（毫秒截断容差 299/300）" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE id=$RID1 AND TIMESTAMPDIFF(SECOND, created_at, timeout_at) BETWEEN 299 AND 300;")"
chk "T1 审计 CREATE=1" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID1;")"
chk "T1 审计 biz_no=run_no" "$RUNNO1" "$(q "SELECT biz_no FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID1 LIMIT 1;")"
chk "T1 审计 result" "SUCCESS" "$(q "SELECT result FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID1 LIMIT 1;")"

# ---------------------------------------------------------------- T2 重放
sep; log "=== T2 同键同体重放 ==="
post_create "$T_OP" "rb15r-k1" "$BODY_A" "req_rb15r_t2"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
chk "T2 HTTP" "202" "$POST_STATUS"
chk "T2 Idempotent-Replayed" "true" "$(replayed)"
chk "T2 run_id 与首次一致" "$RID1" "$(jf data.run_id)"
chk "T2 team7 run 行数仍为 1" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE team_id=7 AND idempotency_key='rb15r-k1';")"
chk "T2 审计 CREATE 未重复" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID1;")"

# ---------------------------------------------------------------- T3 同键改日期（契约 §6.3）
sep; log "=== T3 探针：同键 + 仅改 date_from（契约 §6.3「请求体不一致」→ COM-0009） ==="
post_create "$T_OP" "rb15r-k1" "$BODY_A2" "req_rb15r_t3"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
note "契约 §6.3：请求体一致性按规范化整体比对；日期属于请求体"
chk "T3 期望 COM-0009 / 409" "COM-0009:409" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- T4 同键改范围
sep; log "=== T4 同键 + 改 scope_json（应 COM-0009） ==="
post_create "$T_OP" "rb15r-k1" "$BODY_A3" "req_rb15r_t4"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T4 期望 COM-0009 / 409" "COM-0009:409" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- T5 ANL-7001 同范围
sep; log "=== T5 ANL-7001（同 scope_type + 同仓 + 相交 + RUNNING）与 data 字段 ==="
post_create "$T_OP" "rb15r-k5" "$BODY_A" "req_rb15r_t5"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T5 HTTP" "409" "$POST_STATUS"
chk "T5 code" "ANL-7001" "$(jf code)"
note "契约 §11.5 失败响应 1 的 data 应含 existing_run_id / existing_run_no"
log "T5 data.existing_run_id=$(jf data.existing_run_id)  data.existing_run_no=$(jf data.existing_run_no)"
PROBE=$((PROBE+1)); [ "$(jf data.existing_run_id)" = "$RID1" ] && log "[PASS] T5 data.existing_run_id 回带" || log "[PROBE-FAIL] T5 data 未回带 existing_run_id（契约 §11.5 示例要求；当前异常恒 data=null）"

# ---------------------------------------------------------------- T6/T7 ALL_ACTIVE
sep; log "=== T6/T7 ALL_ACTIVE（scope_json=NULL 的进行中还原） ==="
post_create "$T_OP" "rb15r-k6" "$BODY_ALL" "req_rb15r_t6"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T6 HTTP" "202" "$POST_STATUS"
chk "T6 total_skus（非 SELECTED 为 0）" "0" "$(jf data.total_skus)"
chk "T6 DB scope_json IS NULL" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE id=$(jf data.run_id) AND scope_json IS NULL;")"
post_create "$T_OP" "rb15r-k7" "$BODY_ALL" "req_rb15r_t7"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T7 ALL_ACTIVE 再建 → ANL-7001" "ANL-7001:409" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- T8 跨 scope_type 放行
sep; log "=== T8 不同 scope_type 不冲突（ALL_ACTIVE 进行中时 SELECTED_SKUS 放行） ==="
post_create "$T_OP" "rb15r-k8" "$BODY_5" "req_rb15r_t8"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T8 HTTP" "202" "$POST_STATUS"

# ---------------------------------------------------------------- T9 跨团队同键
sep; log "=== T9 跨团队同键（uk_run_team_idem 带 team_id） ==="
post_create "$T_T8" "rb15r-k1" "$BODY_A" "req_rb15r_t9"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
RID9=$(jf data.run_id)
chk "T9 团队 8 同键 → 202 新建" "202" "$POST_STATUS"
chk "T9 非重放" "false" "$(replayed)"
chk "T9 新 run_id != 团队 7 的 run" "1" "$([ "$RID9" != "$RID1" ] && echo 1 || echo 0)"
chk "T9 DB team_id=8" "8" "$(q "SELECT team_id FROM shoppilot.analysis_runs WHERE id=$RID9;")"

# ---------------------------------------------------------------- T10/T11 键边界
sep; log "=== T10/T11 幂等键边界 ==="
post_create "$T_OP" "" "$BODY_5" "req_rb15r_t10"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T10 缺键 → COM-0010/400" "COM-0010:400" "$(jf code):$POST_STATUS"
K128=$(printf 'k%.0s' $(seq 1 128)); K129="${K128}k"
BODY_7='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9007]},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
post_create "$T_OP" "$K128" "$BODY_7" "req_rb15r_t11a"
log "STATUS=$POST_STATUS（128 字符键）BODY=$(cat "$OUT/body.json")"
chk "T11a 128 字符键 → 202" "202" "$POST_STATUS"
post_create "$T_OP" "$K129" "$BODY_5" "req_rb15r_t11b"
log "STATUS=$POST_STATUS（129 字符键）BODY=$(cat "$OUT/body.json")"
chk "T11b 129 字符键 → COM-0010/400" "COM-0010:400" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- T12 VIEWER
sep; log "=== T12 VIEWER 静态拦截 ==="
post_create "$T_VIEW" "rb15r-k12" "$BODY_5" "req_rb15r_t12"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T12 VIEWER → TEAM-2003/403" "TEAM-2003:403" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- T13 team_id 注入
sep; log "=== T13 请求体塞 team_id=999（必须忽略） ==="
BODY_TID='{"team_id":999,"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9003]},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
post_create "$T_OP" "rb15r-k13" "$BODY_TID" "req_rb15r_t13"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T13 HTTP" "202" "$POST_STATUS"
chk "T13 DB team_id 仍为 7" "7" "$(q "SELECT team_id FROM shoppilot.analysis_runs WHERE id=$(jf data.run_id);")"

# ---------------------------------------------------------------- T14 CATEGORY 引号
sep; log "=== T14 探针：CATEGORY 含双引号（scope_json 手工拼接的转义） ==="
BODY_Q='{"scope_type":"CATEGORY","scope_json":{"category":"A\"B"},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
post_create "$T_OP" "rb15r-k14" "$BODY_Q" "req_rb15r_t14"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
note "category 允许任意 1..128 字符串；canonicalJson 用字符串拼接，未做 JSON 转义"
PROBE=$((PROBE+1))
case "$POST_STATUS:$(jf code)" in
  202:OK)      log "[PASS] T14 category 含引号被正确接受（JSON 已转义）" ;;
  400:COM-0001) log "[PASS] T14 category 含引号被显式拒绝（400，非 500）" ;;
  *)           log "[PROBE-FAIL] T14 category 含引号落 $(jf code):$POST_STATUS（既非接受也非 400）——R109" ;;
esac

# ---------------------------------------------------------------- T15 失败补偿（AI-6002）
sep; log "=== T15 发起失败补偿（AI-6002 → ANL-7012，run FAILED） ==="
echo ai6002 > "$MODE_FILE"
BODY_CAT='{"scope_type":"CATEGORY","scope_json":{"category":"食品"},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
post_create "$T_OP" "rb15r-k15" "$BODY_CAT" "req_rb15r_t15"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
RID15=$(q "SELECT id FROM shoppilot.analysis_runs WHERE idempotency_key='rb15r-k15' LIMIT 1;")
chk "T15 对外码 ANL-7012/409" "ANL-7012:409" "$(jf code):$POST_STATUS"
chk "T15 run status=FAILED" "FAILED" "$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID15;")"
chk "T15 error_code" "ANL-7012" "$(q "SELECT error_code FROM shoppilot.analysis_runs WHERE id=$RID15;")"
chk "T15 finished_at 非空" "1" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE id=$RID15 AND finished_at IS NOT NULL;")"
chk "T15 无 PENDING 悬挂" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE status='PENDING';")"
chk "T15 审计 CREATE=0" "0" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_CREATE' AND biz_id=$RID15;")"
chk "T15 审计 FINISH=1 且 FAILURE" "FAILURE" "$(q "SELECT result FROM shoppilot.audit_logs WHERE action='ANALYSIS_RUN_FINISH' AND biz_id=$RID15 LIMIT 1;")"
chk "T15 响应体不含 AI- 码" "0" "$(grep -c 'AI-' "$OUT/body.json")"
chk "T15 error_message 脱敏（不含 AI-6002）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE id=$RID15 AND error_message LIKE '%AI-%';")"

# ---------------------------------------------------------------- T16 重放 FAILED
sep; log "=== T16 同键重放一个 FAILED 的 run（契约：返回首次结果） ==="
echo ok > "$MODE_FILE"
post_create "$T_OP" "rb15r-k15" "$BODY_CAT" "req_rb15r_t16"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
chk "T16 HTTP 202" "202" "$POST_STATUS"
chk "T16 重放标记" "true" "$(replayed)"
chk "T16 返回 FAILED 快照" "FAILED" "$(jf data.status)"

# ---------------------------------------------------------------- T17 FAILED 不阻塞
sep; log "=== T17 FAILED 不参与 ANL-7001（同 CATEGORY 重新发起应放行） ==="
post_create "$T_OP" "rb15r-k17" "$BODY_CAT" "req_rb15r_t17"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T17 HTTP" "202" "$POST_STATUS"

# ---------------------------------------------------------------- T18/T19/T20 错误映射
sep; log "=== T18 未知码 → ANL-7002；T19 2xx 非 JSON → ANL-7004；T20 404 AI-1003 → ANL-7002 ==="
BODY_CAT2='{"scope_type":"CATEGORY","scope_json":{"category":"饮料"},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
echo unknown > "$MODE_FILE"
post_create "$T_OP" "rb15r-k18" "$BODY_CAT2" "req_rb15r_t18"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T18 未知码 → ANL-7002/502" "ANL-7002:502" "$(jf code):$POST_STATUS"
chk "T18 响应不含 AI-9999" "0" "$(grep -c 'AI-9999' "$OUT/body.json")"

echo nonjson > "$MODE_FILE"
post_create "$T_OP" "rb15r-k19" "$BODY_CAT2" "req_rb15r_t19"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T19 非 JSON 2xx → ANL-7004/422" "ANL-7004:422" "$(jf code):$POST_STATUS"

echo route404 > "$MODE_FILE"
post_create "$T_OP" "rb15r-k20" "$BODY_CAT2" "req_rb15r_t20"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "T20 404 AI-1003 → ANL-7002/502" "ANL-7002:502" "$(jf code):$POST_STATUS"
chk "T20 响应不含 AI-1003" "0" "$(grep -c 'AI-1003' "$OUT/body.json")"
chk "T20 日志保留原码" "1" "$(grep -c 'aiCode=AI-1003' "$APP_LOG")"
echo ok > "$MODE_FILE"

# ---------------------------------------------------------------- T21 出站头
sep; log "=== T21 出站头与路径（stub 记录） ==="
# ★ 路径必须以 argv 传入：Git Bash 会把 argv 里的 /d/... 自动转 Windows 路径，
#   而内嵌在 -c 代码里的 /d/... 不会被转换（曾因此得到 4 条假 FAIL）。
T21_OUT=$("$PY" - "$STUB_LOG" "$TOKEN" <<'PYEOF'
import json, sys
log, token = sys.argv[1], sys.argv[2]
total = bad_path = bad_token = miss_rid = ok = 0
for line in open(log, encoding="utf-8"):
    d = json.loads(line)
    total += 1
    if d["path"] != "/internal/v1/analysis/runs":
        bad_path += 1
    if d["token"] != token:
        bad_token += 1
    if not d["requestId"]:
        miss_rid += 1
    if d["mode"] == "ok":
        ok += 1
print(total, bad_path, bad_token, miss_rid, ok)
PYEOF
)
set -- $T21_OUT
TOTAL_REQ=$1; BAD_PATH=$2; BAD_TOKEN=$3; MISS_RID=$4; OK_REQ=$5
log "STUB_REQUESTS=$TOTAL_REQ"
chk "T21 出站路径全为冒号形态 runs" "0" "$BAD_PATH"
chk "T21 内部令牌头全部正确" "0" "$BAD_TOKEN"
chk "T21 X-Request-Id 全部非空" "0" "$MISS_RID"
chk "T21 至少有一次 202 成功受理" "1" "$([ "$OK_REQ" -gt 0 ] && echo 1 || echo 0)"

# —— 契约 §3.2：请求体必须携带 team_id（服务端解析）与 items（本批恒空数组）
sep; log "=== T22 内部请求体字段（AI_SERVICE_CONTRACT §3.2） ==="
pick_body(){ "$PY" - "$STUB_LOG" "$1" <<'PYEOF'
import json, sys
log, rid = sys.argv[1], int(sys.argv[2])
for line in open(log, encoding="utf-8"):
    d = json.loads(line)
    b = d.get("body") or {}
    if b.get("analysis_run_id") == rid:
        print(json.dumps(b, ensure_ascii=False, separators=(",", ":")))
        break
PYEOF
}
REQ_RID1=$(pick_body "$RID1")
log "REQ_BODY_T1=$REQ_RID1"
chk "T22 team_id 来自上下文（=7）" "7" "$(echo "$REQ_RID1" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('team_id'))")"
chk "T22 items 恒空数组" "[]" "$(echo "$REQ_RID1" | "$PY" -c "import json,sys;print(json.dumps(json.load(sys.stdin).get('items')))")"
chk "T22 必需字段齐全（9 个，NON_NULL 省略 null）" "1" "$(echo "$REQ_RID1" | "$PY" -c "import json,sys;d=json.load(sys.stdin);print(1 if {'analysis_run_id','team_id','warehouse_id','scope','date_from','date_to','params','algorithm_version','items'} <= set(d) else 0)")"
chk "T22 date_from 透传" "2026-09-16" "$(echo "$REQ_RID1" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('date_from'))")"
REQ_RID9=$(pick_body "$RID9")
chk "T22 跨团队请求 team_id=8" "8" "$(echo "$REQ_RID9" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('team_id'))")"

sep
log "RB15R_PROBES_TOTAL=$((PASS+FAIL+PROBE))"
log "RB15R_PASS=$PASS  FAIL=$FAIL  PROBE_NOTE=$PROBE"
[ "$FAIL" = 0 ] && log "RB15R_RESULT=ALL PASS" || log "RB15R_RESULT=HAS FAILURES"
exit 0
