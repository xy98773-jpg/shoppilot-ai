#!/usr/bin/env bash
# =============================================================================
# ★ 项目负责人独立复核 B8-hotfix（R68 · sale_date 范围校验）——不采信自报
#
# 独立 MySQL(3330) + 自建 fat jar(:18110)。
# 端口刻意错开：B7 复核 3327/18107、B8 开发 3328/18108、B8 复核 3329/18109、本次 3330/18110。
#
# 设计原则（R62/R68 两轮教训）：开发已覆盖 W1–W3b 的「正向拦截」，本脚本**不重复证明同一件事**，
# 而是补它没测的四类：
#   A 反向误杀风险   —— 新校验会不会把合法写入也拦掉（NULL 上架日 / 历史行覆盖 / 边界日）
#   B 校验优先级     —— 日期校验插在 sku/仓库校验之间，错误码顺序是否合理、有无信息泄露
#   C 副产物污染     —— 被拒请求是否留下审计/残留行
#   D 相邻回归       —— 格式非法、列表端点是否受影响
#
# 组次：
#   V1  覆盖更新历史行（9002/WH1/2026-10-03 15→7）→ 201/201，1 行，qty=7   ← 反向误杀
#   V2  listed_date=NULL 的 SKU 用很早日期(2026-01-05) → 201               ← 反向误杀
#   V3  未来日期(tomorrow) → 400 SAL-6003 且零落库                          ← 复现 W1
#   V4  早于 listed_date(9008: 2026-09-30) → 400 SAL-6003 且零落库          ← 复现 W2
#   V5  边界日 = listed_date(9008: 2026-10-01) → 201                        ← 复现 W3b
#   V6  今天(9008) → 201                                                   ← 复现 W3
#   V7  未来日期 + 不存在 SKU(9999) → 422 SAL-6005                          ← 优先级/泄露
#   V8  早于上架日 + 异团队 SKU(9102) → 422 SAL-6005                        ← 优先级/泄露
#   V9  DB 残留：sale_date > TODAY 的行数 = 0                               ← 副产物
#   V10 失败请求不新增审计（V3/V4 前后 SALES_DAILY_UPSERT 条数不变）          ← 副产物
#   V11 格式非法(2026-13-40) → 400 SAL-6003                                 ← 相邻回归
#   V12 GET 列表端点仍 200                                                  ← 相邻回归
#   V13 观察项（不设断言）：未来+qty负、未来+异团队仓库 的实际错误码优先级
#
# 用法：bash backend-java/tools/rb-b8h/run-rb8h-evidence.sh
# 产物：evidence-rb8h.txt（入库） / evidence-rb8h-app.log（*.log 已 gitignore）
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb8h.txt"
APP_LOG="$OUT_DIR/evidence-rb8h-app.log"
MYSQL_CT="rb8h-mysql"
MYSQL_PORT=3330
APP_PORT=18110
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"; : > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

[ -n "$JAR" ] || { log "FATAL: jar not found"; exit 2; }
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -d' ' -f1)"

cleanup(){ log "--- cleanup ---"; docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT)"
[ "$READY" = "1" ] || { log "FATAL mysql"; exit 3; }

SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
log "APP_PID=$! (port $APP_PORT)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app"; exit 4; }

docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot shoppilot \
  < "$EVI/seed-b8.sql" >> "$LOG" 2>&1 && log "SEED_OK"

