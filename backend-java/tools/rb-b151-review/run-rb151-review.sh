#!/usr/bin/env bash
# =============================================================================
# M4-B15.1 定向复核补充探针（项目负责人自建，不复用开发 rb-b151）
# -----------------------------------------------------------------------------
# 目的：在 B15 的 80 项基线之外，补「SSOT 明文但 B15.1 开发未覆盖」的项：
#   A. R108 全字段比对：date_to / prompt_version / model_name（开发单测有，
#      但真栈只验了 date_from）——本脚本逐项做 HTTP 级取证；
#   B. 空白归一：prompt_version "" vs 缺省 必须视为同体（不得误报 COM-0009）；
#   C. R109 变体：反斜杠 / 内嵌换行（开发真栈只验了双引号）；
#   D. 并发同键（>2 路）：全部应 202 + 同一 run_id，且库内 1 行；
#      ★ 顺带探「c 未命中但 d 看到 PENDING」的窗口是否会误报 ANL-7001；
#   E. 新载体：data_start_date / data_end_date 必须等于请求窗口。
# 生命周期：trap cleanup（容器 / app / stub 全回收）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b151-review"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
MYSQL_NAME="rb-b151r-mysql"
MYSQL_PORT="${MYSQL_PORT:-3394}"
APP_PORT="${APP_PORT:-18380}"
STUB_PORT="${STUB_PORT:-18381}"
TOKEN="rb-b151r-token-0123456789"
MODE_FILE="$OUT/mode.txt"
STUB_LOG="$OUT/stub-requests.jsonl"
EVI="$OUT/evidence-rb151-review.txt"
PASS=0; FAIL=0; PROBE=0
mkdir -p "$OUT/storage"
: > "$EVI"; : > "$STUB_LOG"; echo ok > "$MODE_FILE"

log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3"; else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
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

# 复用 B15 复核脚本里的契约级假对端（只读）
"$PY" "$ROOT/backend-java/tools/rb-b15-review/stub_ai.py" "$MODE_FILE" "$STUB_LOG" "$STUB_PORT" >"$OUT/stub.out" 2>&1 &
STUB_PID=$!
SUP=0
for _ in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' "http://127.0.0.1:$STUB_PORT/internal/v1/health" || echo 000)
  [ "$c" = "200" ] && { SUP=1; break; }
  sleep 1
done
[ "$SUP" = 1 ] || { log "FATAL: stub 未就绪"; exit 5; }
log "STUB_UP=1"

export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b151r-independent-secret-0123456789"
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
[ "$UP" = 1 ] || { log "FATAL: app 未就绪"; tail -30 "$APP_LOG" | tee -a "$EVI"; exit 5; }
log "APP_UP=1"

docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw --default-character-set=utf8mb4 shoppilot < "$ROOT/backend-java/tools/evi/seed-b7.sql" 2>/dev/null
q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 -e "$1" 2>/dev/null | tr -d '\r'; }
T_OP=$(curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" -H "Content-Type: application/json" \
  -d '{"username":"operator01","password":"1234"}' | "$PY" -c "import json,sys;print(json.load(sys.stdin).get('data',{}).get('accessToken',''))")
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && echo 1 || echo 0)"

jf(){ "$PY" - "$OUT/body.json" "$1" <<'PYEOF'
import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
cur=d
for k in sys.argv[2].split('.'):
    if k=='': continue
    if isinstance(cur,dict) and k in cur: cur=cur[k]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if cur is None else cur)
PYEOF
}
POST_STATUS=""
post(){ local key="$1" body="$2" rid="$3"; local hdr=(-H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" -H "X-Request-Id: $rid"); [ -n "$key" ] && hdr+=(-H "Idempotency-Key: $key"); POST_STATUS=$(curl -s --noproxy '*' -o "$OUT/body.json" -D "$OUT/headers.txt" -w '%{http_code}' -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" "${hdr[@]}" -d "$body"); }
replayed(){ grep -qi '^Idempotent-Replayed: true' "$OUT/headers.txt" && echo true || echo false; }
mkbody(){ # scope_json date_from date_to params_json prompt model
  printf '{"scope_type":"%s","scope_json":%s,"warehouse_id":9004,"date_from":"%s","date_to":"%s","params":%s,"algorithm_version":"avg-ma-3sigma-v1"%s%s}' \
    "$1" "$2" "$3" "$4" "$5" "${6:-}" "${7:-}";
}
SEL='{"sku_ids":[9002]}'
P_OFF='{"window_days":28,"min_days":7,"enable_llm":false}'
P_ON='{"window_days":28,"min_days":7,"enable_llm":true}'

