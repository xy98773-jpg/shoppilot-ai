#!/usr/bin/env bash
# =============================================================================
# M4-B20.4 开发自证探针（V5 / V6 原始响应取证）
# -----------------------------------------------------------------------------
# 目的：任务书 §V5/§V6 要求「贴一次真实响应」，而集成测试只断言不打印 body。
# 本脚本用**独立** MySQL 容器 + 独立 fat jar + 独立端口，走真实 HTTP 抓原始 JSON：
#   V5  POST /api/v1/analysis/runs/{id}/cancel  → 200 + data{run_id,status,cancelled_at}
#   V6  GET  /api/v1/analysis/runs/{id}/items?page=abc → 400 COM-0001 + details[0].field
# 顺带交叉验证：
#   - 幂等重放（再取消一次）→ 200 且 cancelled_at 不变、审计仍 1 条；
#   - 终态 SUCCEEDED → 422 ANL-7011；
#   - 远端取消不可达（AI_SERVICE_BASE_URL 指向关闭端口）→ 仍 200（A5 尽力而为）；
#   - pageSize=abc → 400 COM-0001（证明是全局 handler 而非单点特判）。
# 生命周期：trap cleanup（容器/进程全回收）。不依赖 service-ai（本批不涉及）。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
JAR="$ROOT/backend-java/app/target/shoppilot-app-0.1.0.jar"
OUT="$ROOT/backend-java/tools/rb-b204"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
MYSQL_NAME="rb-b204-mysql"
MYSQL_PORT="${MYSQL_PORT:-3381}"
APP_PORT="${APP_PORT:-18321}"
EVI="$OUT/evidence-b204.txt"
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
    if d is None: print('<null>'); raise SystemExit
    d = d[int(k)] if k.isdigit() and isinstance(d,list) else (d.get(k) if isinstance(d,dict) else None)
print('' if d is None else d)" "$BODY" "$1"; }

cleanup(){
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null
  wait 2>/dev/null
  docker rm -f "$MYSQL_NAME" >/dev/null 2>&1
}
trap cleanup EXIT

[ -f "$JAR" ] || { log "FATAL: fat jar 不存在：$JAR（先跑 V3 package）"; exit 4; }
log "JAR=$JAR"
log "JAR_SIZE=$(stat -c%s "$JAR")  JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

for p in "$MYSQL_PORT" "$APP_PORT"; do
  netstat -ano | grep -E ":${p}[[:space:]].*LISTENING" >/dev/null 2>&1 && { log "FATAL: 端口 $p 占用"; exit 6; }
done
log "PORTS_FREE $MYSQL_PORT/$APP_PORT"

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
log "MYSQL_READY=1 (port $MYSQL_PORT)"

# ---------------------------------------------------------------- app
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC"
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_PASSWORD=rootpw
export JWT_SECRET="rb-b204-independent-secret-0123456789"
# ★ 指向关闭端口：验证「远端取消失败 → 仍 200、本地 CANCELLED、只 WARN」（§A5）
export AI_SERVICE_BASE_URL="http://127.0.0.1:1"
export AI_INTERNAL_TOKEN="rb-b204-internal-token"
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

q(){ docker exec -i "$MYSQL_NAME" mysql -uroot -prootpw -N --default-character-set=utf8mb4 shoppilot -e "$1" 2>>"$OUT/q.err" | tr -d '\r'; }

# ---------------------------------------------------------------- 种子（表已由 Flyway 建好）
q "INSERT INTO teams (id, code, name, status, default_timezone) VALUES (7,'T-7','七组','ACTIVE','Asia/Shanghai');" >/dev/null
q "INSERT INTO users (id, username, email, password_hash, display_name, status) VALUES (1001,'operator01','operator01@example.com','\$2a\$10\$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6','小王','ACTIVE');" >/dev/null
q "INSERT INTO team_members (team_id,user_id,role,status) VALUES (7,1001,'OPERATOR','ACTIVE');" >/dev/null
q "INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version, llm_status, progress) VALUES (7,'RUN-B204-PENDING','PENDING','ALL_ACTIVE','avg-ma-3sigma-v1','NOT_REQUESTED',0);" >/dev/null
q "INSERT INTO analysis_runs (team_id, run_no, status, scope_type, algorithm_version, llm_status, progress) VALUES (7,'RUN-B204-SUCCEEDED','SUCCEEDED','ALL_ACTIVE','avg-ma-3sigma-v1','SUCCEEDED',100);" >/dev/null
RUN_PENDING=$(q "SELECT id FROM analysis_runs WHERE run_no='RUN-B204-PENDING';")
RUN_SUCCEEDED=$(q "SELECT id FROM analysis_runs WHERE run_no='RUN-B204-SUCCEEDED';")
chk "种子 run 就绪（PENDING/SUCCEEDED）" "2" "$(q "SELECT COUNT(*) FROM analysis_runs WHERE run_no LIKE 'RUN-B204-%';")"
log "RUN_PENDING=$RUN_PENDING RUN_SUCCEEDED=$RUN_SUCCEEDED"

curl -s --noproxy '*' -X POST "http://127.0.0.1:$APP_PORT/api/v1/auth/login" \
  -H "Content-Type: application/json" -d '{"username":"operator01","password":"S3cret!Passw0rd"}' > "$BODY"