# ★ 中文断言必须显式 utf8mb4（B8 开发踩过的坑：否则 LIKE '%覆盖%' 恒不匹配）
sql(){ docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot -N -B shoppilot -e "$1" 2>/dev/null; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
post(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/sales/daily" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$2"; }

TODAY=$(date +%F)
TOMORROW=$(date -d '+1 day' +%F)
log "TODAY=$TODAY TOMORROW=$TOMORROW"

OP=$(token operator01); OWN=$(token owner01)
log "TOKENS op=${OP:0:10} own=${OWN:0:10}"
[ -n "$OP" ] || { log "FATAL operator01 login failed"; exit 5; }

FAIL=0
chk(){ if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi; }
note(){ log "NOTE $*"; }

# ===== V0 环境自检 =====
sep; log "V0 迁移链 / 应用就绪（确认跑的是含 hotfix 的新 jar）"
chk "V0-迁移条数" "9" "$(sql "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1")"
chk "V0-最高版本" "1.0.8" "$(sql "SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1")"
chk "V0-SKU-E上架日" "2026-10-01" "$(sql "SELECT listed_date FROM skus WHERE id=9008")"

# ===== V1 反向误杀：覆盖更新历史行 =====
sep; log "V1 覆盖更新历史行（9002/WH1/2026-10-03 先 15 再 7）→ 新校验不得阻断合法覆盖"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"15.000"}')
chk "V1-首次http" "201" "${R##*|}"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-10-03","qty":"7.000"}')
chk "V1-二次http" "201" "${R##*|}"
chk "V1-行数仍1" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")"
chk "V1-qty为新值" "7.000" "$(sql "SELECT qty FROM sales_daily WHERE sku_id=9002 AND warehouse_id=9004 AND sale_date='2026-10-03'")"

# ===== V2 反向误杀：listed_date 为 NULL 不得误杀早期日期 =====
sep; log "V2 listed_date=NULL 的 SKU（9003）用 2026-01-05 → 必须放行（NULL 不参与比较）"
chk "V2-前置listed_date为NULL" "NULL" "$(sql "SELECT IFNULL(listed_date,'NULL') FROM skus WHERE id=9003")"
R=$(post "$OP" '{"sku_id":9003,"warehouse_id":9004,"sale_date":"2026-01-05","qty":"1.000"}')
chk "V2-http" "201" "${R##*|}"
chk "V2-已落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9003 AND sale_date='2026-01-05'")"

# ===== V10 前置：审计基线（放在 V3/V4 之前）=====
A0=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")
log "V10-审计基线 A0=$A0"

# ===== V3 复现 W1：未来日期 =====
sep; log "V3 sale_date 晚于今天（$TOMORROW）→ 400 SAL-6003 且零落库"
R=$(post "$OP" "{\"sku_id\":9008,\"warehouse_id\":9004,\"sale_date\":\"$TOMORROW\",\"qty\":\"1.000\"}")
chk "V3-code" "SAL-6003" "$(code "${R%|*}")"
chk "V3-http" "400" "${R##*|}"
chk "V3-零落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9008 AND sale_date='$TOMORROW'")"
log "V3-body=${R%|*}"

# ===== V4 复现 W2：早于上架日 =====
sep; log "V4 sale_date 早于 listed_date（2026-09-30 < 2026-10-01）→ 400 SAL-6003 且零落库"
R=$(post "$OP" '{"sku_id":9008,"warehouse_id":9004,"sale_date":"2026-09-30","qty":"1.000"}')
chk "V4-code" "SAL-6003" "$(code "${R%|*}")"
chk "V4-http" "400" "${R##*|}"
chk "V4-零落库" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9008 AND sale_date='2026-09-30'")"
log "V4-body=${R%|*}"

# ===== V10 副产物：失败请求不得写审计 =====
sep; log "V10 被拒请求（V3/V4）不得新增审计"
A1=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='SALES_DAILY_UPSERT'")
chk "V10-审计条数不变" "$A0" "$A1"

# ===== V5 复现 W3b：边界日放行 =====
sep; log "V5 边界日 = listed_date（2026-10-01）→ 201（比较符必须 < 而非 <=）"
R=$(post "$OP" '{"sku_id":9008,"warehouse_id":9004,"sale_date":"2026-10-01","qty":"1.000"}')
chk "V5-http" "201" "${R##*|}"
chk "V5-已落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9008 AND sale_date='2026-10-01'")"