# ---------------------------------------------------------------- 基线建 + 载体
sep; log "=== A1 基线建（202）+ 请求窗口载体 ==="
B=$(mkbody SELECTED_SKUS "$SEL" 2026-09-16 2026-10-13 "$P_OFF")
post "b151r-k1" "$B" "req_b151r_a1"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
RID1=$(jf data.run_id)
chk "A1 HTTP" "202" "$POST_STATUS"
chk "A1 data_start_date = date_from" "2026-09-16" "$(q "SELECT DATE_FORMAT(data_start_date,'%Y-%m-%d') FROM shoppilot.analysis_runs WHERE id=$RID1;")"
chk "A1 data_end_date = date_to" "2026-10-13" "$(q "SELECT DATE_FORMAT(data_end_date,'%Y-%m-%d') FROM shoppilot.analysis_runs WHERE id=$RID1;")"
post "b151r-k1" "$B" "req_b151r_a2"
chk "A2 同键同体重放" "202:true:$RID1" "$POST_STATUS:$(replayed):$(jf data.run_id)"

# ---------------------------------------------------------------- R108 date_to
sep; log "=== B R108：同键仅改 date_to → COM-0009 ==="
B2=$(mkbody SELECTED_SKUS "$SEL" 2026-09-16 2026-10-12 "$P_OFF")
post "b151r-k1" "$B2" "req_b151r_b"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "B 期望 COM-0009/409" "COM-0009:409" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- R108 prompt/model
sep; log "=== C R108：prompt_version / model_name 变体 ==="
BC=$(mkbody SELECTED_SKUS '{"sku_ids":[9003]}' 2026-09-16 2026-10-13 "$P_ON" ',"prompt_version":"explain_risk.v1"' ',"model_name":"m1"')
post "b151r-k3" "$BC" "req_b151r_c1"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "C1 基线 202" "202" "$POST_STATUS"
BC2=$(mkbody SELECTED_SKUS '{"sku_ids":[9003]}' 2026-09-16 2026-10-13 "$P_ON" ',"prompt_version":"explain_risk.v2"' ',"model_name":"m1"')
post "b151r-k3" "$BC2" "req_b151r_c2"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "C2 改 prompt_version → COM-0009" "COM-0009:409" "$(jf code):$POST_STATUS"
BC3=$(mkbody SELECTED_SKUS '{"sku_ids":[9003]}' 2026-09-16 2026-10-13 "$P_ON" ',"prompt_version":"explain_risk.v1"' ',"model_name":"m2"')
post "b151r-k3" "$BC3" "req_b151r_c3"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "C3 改 model_name → COM-0009" "COM-0009:409" "$(jf code):$POST_STATUS"

# ---------------------------------------------------------------- 空白归一
sep; log "=== D 空白归一：prompt_version=\"\" 与缺省视为同体 ==="
BD=$(mkbody SELECTED_SKUS '{"sku_ids":[9005]}' 2026-09-16 2026-10-13 "$P_OFF" ',"prompt_version":""')
post "b151r-k5" "$BD" "req_b151r_d1"
chk "D1 基线 202" "202" "$POST_STATUS"
BD2=$(mkbody SELECTED_SKUS '{"sku_ids":[9005]}' 2026-09-16 2026-10-13 "$P_OFF")
post "b151r-k5" "$BD2" "req_b151r_d2"
log "STATUS=$POST_STATUS REPLAYED=$(replayed) BODY=$(cat "$OUT/body.json")"
chk "D2 缺省 vs 空白 → 重放 202" "202:true" "$POST_STATUS:$(replayed)"

# ---------------------------------------------------------------- R109 变体
sep; log "=== E R109：反斜杠 / 内嵌换行 ==="
BE=$(printf '{"scope_type":"CATEGORY","scope_json":{"category":"A\\\\B"},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":%s,"algorithm_version":"avg-ma-3sigma-v1"}' "$P_OFF")
post "b151r-k6" "$BE" "req_b151r_e1"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
RID6=$(jf data.run_id)
chk "E1 反斜杠 202" "202" "$POST_STATUS"
chk "E1 scope_json 合法" "1" "$(q "SELECT JSON_VALID(scope_json) FROM shoppilot.analysis_runs WHERE id=$RID6;")"
chk "E1 category 原值（hex 415C42）" "415C42" "$(q "SELECT HEX(JSON_UNQUOTE(JSON_EXTRACT(scope_json,'\$.category'))) FROM shoppilot.analysis_runs WHERE id=$RID6;")"

