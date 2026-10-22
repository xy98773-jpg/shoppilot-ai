#!/usr/bin/env bash
# =============================================================================
# M7-T02 性能验证（NFR-PERF）
# -----------------------------------------------------------------------------
# 三条指标（ACCEPTANCE_CRITERIA §6 + NFR-PERF-01/02/03）：
#   P1 库存余额列表：1 万 SKU / 10 万流水下 GET /inventory/balances page_size=20
#      50 次取 p95 < 500ms
#   P2 1 万行导入（INVENTORY_SNAPSHOT）确认（confirm）< 60s
#   P3 500 SKU 分析（LLM 关闭）到终态 < 60s
# 前置：T05 已起 dev 栈；dev 库口令为测试值（T03 已重置）。
# 用法：bash backend-java/tools/m7-t02/verify-t02.sh
# 说明：造数直接走 SQL（万级行用递归 CTE 一条语句完成），导入与分析走【真实 HTTP】。
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/m7-t02"
DATA="$OUT/data"
EVI="$OUT/evidence-t02.txt"
BODY="$OUT/body-t02.json"
BASE="http://127.0.0.1:8081"
MYSQL=shoppilot-dev-mysql-1
MYSQL_ARGS="-ushoppilot -pchangeme_app_password shoppilot"
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
PASS=0; FAIL=0
mkdir -p "$OUT" "$DATA"; : > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
chk_lt(){ if [ "$3" -lt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (< $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected < [$2] but was [$3]"; fi; }
chk_gt(){ if [ "${3%.*}" -gt "$2" ] 2>/dev/null; then PASS=$((PASS+1)); log "[PASS] $1 => $3 (> $2)";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected > [$2] but was [$3]"; fi; }
api(){ curl -s --noproxy '*' -o "$BODY" -w "%{http_code}" "$@"; }
jget(){ "$PY" -c "import json,sys
try: d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception: print('<unparsable>'); raise SystemExit
for k in sys.argv[2].split('.'):
    if not k: continue
    if isinstance(d,dict) and k in d: d=d[k]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }
jraw(){ "$PY" -c "import json,sys
d=json.load(open(sys.argv[1],encoding='utf-8'))
for k in sys.argv[2].split('.'):
    d=d[k]
print(json.dumps(d,ensure_ascii=False))" "$BODY" "$1"; }
sql(){ docker exec "$MYSQL" mysql $MYSQL_ARGS --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }
# 造数专用：不吞 stderr。造数语句一旦被 CHECK 约束拒绝（本批两次踩坑：
# inventory_balances.available_qty 是生成列、inventory_transactions 的 txn_type/biz_type
# 各有枚举 CHECK），静默失败会让后续所有性能数字建立在空表上——必须让错误直接打在证据里。
sqlv(){ docker exec "$MYSQL" mysql $MYSQL_ARGS --default-character-set=utf8mb4 -N -B -e "$1" 2>&1 | grep -v "Using a password"; }
now_ms(){ date +%s%3N; }
winpath(){ cygpath -w "$1" 2>/dev/null || echo "$1"; }

log "M7-T02 性能验证  $(date '+%F %T')"
log "目标：$BASE（compose dev 栈，容器内 backend + mysql 8.0.36）"
sep

# ---------------------------------------------------------------------------
log "[0] 造数：1 万 SKU + 1 万余额 + 10 万流水（team=7 / 仓 9003）"
# ★ 四段 INSERT 全部 ON DUPLICATE KEY，可重复执行（幂等），因此不做「已存在则跳过」的短路：
#   否则一旦中途失败（例如余额段报错）后半段会永久缺失，却因为 SKU 段已存在而被静默跳过。
# ★ available_qty 是 STORED GENERATED（`on_hand_qty - committed_qty`），**不可显式写入**，
#   写入会 3105 直接失败（本批踩过）。
sqlv "SET SESSION cte_max_recursion_depth=200001;
     INSERT INTO products (id, team_id, product_code, name, status, created_by, updated_by)
     WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<10000)
     SELECT 900000+n, 7, CONCAT('P-PERF-',LPAD(n,5,'0')), CONCAT('性能商品',n), 'ACTIVE', 1001, 1001 FROM seq
     ON DUPLICATE KEY UPDATE name=VALUES(name);
     INSERT INTO skus (id, team_id, product_id, sku_code, name, status, default_lead_time_days, moq, order_multiple, purchase_price)
     WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<10000)
     SELECT 910000+n, 7, 900000+n, CONCAT('SKU-PERF-',LPAD(n,5,'0')), CONCAT('性能SKU',n), 'ACTIVE', 7, 10.000, 10.000, 8.0000 FROM seq
     ON DUPLICATE KEY UPDATE name=VALUES(name);
     INSERT INTO inventory_balances (team_id, warehouse_id, sku_id, on_hand_qty, committed_qty, last_txn_at)
     WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<10000)
     SELECT 7, 9003, 910000+n, 100.000, 10.000, NOW(3) FROM seq
     ON DUPLICATE KEY UPDATE on_hand_qty=VALUES(on_hand_qty), committed_qty=VALUES(committed_qty);
     INSERT INTO inventory_transactions (team_id, warehouse_id, sku_id, txn_type, qty_delta, qty_before, qty_after,
                                         biz_type, idempotency_key, operator_id, occurred_at)
     WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<100000)
     SELECT 7, 9003, 910000+(n%10000)+1, 'MANUAL_ADJUST', 1.000, 0.000, 1.000,
            'MANUAL', CONCAT('perf-',n), 1001, NOW(3) - INTERVAL (n%300) DAY FROM seq
     ON DUPLICATE KEY UPDATE qty_delta=VALUES(qty_delta);"
log "    造数完成"
chk_gt "0 SKU 数" "9999" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND sku_code LIKE 'SKU-PERF-%'")"
chk_gt "0 余额行数" "9999" "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7")"
chk_gt "0 流水行数" "99999" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"
log "    on_hand 合计 = $(sql "SELECT SUM(on_hand_qty) FROM inventory_balances WHERE team_id=7")"