T_OP=$(jget "data.accessToken")
chk "operator01 登录" "1" "$([ -n "$T_OP" ] && [ "$T_OP" != "<unparsable>" ] && echo 1 || echo 0)"

# ---------------------------------------------------------------- V5：取消 200 + data 三字段
sep; log "=== V5 取消端点原始响应（POST /api/v1/analysis/runs/$RUN_PENDING/cancel）==="
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RUN_PENDING/cancel" \
  -H "Authorization: Bearer $T_OP")
log "HTTP $CODE"
log "BODY $(cat "$BODY")"
chk "V5 HTTP" "200" "$CODE"
chk "V5 code" "OK" "$(jget code)"
chk "V5 data.run_id" "$RUN_PENDING" "$(jget data.run_id)"
chk "V5 data.status" "CANCELLED" "$(jget data.status)"
CANCELLED_AT=$(jget data.cancelled_at)
log "V5 cancelled_at=$CANCELLED_AT"
echo "$CANCELLED_AT" | grep -Eq '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{3}Z$' \
  && chk "V5 cancelled_at 形态（ISO-8601 UTC 毫秒）" "ok" "ok" \
  || chk "V5 cancelled_at 形态（ISO-8601 UTC 毫秒）" "ok" "bad:$CANCELLED_AT"
chk "V5 DB status" "CANCELLED" "$(q "SELECT status FROM analysis_runs WHERE id=$RUN_PENDING;")"
chk "V5 DB current_step" "CANCELLED" "$(q "SELECT current_step FROM analysis_runs WHERE id=$RUN_PENDING;")"
chk "V5 DB finished_at 非空" "1" "$(q "SELECT COUNT(*) FROM analysis_runs WHERE id=$RUN_PENDING AND finished_at IS NOT NULL;")"
chk "V5 审计 ANALYSIS_RUN_CANCEL 恰 1 条" "1" "$(q "SELECT COUNT(*) FROM audit_logs WHERE action='ANALYSIS_RUN_CANCEL' AND biz_id=$RUN_PENDING AND result='SUCCESS';")"
chk "V5 不额外记 ANALYSIS_RUN_FINISH" "0" "$(q "SELECT COUNT(*) FROM audit_logs WHERE action='ANALYSIS_RUN_FINISH' AND biz_id=$RUN_PENDING;")"
log "NOTE 远端取消不可达（AI_SERVICE_BASE_URL=127.0.0.1:1）仍返回 200 ⇒ §A5 尽力而为生效"
grep -c "取消分析任务失败\|远端取消未成功\|远端取消异常" "$APP_LOG" | sed 's/^/NOTE WARN 行数=/' | tee -a "$EVI"

# ---------------------------------------------------------------- 幂等重放
sep; log "=== 幂等重放：再取消一次 ==="
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RUN_PENDING/cancel" \
  -H "Authorization: Bearer $T_OP")
log "HTTP $CODE  BODY $(cat "$BODY")"
chk "重放 HTTP" "200" "$CODE"
chk "重放 cancelled_at 不变" "$CANCELLED_AT" "$(jget data.cancelled_at)"
chk "重放不重复审计" "1" "$(q "SELECT COUNT(*) FROM audit_logs WHERE action='ANALYSIS_RUN_CANCEL' AND biz_id=$RUN_PENDING;")"

# ---------------------------------------------------------------- 终态 422
sep; log "=== 终态 SUCCEEDED 取消 ⇒ 422 ANL-7011 ==="
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' -X POST \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RUN_SUCCEEDED/cancel" \
  -H "Authorization: Bearer $T_OP")
log "HTTP $CODE  BODY $(cat "$BODY")"
chk "终态 HTTP" "422" "$CODE"
chk "终态 code" "ANL-7011" "$(jget code)"
chk "终态状态不变" "SUCCEEDED" "$(q "SELECT status FROM analysis_runs WHERE id=$RUN_SUCCEEDED;")"

# ---------------------------------------------------------------- V6：page=abc
sep; log "=== V6 page=abc 原始响应（GET /api/v1/analysis/runs/$RUN_SUCCEEDED/items?page=abc）==="
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RUN_SUCCEEDED/items?page=abc" \
  -H "Authorization: Bearer $T_OP")
log "HTTP $CODE"
log "BODY $(cat "$BODY")"
chk "V6 HTTP" "400" "$CODE"
chk "V6 code" "COM-0001" "$(jget code)"
chk "V6 details[0].field" "page" "$(jget details.0.field)"
chk "V6 details[0].rejectedValue" "abc" "$(jget details.0.rejectedValue)"

sep; log "=== 交叉验证 pageSize=abc（全局 handler 而非单点特判）==="
CODE=$(curl -s --noproxy '*' -o "$BODY" -w '%{http_code}' \
  "http://127.0.0.1:$APP_PORT/api/v1/analysis/runs/$RUN_SUCCEEDED/items?pageSize=abc" \
  -H "Authorization: Bearer $T_OP")
log "HTTP $CODE  BODY $(cat "$BODY")"
chk "pageSize=abc HTTP" "400" "$CODE"
chk "pageSize=abc details[0].field" "page_size" "$(jget details.0.field)"

# ---------------------------------------------------------------- 汇总
sep
log "RESULT: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = 0 ] && log "ALL PASS" || log "HAS FAILURE"
exit 0