BF=$(printf '{"scope_type":"CATEGORY","scope_json":{"category":"A\\nB"},"warehouse_id":9004,"date_from":"2026-09-16","date_to":"2026-10-13","params":%s,"algorithm_version":"avg-ma-3sigma-v1"}' "$P_OFF")
post "b151r-k7" "$BF" "req_b151r_e2"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
RID7=$(jf data.run_id)
chk "E2 换行 202" "202" "$POST_STATUS"
chk "E2 category 原值（hex 410A42）" "410A42" "$(q "SELECT HEX(JSON_UNQUOTE(JSON_EXTRACT(scope_json,'\$.category'))) FROM shoppilot.analysis_runs WHERE id=$RID7;")"

# ---------------------------------------------------------------- 并发同键
sep; log "=== F 并发同键（4 路 × 6 轮）：同一 run、库内 1 行、不得误报 ANL-7001 ==="
CONC_BAD=0; CONC_7001=0; CONC_ROWS=0
for round in 1 2 3 4 5 6; do
  sku=$((9200 + round))
  CB=$(mkbody SELECTED_SKUS "{\"sku_ids\":[$sku]}" 2026-09-16 2026-10-13 "$P_OFF")
  rm -f "$OUT/c_$round"_*.json "$OUT/c_$round"_*.hdr
  pids=()
  for i in 1 2 3 4; do
    ( curl -s --noproxy '*' -o "$OUT/c_${round}_$i.json" -D "$OUT/c_${round}_$i.hdr" -w '%{http_code}' \
        -X POST "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs" \
        -H "Content-Type: application/json" -H "Authorization: Bearer $T_OP" \
        -H "Idempotency-Key: b151r-conc-$round" -H "X-Request-Id: req_b151r_conc_${round}_$i" \
        -d "$CB" > "$OUT/c_${round}_$i.status" ) &
    pids+=($!)
  done
  # ★ 只等本轮的 curl：裸 wait 会连 app/stub 一起等 → 永久挂起（首轮实测 58min 卡死）
  wait "${pids[@]}"
  ids=$("$PY" - "$OUT" "$round" <<'PYEOF'
import glob, json, sys
out, rnd = sys.argv[1], sys.argv[2]
ids, codes = set(), set()
for path in glob.glob(f"{out}/c_{rnd}_*.json"):
    try:
        d = json.load(open(path, encoding="utf-8"))
    except Exception:
        continue
    codes.add(d.get("code"))
    if d.get("code") == "OK" and d.get("data"):
        ids.add(d["data"].get("run_id"))
print(len(ids), ",".join(sorted(c for c in codes if c)))
PYEOF
)
  uniq=$(echo "$ids" | cut -d' ' -f1); codes=$(echo "$ids" | cut -d' ' -f2)
  rows=$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE idempotency_key='b151r-conc-$round';")
  log "ROUND $round: distinct_run_ids=$uniq codes=[$codes] rows=$rows"
  [ "$uniq" != "1" ] && CONC_BAD=$((CONC_BAD+1))
  [ "$rows" != "1" ] && CONC_ROWS=$((CONC_ROWS+1))
  echo "$codes" | grep -q "ANL-7001" && CONC_7001=$((CONC_7001+1))
done
chk "F1 每轮 distinct run_id 恰 1" "0" "$CONC_BAD"
chk "F2 每轮库内恰 1 行" "0" "$CONC_ROWS"
chk "F3 同键并发不得报 ANL-7001 的轮数" "0" "$CONC_7001"

# ---------------------------------------------------------------- 回归
sep; log "=== G 回归：ANL-7001 / 失败补偿 ==="
BG=$(mkbody SELECTED_SKUS "$SEL" 2026-09-16 2026-10-13 "$P_OFF")
post "b151r-k8" "$BG" "req_b151r_g1"
chk "G1 同范围不同键 → ANL-7001/409" "ANL-7001:409" "$(jf code):$POST_STATUS"
echo ai6002 > "$MODE_FILE"
BFAIL=$(mkbody CATEGORY '{"category":"食品"}' 2026-09-16 2026-10-13 "$P_OFF")
post "b151r-k9" "$BFAIL" "req_b151r_g2"
log "STATUS=$POST_STATUS BODY=$(cat "$OUT/body.json")"
chk "G2 AI-6002 → ANL-7012/409" "ANL-7012:409" "$(jf code):$POST_STATUS"
chk "G2 run FAILED 且无 PENDING" "0" "$(q "SELECT COUNT(*) FROM shoppilot.analysis_runs WHERE status='PENDING';")"
echo ok > "$MODE_FILE"

sep
log "RB151R_TOTAL=$((PASS+FAIL+PROBE))"
log "RB151R_PASS=$PASS FAIL=$FAIL PROBE_NOTE=$PROBE"
[ "$FAIL" = 0 ] && log "RB151R_RESULT=ALL PASS" || log "RB151R_RESULT=HAS FAILURES"
exit 0
