#!/usr/bin/env bash
# =============================================================================
# M4-B20.2 项目负责人独立复核探针（不采信开发自报）
# -----------------------------------------------------------------------------
# 与开发 V1–V7 的差异（刻意）：
#   1) **真实 service-ai 容器**（不是 stub），且 Java 侧 polling **显式打开**
#      （ANALYSIS_POLL_ENABLED=true）⇒ 第一次验证「定时任务不靠读时同步也能推进」；
#   2) 独立 MySQL + 独立 fat jar + 独立端口，不复用任何开发环境；
#   3) 重点打「SSOT 明文但开发未覆盖」：
#       P1 落库后的 risk_sku_count 与 summary_json 内同名值是否自相矛盾（§5.32 裁定 #12）；
#       P2 定时任务单独可推进（M4-T05 的核心，开发 V6 只验了 TIMEOUT）；
#       P3 data_start_date/data_end_date 结果回写红线（R108 幂等载体）；
#       P4 analysis_items 仅 INSERT（§5.15：无 updated_at/updated_by/version）；
#       P5 读时同步静默降级（R134）；P6 跨团队 GET ⇒ ANL-7005；P7 VIEWER 可读；
#       P8 R128 显式他团队仓 ⇒ COM-0001；P9 total_skus = 装配后实际数（R135）。
# 生命周期：trap cleanup（容器/进程全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b202-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b202r-mysql"
AI_NAME="rb-b202r-ai"
MYSQL_PORT="${MYSQL_PORT:-3361}"
APP_PORT="${APP_PORT:-18250}"
AI_PORT="${AI_PORT:-18260}"
AI_PROXY_PORT="${AI_PROXY_PORT:-18261}"
TOKEN="rb-b202r-internal-token-0123456789"
EVI="$OUT/evidence-b202-review.txt"
CAP="$OUT/ai-captured.jsonl"
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
"$PY" "$OUT/ai_proxy.py" "$AI_PORT" "$AI_PROXY_PORT" "$CAP" >"$OUT/proxy.out" 2>&1 &
PROXY_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$AI_PROXY_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: 抓包反代未就绪"; cat "$OUT/proxy.out" | tee -a "$EVI"; exit 5; }
log "CAPTURE_PROXY_UP=1 ($AI_PROXY_PORT -> $AI_PORT)"

# ---------------------------------------------------------------- app（★ polling 显式打开）
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b202r-independent-secret-0123456789"
export AI_SERVICE_BASE_URL="http://127.0.0.1:$AI_PROXY_PORT"
export AI_INTERNAL_TOKEN="$TOKEN"
export IMPORT_STORAGE_DIR="$OUT/storage"
export ANALYSIS_POLL_ENABLED=true
APP_LOG="$OUT/app.log"; : > "$APP_LOG"
java -jar "$JAR" --server.port="$APP_PORT" \
  --shoppilot.analysis.poll.fixed-delay-ms=5000 \
  --shoppilot.analysis.poll.initial-delay-ms=10000 \
  --shoppilot.analysis.poll.scan-limit=50 >>"$APP_LOG" 2>&1 &
APP_PID=$!
UP=0
for _ in $(seq 1 150); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$APP_PORT/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 1
done
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -40 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1 (port $APP_PORT, ANALYSIS_POLL_ENABLED=true)"

# ---------------------------------------------------------------- 结构面
sep; log "=== S1 结构面 ==="
JAR_WIN=$(cygpath -w "$JAR")
JAR_HIT=$("$PY" -c "import zipfile,sys;print(sum(1 for n in zipfile.ZipFile(sys.argv[1]).namelist() if n.endswith('V1.0.9__analysis.sql')))" "$JAR_WIN")
chk "fat jar 内含 V1.0.9__analysis.sql" "1" "$JAR_HIT"
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }
chk "迁移版本数（10）" "10" "$(q "SELECT COUNT(*) FROM shoppilot.flyway_schema_history WHERE success=1;")"
chk "P4 analysis_items 无 updated_at 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_items' AND COLUMN_NAME='updated_at';")"
chk "P4 analysis_items 无 version 列" "0" "$(q "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_items' AND COLUMN_NAME='version';")"
chk "P4 analysis_items 唯一键 uk_ai_run_sku_wh 列数=3" "3" "$(q "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_items' AND NON_UNIQUE=0 AND INDEX_NAME='uk_ai_run_sku_wh';")"
chk "P4 analysis_items 唯一键不含 team_id（R89）" "0" "$(q "SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='shoppilot' AND TABLE_NAME='analysis_items' AND NON_UNIQUE=0 AND INDEX_NAME='uk_ai_run_sku_wh' AND COLUMN_NAME='team_id';")"