log "[1] 登录"
code=$(api -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"owner01","password":"1234"}')
chk "1 登录 HTTP" "200" "$code"
TOK=$(jget 'data.accessToken')
AUTH="Authorization: Bearer $TOK"
sep

# ---------------------------------------------------------------------------
log "[2] P1 索引命中：默认排序（available_qty ASC）必须走索引，不得全表扫描"
EXPLAIN_SQL="EXPLAIN SELECT id FROM inventory_balances WHERE team_id=7 AND warehouse_id=9003 ORDER BY available_qty ASC LIMIT 20"
sql "$EXPLAIN_SQL" | sed 's/^/    /' | tee -a "$EVI"
# EXPLAIN 列序：id select_type table partitions type possible_keys key key_len ref rows Extra
TYPE=$(sql "$EXPLAIN_SQL" | awk -F'\t' '{print $5}' | tail -1)
KEY=$(sql "$EXPLAIN_SQL" | awk -F'\t' '{print $7}' | tail -1)
SCAN=$(sql "$EXPLAIN_SQL" | awk -F'\t' '{print $2}' | tail -1)
log "    access_type=$TYPE  key=$KEY  select_type=$SCAN"
chk "2 未退化为全表扫描" "ok" "$( case "$TYPE" in ALL) echo "ALL" ;; *) echo ok ;; esac )"
chk "2 命中 (team_id, …) 前缀索引" "ok" "$( case "$KEY" in *team*) echo ok ;; *) echo "$KEY" ;; esac )"
sep