# ===== V6 复现 W3：今天放行 =====
sep; log "V6 今天（$TODAY）→ 201（不得误杀当天数据）"
R=$(post "$OP" "{\"sku_id\":9008,\"warehouse_id\":9004,\"sale_date\":\"$TODAY\",\"qty\":\"2.000\"}")
chk "V6-http" "201" "${R##*|}"
chk "V6-已落库" "1" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sku_id=9008 AND sale_date='$TODAY'")"
chk "V6-值正确" "2.000" "$(sql "SELECT qty FROM sales_daily WHERE sku_id=9008 AND sale_date='$TODAY'")"

# ===== V7 优先级：未来日期 + 不存在 SKU =====
sep; log "V7 未来日期 + 不存在 SKU(9999) → 期望 422 SAL-6005（sku 校验在日期之前，不泄露存在性）"
R=$(post "$OP" "{\"sku_id\":9999,\"warehouse_id\":9004,\"sale_date\":\"$TOMORROW\",\"qty\":\"1.000\"}")
chk "V7-code" "SAL-6005" "$(code "${R%|*}")"
chk "V7-http" "422" "${R##*|}"

# ===== V8 优先级：早于上架日 + 异团队 SKU =====
sep; log "V8 早于上架日 + 异团队 SKU(9102, team8) → 期望 422 SAL-6005（隔离先于日期，不泄露上架日）"
R=$(post "$OP" '{"sku_id":9102,"warehouse_id":9004,"sale_date":"2026-09-30","qty":"1.000"}')
chk "V8-code" "SAL-6005" "$(code "${R%|*}")"
chk "V8-http" "422" "${R##*|}"

# ===== V9 副产物：无未来日期残留 =====
sep; log "V9 全表残留检查：sale_date > 今天 的行数必须为 0"
chk "V9-未来日期残留" "0" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE sale_date > '$TODAY'")"

# ===== V11 相邻回归：格式非法 =====
sep; log "V11 sale_date 格式非法（2026-13-40）→ 400 SAL-6003"
R=$(post "$OP" '{"sku_id":9002,"warehouse_id":9004,"sale_date":"2026-13-40","qty":"1.000"}')
chk "V11-code" "SAL-6003" "$(code "${R%|*}")"
chk "V11-http" "400" "${R##*|}"

# ===== V12 相邻回归：GET 列表 =====
sep; log "V12 GET /sales/daily 仍 200（新增校验不得影响读路径）"
H=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/sales/daily" -H "Authorization: Bearer $OP")
chk "V12-http" "200" "$H"

# ===== V13 观察项（不设断言，仅记录真实优先级）=====
sep; log "V13 观察项：校验优先级（仅记录，不作断言）"
R=$(post "$OP" "{\"sku_id\":9008,\"warehouse_id\":9004,\"sale_date\":\"$TOMORROW\",\"qty\":\"-1\"}")
note "V13a 未来日期+qty=-1 → code=$(code "${R%|*}") http=${R##*|}"
R=$(post "$OP" "{\"sku_id\":9008,\"warehouse_id\":9103,\"sale_date\":\"$TOMORROW\",\"qty\":\"1.000\"}")
note "V13b 未来日期+异团队仓库 → code=$(code "${R%|*}") http=${R##*|}"
R=$(post "$OP" "{\"sku_id\":9008,\"warehouse_id\":9004,\"sale_date\":\"$TOMORROW\",\"qty\":\"1.000\",\"source\":\"BAD\"}")
note "V13c 未来日期+非法source → code=$(code "${R%|*}") http=${R##*|}"

sep
TOTAL=$(grep -c "^PASS \|^FAIL " "$LOG")
log "RB8H_CHECK_TOTAL=$TOTAL"
log "RB8H_FAIL_COUNT=$FAIL"
log "=== RB8H EVIDENCE DONE ==="
exit 0