# ---------------------------------------------------------------- 种子 + 登录
docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$OUT/seed-b201.sql" 2>"$OUT/seed.err"
SEED_RC=$?
chk "种子导入成功" "0" "$SEED_RC"
[ "$SEED_RC" = 0 ] || cat "$OUT/seed.err" | tee -a "$EVI"
chk "种子：团队 7 默认仓 9004" "1" "$(q "SELECT is_default FROM shoppilot.warehouses WHERE id=9004;")"
chk "种子：团队 7 ACTIVE SKU 数（装配预期 5）" "5" "$(q "SELECT COUNT(*) FROM shoppilot.skus WHERE team_id=7 AND status='ACTIVE';")"

login(){ curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"S3cret!Passw0rd\"}" | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))"; }
T_OP=$(login operator01); T_VW=$(login viewer01); T_T8=$(login owner8)
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && echo 1 || echo 0)"
chk "viewer01 登录" "1" "$([ -n "$T_VW" ] && echo 1 || echo 0)"
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
    elif isinstance(cur,list) and k.lstrip('-').isdigit():
        i=int(k)
        if -len(cur) <= i < len(cur): cur=cur[i]
        else: print('<index-oob:%s>'%k); raise SystemExit
    else: print('<missing:%s>'%k); raise SystemExit
print('' if cur is None else cur)
PY
}
post_create(){ local token="$1" key="$2" body="$3" rid="$4"
  local hdr=(-H "Content-Type: application/json" -H "Authorization: Bearer $token" -H "X-Request-Id: $rid")
  [ -n "$key" ] && hdr+=(-H "Idempotency-Key: $key")
  POST_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
    -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" "${hdr[@]}" -d "$body")
}
replayed(){ grep -qi '^Idempotent-Replayed: true' "$OUT/headers.txt" && echo true || echo false; }
get_run(){ curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' \
  -H "Authorization: Bearer $1" "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$2" > "$OUT/gs.txt"; GET_STATUS=$(cat "$OUT/gs.txt"); }
cap_paths(){ "$PY" - "$CAP" <<'PY'
import json,sys,collections
c=collections.Counter()
for line in open(sys.argv[1],encoding='utf-8'):
    line=line.strip()
    if not line: continue
    try: c[json.loads(line).get('path','?')]+=1
    except Exception: pass
print(' '.join('%s=%d'%(k,v) for k,v in sorted(c.items())))
PY
}

WIN_FROM='2026-09-16'; WIN_TO='2026-10-13'
BODY_ALL='{"scope_type":"ALL_ACTIVE","date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'
BODY_XWH='{"scope_type":"SELECTED_SKUS","scope_json":{"sku_ids":[9002]},"warehouse_id":9103,"date_from":"2026-09-16","date_to":"2026-10-13","params":{"window_days":28,"min_days":7,"enable_llm":false},"algorithm_version":"avg-ma-3sigma-v1"}'

wait_terminal(){ # runId -> status
  local rid="$1" st="" i
  for i in $(seq 1 40); do
    get_run "$T_OP" "$rid" >/dev/null
    st=$(jf data.status)
    case "$st" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) echo "$st"; return 0;; esac
    sleep 2
  done
  echo "$st"
}