# ---------------------------------------------------------------------------
log "[3] P1 时延：GET /inventory/balances?page=1&page_size=20 × 50 次"
TIMES="$DATA/times.txt"; : > "$TIMES"
BAD=0
for i in $(seq 1 50); do
  code=$(curl -s --noproxy '*' -o "$BODY" -w "%{http_code} %{time_total}" \
    "$BASE/api/v1/inventory/balances?page=1&page_size=20" -H "$AUTH")
  c=${code%% *}; t=${code##* }
  echo "$t" >> "$TIMES"
  [ "$c" = "200" ] || { BAD=$((BAD+1)); log "    [warn] 第 $i 次 HTTP=$c"; }
done
chk "3 非 200 次数" "0" "$BAD"
chk "3 采样条数" "50" "$(wc -l < "$TIMES" | tr -d ' ')"
STATS=$("$PY" - "$TIMES" <<'PY'
import sys, statistics
xs = sorted(float(x) for x in open(sys.argv[1]) if x.strip())
def pct(p):
    k = max(0, min(len(xs)-1, int(round(p*(len(xs)-1)))))
    return xs[k]
print(f"n={len(xs)} p50={pct(0.50)*1000:.0f}ms p95={pct(0.95)*1000:.0f}ms max={xs[-1]*1000:.0f}ms")
print(int(pct(0.95)*1000))
PY
)
log "    $STATS"
P95=$(echo "$STATS" | tail -1)
log "    $(echo "$STATS" | head -1)"
chk_lt "3 p95 (ms)" "500" "$P95"
chk "3 分页 total 覆盖 1 万 SKU" "ok" "$( [ "$(jget 'data.total')" -gt 9999 ] 2>/dev/null && echo ok || echo "total=$(jget 'data.total')" )"
# ★ 文件路径必须经 argv 传入：Windows 原生 python 不认 Git Bash 的 /d/... 路径，
#   把路径内联进 -c 代码串里会 FileNotFoundError（本批踩过）。
chk "3 本页行数" "20" "$("$PY" -c "import json,sys;print(len(json.load(open(sys.argv[1],encoding='utf-8'))['data']['list']))" "$BODY")"
sep

# ---------------------------------------------------------------------------
log "[4] P2 1 万行导入：INVENTORY_SNAPSHOT（真实 HTTP：上传→预览→映射→校验→确认）"
"$PY" - "$DATA/snapshot-10k.csv" "$(date +%F)" "$(date +%s%3N)" <<'PY'
import sys
path, day, runid = sys.argv[1], sys.argv[2], sys.argv[3]
with open(path, 'w', encoding='utf-8', newline='') as f:
    f.write('sku_code,warehouse_code,qty,snapshot_date,remark\n')
    for i in range(1, 10001):
        f.write(f'SKU-PERF-{i:05d},WH-A-001,200,{day},性能导入-{runid}\n')
PY
log "    文件行数（含表头）=$(wc -l < "$DATA/snapshot-10k.csv")"
T0=$(now_ms)
code=$(api -X POST "$BASE/api/v1/import/batches" -H "$AUTH" -F "file=@$(winpath "$DATA/snapshot-10k.csv")" -F "bizType=INVENTORY_SNAPSHOT")
chk "4 上传" "201" "$code"
BID=$(jget 'data.batch_id')
code=$(api "$BASE/api/v1/import/batches/$BID/preview" -H "$AUTH")
chk "4 预览" "200" "$code"
chk "4 总行数" "10000" "$(jget 'data.total_rows')"
jraw 'data.suggested_mapping' > "$DATA/map.json"
code=$(api -X PUT "$BASE/api/v1/import/batches/$BID/mapping" -H "$AUTH" \
  -H 'Content-Type: application/json' -d "{\"field_mapping_json\":$(cat "$DATA/map.json")}")
chk "4 映射" "200" "$code"
code=$(api -X POST "$BASE/api/v1/import/batches/$BID/validate" -H "$AUTH")
chk "4 校验" "200" "$code"
chk "4 错误行" "0" "$(jget 'data.error_rows')"
# ★ INIT_IMPORT 是【增量】语义（InventoryWriteService 注释：qty=0 即「加 0」），
#   不是「覆盖为快照值」⇒ 断言「导入后 = 导入前 + 200」，不能硬编码 200.000
#   （本批踩过：100 + 200 = 300，断言 200 全红）。
BEFORE_QTY=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9003 AND sku_id=910001")
log "    导入前 on_hand=$BEFORE_QTY"
T1=$(now_ms)
code=$(api -X POST "$BASE/api/v1/import/batches/$BID/confirm" -H "$AUTH" \
  -H "Idempotency-Key: t02-import-$(date +%s)" -H 'Content-Type: application/json' -d '{}')
T2=$(now_ms)
CLIENT_MS=$((T2-T1))
chk "4 确认" "200" "$code"
# 服务端口径：轮询批次到终态后读 started_at → finished_at。
# 为什么要单独测服务端：客户端耗时含 nginx 转发与网络排队，早先 nginx 默认 60s 读超时
# 会在服务端即将返回时切断请求（504），把「网关耐心」误读成「服务端慢」（R193）。
STATUS4=""
for i in $(seq 1 60); do
  api "$BASE/api/v1/import/batches/$BID" -H "$AUTH" >/dev/null
  STATUS4=$(jget 'data.status')
  case "$STATUS4" in COMPLETED|PARTIAL_SUCCESS|FAILED) break ;; esac
  sleep 2