# ================================ T1 端到端（读时同步路径）
sep; log "=== T1 创建 → 轮询 → 终态 → 落库 ==="
post_create "$T_OP" "b202-t1-all" "$BODY_ALL" "b202-r1"
RID_T1=$(jf data.run_id)
chk "T1a 创建返回 202" "202" "$POST_STATUS"
chk "T1b P9 total_skus = 装配后实际数（旧逻辑 ALL_ACTIVE 恒 0）" "5" "$(jf data.total_skus)"
T1_DS_BEFORE=$(q "SELECT data_start_date FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
T1_DE_BEFORE=$(q "SELECT data_end_date FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
note "T1 建 run 时窗口：data_start_date=$T1_DS_BEFORE / data_end_date=$T1_DE_BEFORE"

ST1=$(wait_terminal "$RID_T1")
chk "T1c 终态（读时同步推进）" "SUCCEEDED" "$ST1"
chk "T1d analysis_items 落库 5 行" "5" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1;")"
chk "T1e progress=100 / current_step=DONE" "100|DONE" "$(q "SELECT CONCAT(progress,'|',current_step) FROM shoppilot.analysis_runs WHERE id=$RID_T1;")"
chk "T1f finished_at 非空" "1" "$(q "SELECT finished_at IS NOT NULL FROM shoppilot.analysis_runs WHERE id=$RID_T1;")"
chk "T1g 审计 ANALYSIS_RUN_FINISH(SUCCESS) 恰 1 条" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_T1 AND action='ANALYSIS_RUN_FINISH' AND result='SUCCESS';")"
chk "T1h 审计含 CREATE 与 FINISH 两个动作" "2" "$(q "SELECT COUNT(DISTINCT action) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_T1;")"

# ---- P1 核心探针：risk_sku_count 与 summary_json 内同名值是否自相矛盾
sep; log "=== P1 risk_sku_count 口径一致性（§5.32 裁定 #12：仅 HIGH/MEDIUM/LOW） ==="
P1_COL=$(q "SELECT risk_sku_count FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
P1_SUM=$(q "SELECT JSON_UNQUOTE(JSON_EXTRACT(summary_json,'\$.risk_sku_count')) FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
P1_UNK=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND risk_level='UNKNOWN';")
P1_RISK=$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND risk_level IN ('HIGH','MEDIUM','LOW');")
note "P1 列值=$P1_COL / summary_json内=$P1_SUM / 落库 UNKNOWN 条数=$P1_UNK / HIGH+MEDIUM+LOW=$P1_RISK"
chk "P1a 列 risk_sku_count == summary_json.risk_sku_count（同一次落库不得自相矛盾）" "$P1_SUM" "$P1_COL"
chk "P1b 列 risk_sku_count 应等于 HIGH+MEDIUM+LOW 条数" "$P1_RISK" "$P1_COL"

# ---- R132 三列回读
sep; log "=== T2 R132：Python 不回显的三列须回读主数据 ==="
chk "T2a 9002 product_id 回读（=9001）" "9001" "$(q "SELECT product_id FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND sku_id=9002;")"
chk "T2b 9002 in_transit_qty 回读余额列（=5.000）" "5.000" "$(q "SELECT in_transit_qty FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND sku_id=9002;")"
chk "T2c 9003 无余额行 ⇒ in_transit_qty=0" "0.000" "$(q "SELECT in_transit_qty FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND sku_id=9003;")"
chk "T2d 9002 sales_window_days = 请求 window_days(28)" "28" "$(q "SELECT sales_window_days FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND sku_id=9002;")"
chk "T2e 9002 available_qty = on_hand(10) − committed(1) = 9" "9.000" "$(q "SELECT available_qty FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND sku_id=9002;")"
chk "T2f P4 analysis_items.team_id 全部 = 7" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND team_id<>7;")"
chk "T2g §8 规则2 llm_detail_json 独立成列且每行非空（5 行）" "5" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND llm_detail_json IS NOT NULL;")"
chk "T2h risk_detail_json / data_quality_json 中不得出现 llm_status 键" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND (risk_detail_json LIKE '%llm_status%' OR data_quality_json LIKE '%llm_status%');")"
chk "T2i R124 同源：in_transit_qty == effective_in_transit_qty（临时口径）" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_T1 AND in_transit_qty<>effective_in_transit_qty;")"
chk "T2j created_by = run.triggered_by(1001)" "1001" "$(q "SELECT DISTINCT created_by FROM shoppilot.analysis_items WHERE run_id=$RID_T1;")"

# ---- P3 红线：data_start_date / data_end_date 不被结果覆盖
sep; log "=== P3 R108 红线：结果回写不得漂移幂等载体 ==="
T1_DS_AFTER=$(q "SELECT data_start_date FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
T1_DE_AFTER=$(q "SELECT data_end_date FROM shoppilot.analysis_runs WHERE id=$RID_T1;")
chk "P3a data_start_date 落库前后不变" "$T1_DS_BEFORE" "$T1_DS_AFTER"
chk "P3b data_end_date 落库前后不变" "$T1_DE_BEFORE" "$T1_DE_AFTER"

# ---- 幂等重投（终态后）
post_create "$T_OP" "b202-t1-all" "$BODY_ALL" "b202-r2"
chk "T3 终态后同键重投 202 + replayed" "202|true" "$POST_STATUS|$(replayed)"

# ---- 权限面
sep; log "=== T4 端点权限与跨团队 ==="
get_run "$T_VW" "$RID_T1" >/dev/null
chk "T4a P7 VIEWER 可读 GET /runs/{id}" "200" "$GET_STATUS"
get_run "$T_T8" "$RID_T1" >/dev/null
GS8="$GET_STATUS"; C8=$(jf code)
chk "T4b P6 跨团队 GET ⇒ 404" "404" "$GS8"
chk "T4c P6 跨团队 GET ⇒ ANL-7005" "ANL-7005" "$C8"
get_run "$T_OP" "999999" >/dev/null
chk "T4d 不存在的 run ⇒ ANL-7005" "ANL-7005" "$(jf code)"
post_create "$T_VW" "b202-t4-viewer" "$BODY_ALL" "b202-r6"
chk "T4e VIEWER 创建仍被拒（OPERATOR+）" "403" "$POST_STATUS"

# ---- R128 显式他团队仓
sep; log "=== T5 R128：显式指定他团队仓 ⇒ COM-0001 ==="
post_create "$T_OP" "b202-t5-xwh" "$BODY_XWH" "b202-r3"
chk "T5a HTTP 400" "400" "$POST_STATUS"
chk "T5b 错误码 COM-0001" "COM-0001" "$(jf code)"
chk "T5c 不建 run" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE idempotency_key='b202-t5-xwh';")"

# ================================ P2 定时任务单独推进（不调 GET）
sep; log "=== P2 定时任务独立推进（创建后 40s 内不调一次 GET） ==="
post_create "$T_OP" "b202-p2-poll" "$BODY_ALL" "b202-r4"
RID_P2=$(jf data.run_id)
chk "P2a 创建 202" "202" "$POST_STATUS"
P2_ST=""; for _ in $(seq 1 40); do sleep 2
  P2_ST=$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_P2;")
  case "$P2_ST" in SUCCEEDED|DEGRADED|FAILED|TIMEOUT|CANCELLED) break;; esac
done
note "P2 期间 Java 出站调用路径：$(cap_paths)"
chk "P2b 定时扫描把 run 推进到终态（未调 GET）" "SUCCEEDED" "$P2_ST"
chk "P2c 定时扫描同时完成落库（5 行）" "5" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_P2;")"
CAP_GET=$(grep -c '"method": "GET"' "$CAP" 2>/dev/null); CAP_GET=${CAP_GET:-0}
CAP_RESULT=$(grep -c 'runs/[0-9]*/result' "$CAP" 2>/dev/null); CAP_RESULT=${CAP_RESULT:-0}
note "P2 抓包统计：GET 请求 $CAP_GET 次 / result 路径 $CAP_RESULT 次"
chk "P2d 出站确有 GET 状态查询（走 PATH_RUN）" "1" "$([ "$CAP_GET" -ge 1 ] && echo 1 || echo 0)"
chk "P2e 出站确有 result 拉取（走 PATH_RUN_RESULT）" "1" "$([ "$CAP_RESULT" -ge 1 ] && echo 1 || echo 0)"

# ================================ 超时扫描
sep; log "=== T6 timeout_at 兜底 ⇒ TIMEOUT + ANL-7003 + FINISH 审计 ==="
q "DELETE FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P2;" >/dev/null
q "UPDATE shoppilot.analysis_runs SET status='RUNNING', timeout_at=DATE_SUB(UTC_TIMESTAMP(3), INTERVAL 1 MINUTE), finished_at=NULL WHERE id=$RID_P2;" >/dev/null
T6_ST=""; for _ in $(seq 1 30); do sleep 2
  T6_ST=$(q "SELECT status FROM shoppilot.analysis_runs WHERE id=$RID_P2;")
  [ "$T6_ST" = "TIMEOUT" ] && break
done
chk "T6a 扫描后 status=TIMEOUT" "TIMEOUT" "$T6_ST"
chk "T6b error_code=ANL-7003" "ANL-7003" "$(q "SELECT error_code FROM shoppilot.analysis_runs WHERE id=$RID_P2;")"
chk "T6c finished_at 非空" "1" "$(q "SELECT finished_at IS NOT NULL FROM shoppilot.analysis_runs WHERE id=$RID_P2;")"
chk "T6d FINISH 审计恰 1 条" "1" "$(q "SELECT COUNT(*) FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P2;")"
chk "T6e FINISH 审计 result=FAILURE" "FAILURE" "$(q "SELECT result FROM shoppilot.audit_logs WHERE biz_type='ANALYSIS_RUN' AND biz_id=$RID_P2;")"
chk "T6f 超时不落 analysis_items（复用旧行，不新增）" "5" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_items WHERE run_id=$RID_P2;")"

# ================================ 破坏性组（放最后）：P5 静默降级
sep; log "=== P5 R134：Python 不可用 ⇒ GET 静默降级 200 ==="
post_create "$T_OP" "b202-p5-down" "$BODY_ALL" "b202-r5"
RID_P5=$(jf data.run_id)
docker rm -f "$AI_NAME" >/dev/null 2>&1
note "P5 已停掉真实 service-ai 容器"
get_run "$T_OP" "$RID_P5" >/dev/null
chk "P5a Python 不可用时 GET 仍 200（不把 3s 超时变 500）" "200" "$GET_STATUS"
note "P5 静默降级以 P5a（停 AI 后 GET 仍 200）为准；上批 R137 阻塞时 P5b 已证「RUNNING 时返回 DB 现状」"
get_run "$T_OP" "$RID_T1" >/dev/null
chk "P5c 终态查询不受影响（不触发同步）" "200" "$GET_STATUS"

sep
log "TOTAL PASS=$PASS FAIL=$FAIL"
log "CAPTURED PATHS: $(cap_paths)"
[ "$FAIL" = 0 ] && log "RESULT=ALL_PASS" || log "RESULT=HAS_FAIL"
exit 0