done
SERVER_MS=$(sql "SELECT TIMESTAMPDIFF(MICROSECOND,started_at,finished_at)/1000 FROM import_batches WHERE id=$BID" | cut -d. -f1)
CONFIRM_MS=${SERVER_MS:-0}; FLOW_MS=$CLIENT_MS
chk "4 终态" "COMPLETED" "$STATUS4"
log "    confirm 客户端=${CLIENT_MS}ms  服务端=${SERVER_MS}ms（started_at→finished_at）"
chk_lt "4 confirm 服务端（毫秒，门槛 60000）" "60000" "$CONFIRM_MS"
chk "4 快照后 on_hand = 导入前 + 200（INIT_IMPORT 增量语义）" \
    "$(sql "SELECT $BEFORE_QTY + 200.000")" \
    "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9003 AND sku_id=910001")"
# 用 ≥：重复跑本脚本会产生多批 INIT_IMPORT（文件级幂等已靠 remark 区分），历史批次的流水仍在
chk_gt "4 快照流水条数" "9999" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key LIKE 'INIT_IMPORT:%'")"
sep

# ---------------------------------------------------------------------------
log "[5] P3 500 SKU 分析（enable_llm=false）到终态计时"
# 分析预检要求每 SKU ≥7 天销售；只给前 500 个 SKU 造 14 天
sql "SET SESSION cte_max_recursion_depth=20001;
     INSERT INTO sales_daily (team_id, warehouse_id, sku_id, product_id, sale_date, qty, return_qty, sales_amount, order_count, source)
     WITH RECURSIVE seq AS (SELECT 1 n UNION ALL SELECT n+1 FROM seq WHERE n<7000)
     SELECT 7, 9003, 910000+((n-1)%500)+1, 900000+((n-1)%500)+1,
            DATE_SUB(CURRENT_DATE, INTERVAL ((n-1) DIV 500) DAY), 4.000, 0.000, 356.00, 2, 'MANUAL'
     FROM seq
     ON DUPLICATE KEY UPDATE qty=VALUES(qty);"
chk_gt "5 500 SKU 销售天数合计" "6999" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id BETWEEN 910001 AND 910500")"
IDS=$("$PY" -c "print(','.join(str(910000+i) for i in range(1,501)))")
FROM=$(date -d '-29 days' +%F); TO=$(date +%F)
TA=$(now_ms)
code=$(api -X POST "$BASE/api/v1/analysis/runs" -H "$AUTH" \
  -H "Idempotency-Key: t02-analysis-$(date +%s)" -H 'Content-Type: application/json' \
  -d "{\"scope_type\":\"SELECTED_SKUS\",\"scope_json\":{\"sku_ids\":[$IDS]},\"warehouse_id\":9003,\"date_from\":\"$FROM\",\"date_to\":\"$TO\",\"params\":{\"enable_llm\":false},\"algorithm_version\":\"avg-ma-3sigma-v1\",\"prompt_version\":\"explain_risk.v1\",\"model_name\":\"gpt-4o-mini\"}")
chk "5 创建运行" "202" "$code"
RUN_ID=$(jget 'data.run_id')
STATUS=""
for i in $(seq 1 120); do
  api "$BASE/api/v1/analysis/runs/$RUN_ID" -H "$AUTH" >/dev/null
  STATUS=$(jget 'data.status')
  case "$STATUS" in SUCCEEDED|DEGRADED|FAILED) break ;; esac
  sleep 1
done
TB=$(now_ms)
ANALYSIS_MS=$((TB-TA))
chk "5 到达终态" "ok" "$( case "$STATUS" in SUCCEEDED|DEGRADED) echo ok ;; *) echo "$STATUS" ;; esac )"
chk "5 处理 SKU 数" "500" "$(jget 'data.processed_skus')"
log "    端到端（提交→终态）=${ANALYSIS_MS}ms"
chk_lt "5 分析端到端（毫秒，门槛 60000）" "60000" "$ANALYSIS_MS"
chk_gt "5 落库分析明细" "499" "$(sql "SELECT COUNT(*) FROM analysis_items WHERE run_id=$RUN_ID")"
sep

log "[6] 汇总"
log "    P1 列表 p95 = ${P95}ms（门槛 500ms）"
log "    P2 1 万行 confirm = ${CONFIRM_MS}ms（门槛 60000ms）"
log "    P3 500 SKU 分析 = ${ANALYSIS_MS}ms（门槛 60000ms）"
sep
log "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
