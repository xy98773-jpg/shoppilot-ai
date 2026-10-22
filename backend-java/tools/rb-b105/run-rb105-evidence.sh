#!/usr/bin/env bash
# =============================================================================
# B10.5（R78）独立复核真栈脚本 —— 项目负责人侧，不采信交付自报
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器（rb105-mysql），不复用任何 dev 栈。
# 端口 3339 / 18119 —— 避开 3319、3327–3338 与 18099、18107–18118。
#
# 组次（★ = 开发 G1–G3 未覆盖的面）：
#   A  INVENTORY_SNAPSHOT 首导（增量 + INIT_IMPORT 幂等键字面 + 重放）
#   B★ 同批同 SKU【跨仓】——开发申报「按重放处理」，实测其真实后果
#   C★ qty=0（有余额行 / 无余额行）——开发未测
#   D★ 校验阶段拦截（CAT-3003/CAT-3005）+ 漂移对照组（parseDataRow 失败 vs 写服务内部失败）
#   E  SALES_DAILY（首导【无】IMP-4006 的反向面 + 覆盖 + 文件内重复行）
#   F★ 主数据四类未被数据类分支带坏（SKU 导入回归）
#   G★ 行级告警副产物（raw_data 不得写 WARNING 行）+ 租户边界
#
# ★ 应用日志写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4，否则中文断言退化成 '?' 产生假 FAIL。
# ★ curl 必须 --noproxy '*'：本机 Clash 全局代理会劫持 localhost。
# ★ curl -F file=@... 在 Windows 原生 curl 下不认 /d/... ，须 cygpath -w 转盘符。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-rb105.txt"
APP_LOG="$OUT_DIR/evidence-rb105-app.log"
HDR_FILE="$OUT_DIR/evidence-rb105-headers.log"
MYSQL_CT="rb105-mysql"
MYSQL_PORT="${RB105_MYSQL_PORT:-3339}"
APP_PORT="${RB105_APP_PORT:-18119}"
PW="1234"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"
PY=$(command -v python || command -v python3)

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar 不存在（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
log "JAR=$(basename "$JAR")"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  pkill -f "server.port=$APP_PORT" 2>/dev/null || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
}
trap cleanup EXIT

# ---------------------------------------------------------------- 独立 MySQL
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$APP_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT, container $MYSQL_CT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

# ---------------------------------------------------------------- 独立 app
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimeZone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
IMPORT_STORAGE_DIR="$OUT_DIR/tmp/storage" \
SERVER_PORT=$APP_PORT \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT)"

UP=0
for i in $(seq 1 60); do
  c=$(curl -s -o /dev/null -w "%{http_code}" --noproxy '*' "$BASE/api/v1/health" || echo 000)
  [ "$c" = "200" ] && { UP=1; break; }
  sleep 2
done
log "APP_UP=$UP"
[ "$UP" = "1" ] || { log "FATAL app not up"; exit 4; }

MYSQL_FLAGS=(--default-character-set=utf8mb4 -h127.0.0.1 -uroot -proot)
docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot < "$EVI/seed-b95.sql" >> "$APP_LOG" 2>&1 \
  && log "SEED_OK (seed-b95.sql：团队7/仓库9004/SKU-A=9002、SKU-B=9003、SKU-C=9005 各 10.000)"
# 复核专用夹具：队内第二仓库 + 无余额的 SKU（不复用开发种子）
sql_raw(){
  docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" shoppilot -e "$1" 2>/dev/null
}
sql_raw "INSERT IGNORE INTO warehouses (id,team_id,code,name,status) VALUES (9006,7,'WH-B','副仓','ACTIVE');
INSERT IGNORE INTO skus (id,team_id,product_id,sku_code,name,status) VALUES (9007,7,9001,'SKU-D','SKU-D','ACTIVE');" \
  >> "$APP_LOG" 2>&1 && log "FIXTURE_OK (仓库 9006=WH-B；SKU 9007=SKU-D 无余额行)"

FAIL=0; TOTAL=0
chk(){ TOTAL=$((TOTAL+1)); if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); fi; }
note(){ log "NOTE $1 => $2"; }
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
st(){ echo "$1" | sed 's/.*|//'; }
body(){ echo "${1%|*}"; }
jqn(){ local B="$1" K="$2"; echo "$B" | grep -o "\"$K\":[0-9]*" | head -1 | sed 's/.*://'; }
jqs(){ local B="$1" K="$2"; echo "$B" | grep -o "\"$K\":\"[^\"]*\"" | head -1 | sed 's/.*:"//;s/"$//'; }
hdr(){ grep -i "^$1:" "$HDR_FILE" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
pyget(){ "$PY" -c "import sys,json;d=json.load(sys.stdin);print($1)" 2>/dev/null; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$2"; }

# 完整链路：$1=token $2=bizType $3=file $4=label
C_BID=""; C_VAL_HTTP=""; C_VAL_STATUS=""; C_VAL_ERR=""; C_VAL_WARN=""
C_CF_HTTP=""; C_CF_CODE=""; C_CF_STATUS=""; C_CF_SUCC=""; C_CF_FAIL=""
chain(){
  local TK="$1" BT="$2" F="$3" LB="$4" R PV MAP VAL
  C_BID=""; C_VAL_HTTP=""; C_VAL_STATUS=""; C_VAL_ERR=""; C_VAL_WARN=""
  C_CF_HTTP=""; C_CF_CODE=""; C_CF_STATUS=""; C_CF_SUCC=""; C_CF_FAIL=""
  R=$(upload "$TK" "$BT" "$F"); local S=$(st "$R")
  log "$LB upload http=$S bizType=$BT body=$(body "$R" | head -c 240)"
  [ "$S" != "201" ] && { log "$LB upload FAILED body=$(body "$R" | head -c 400)"; return 1; }
  C_BID=$(jqn "$(body "$R")" batch_id)
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $TK" \
    "$BASE/api/v1/import/batches/$C_BID/preview?limit=5")
  PV=$(body "$R")
  log "$LB preview http=$(st "$R") status=$(jqs "$PV" status) rows=$(jqn "$PV" total_rows)"
  MAP=$(echo "$PV" | pyget "json.dumps(d['data']['suggested_mapping'],ensure_ascii=False)")
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$C_BID/mapping" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' \
    -d "{\"field_mapping_json\":$MAP}")
  log "$LB mapping http=$(st "$R") status=$(jqs "$(body "$R")" status)"
  R=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$C_BID/validate" \
    -H "Authorization: Bearer $TK")
  VAL=$(body "$R")
  C_VAL_HTTP=$(st "$R")
  C_VAL_STATUS=$(jqs "$VAL" status); C_VAL_ERR=$(jqn "$VAL" error_rows); C_VAL_WARN=$(jqn "$VAL" warning_rows)
  log "$LB validate http=$(st "$R") status=$C_VAL_STATUS error_rows=$C_VAL_ERR warning_rows=$C_VAL_WARN"
  if [ "${SKIP_CONFIRM:-0}" = "1" ]; then
    SKIP_CONFIRM=0
    log "$LB 校验后暂不确认（漂移实验）batch=$C_BID"
    return 0
  fi
  R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$C_BID/confirm" \
    -H "Authorization: Bearer $TK" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: rb105-$LB-$$" -d '{}')
  local CB=$(body "$R")
  C_CF_HTTP=$(st "$R"); C_CF_CODE=$(code "$CB")
  C_CF_STATUS=$(jqs "$CB" status); C_CF_SUCC=$(jqn "$CB" success_rows); C_CF_FAIL=$(jqn "$CB" failed_rows)
  log "$LB confirm http=$C_CF_HTTP code=$C_CF_CODE status=$C_CF_STATUS success=$C_CF_SUCC failed=$C_CF_FAIL"
  return 0
}

TOK_OWNER=$(token owner01); TOK_OPER=$(token operator01)
chk "A0 owner/operator 可登录" "ok" \
  "$([ ${#TOK_OWNER} -gt 20 ] && [ ${#TOK_OPER} -gt 20 ] && echo ok || echo bad)"

TMPD="$OUT_DIR/tmp"; mkdir -p "$TMPD/storage"
TMPD_WIN=$(cygpath -w "$TMPD" 2>/dev/null | sed 's#\\#/#g' || echo "$TMPD")
log "TMPD_WIN=$TMPD_WIN"

# ---------------------------------------------------------------- 夹具 CSV
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-A,WH-MAIN,120.000,2026-10-01,首导\nSKU-B,WH-MAIN,30.000,2026-10-01,首导\n' > "$TMPD/snap-a.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-C,WH-MAIN,5.000,2026-10-02,主仓\nSKU-C,WH-B,7.000,2026-10-02,副仓\n' > "$TMPD/snap-cross.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-A,WH-MAIN,0.000,2026-10-03,零快照\n' > "$TMPD/snap-zero.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-D,WH-MAIN,0.000,2026-10-03,零快照新SKU\n' > "$TMPD/snap-zero-new.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-A,WH-MAIN,1.000,2026-10-04,合法\nSKU-A,WH-NOPE,2.000,2026-10-04,未知仓库\n' > "$TMPD/snap-badwh.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-OTHER,WH-MAIN,3.000,2026-10-04,跨团队\n' > "$TMPD/snap-crossteam.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-A,WH-MAIN,10.000,2026-10-05,合法\nSKU-ZZZ,WH-MAIN,1.000,2026-10-05,未知SKU\n' > "$TMPD/snap-mixed.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-A,WH-MAIN,2026-10-05,12.000,1.000,1080.00,8\nSKU-B,WH-MAIN,2026-10-06,5.500,0.000,495.00,4\n' > "$TMPD/sales-a.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-A,WH-MAIN,2026-10-05,20.500,1.000,1800.00,9\nSKU-B,WH-MAIN,2026-10-06,7.000,0.000,600.00,5\n' > "$TMPD/sales-overwrite.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-C,WH-MAIN,2026-10-07,11.000,0.000,100.00,1\nSKU-C,WH-MAIN,2026-10-07,15.000,0.000,200.00,2\n' > "$TMPD/sales-dup.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-C,WH-B,9.000,2026-10-02,仅副仓\n' > "$TMPD/snap-whb.csv"
printf 'sku_code,warehouse_code,qty,snapshot_date,remark\nSKU-C,WH-MAIN,4.000,2026-10-08,合法\nSKU-D,WH-MAIN,6.000,2026-10-08,将漂移\n' > "$TMPD/snap-drift.csv"
printf 'sku_code,warehouse_code,sale_date,qty,return_qty,sales_amount,order_count\nSKU-B,WH-MAIN,2026-10-09,3.000,0.000,300.00,2\n' > "$TMPD/sales-drift.csv"
printf 'sku_code,product_code,name\nSKU-RB1,P-BAG-001,复核新增SKU\n' > "$TMPD/sku-master.csv"

sep
log "=== 基线快照（seed 后） ==="
log "BASE balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7") sales=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
log "BASE SKU-A=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002") SKU-C=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9005")"

# =============================================================== A 快照首导
sep
log "=== A INVENTORY_SNAPSHOT 首导（增量 + 幂等键字面 + 重放） ==="
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-a.csv" "A1"
chk "A1 校验通过(VALIDATED)" "VALIDATED" "$C_VAL_STATUS"
chk "A1 无 ERROR 行" "0" "$C_VAL_ERR"
chk "A1 两行均命中已有余额 → IMP-4006 WARNING=2" "2" "$C_VAL_WARN"
chk "A1 IMP-4006 落库条数" "2" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$C_BID AND error_code='IMP-4006'")"
chk "A1 IMP-4006 级别为 WARNING" "2" "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE batch_id=$C_BID AND error_level='WARNING'")"
chk "A1 confirm http" "200" "$C_CF_HTTP"
chk "A1 终态 COMPLETED" "COMPLETED" "$C_CF_STATUS"
chk "A1 success_rows=2" "2" "$C_CF_SUCC"
chk "A1 余额 SKU-A 增量 10+120=130" "130.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "A1 余额 SKU-B 增量 10+30=40" "40.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")"
chk "A1 INIT_IMPORT 流水 2 条" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$C_BID AND txn_type='INIT_IMPORT'")"
chk "A1 流水 biz_type=IMPORT" "2" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$C_BID AND biz_type='IMPORT'")"
chk "A1 幂等键字面 INIT_IMPORT:IMPORT:<bid>:9002" "1" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$C_BID:9002'")"
chk "A1 qty_before 记录导入前余额" "10.000" \
  "$(sql "SELECT qty_before FROM inventory_transactions WHERE team_id=7 AND idempotency_key='INIT_IMPORT:IMPORT:$C_BID:9002'")"
chk "A1 last_txn_id 回填（经写服务，非直写）" "1" \
  "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002 AND last_txn_id IS NOT NULL")"
BID_A=$C_BID
R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST \
  "$BASE/api/v1/import/batches/$BID_A/confirm" \
  -H "Authorization: Bearer $TOK_OPER" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: rb105-A1-replay-$$" -d '{}')
chk "A1 二次 confirm http" "200" "$(st "$R")"
chk "A1 二次 confirm 为重放（Idempotent-Replayed）" "true" "$(hdr 'Idempotent-Replayed')"
chk "A1 重放后余额仍 130.000" "130.000" \
  "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "A1 重放后该批次 INIT_IMPORT 仍 2 条" "2" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$BID_A AND txn_type='INIT_IMPORT'")"

# =============================================================== B 跨仓（关键）
sep
log "=== B ★ 同批同 SKU 跨仓（开发申报：第二行「按重放处理，不报错」） ==="
log "B0 前置：SKU-C@WH-MAIN=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9005") / SKU-C@WH-B 余额行=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND warehouse_id=9006 AND sku_id=9005")"
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-cross.csv" "B1"
chk "B1 校验通过(VALIDATED)" "VALIDATED" "$C_VAL_STATUS"
chk "B1 只有主仓那行命中已有余额 → WARNING=1" "1" "$C_VAL_WARN"
chk "B1 confirm http" "200" "$C_CF_HTTP"
log "B1 ★ 实测终态=$C_CF_STATUS success=$C_CF_SUCC failed=$C_CF_FAIL"
chk "B1 两行均成功（合法业务行不得被判重放）" "2:0" "$C_CF_SUCC:$C_CF_FAIL"
chk "B1 主仓 SKU-C 10+5=15.000" "15.000" \
  "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9005")"
chk "B1 副仓 SKU-C 应建行并=7.000" "7.000" \
  "$(sql "SELECT IFNULL((SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9006 AND sku_id=9005),'NO_ROW')")"
chk "B1 该批次 INIT_IMPORT 流水应 2 条" "2" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$C_BID AND txn_type='INIT_IMPORT'")"
ERRCODE_B=$(sql "SELECT GROUP_CONCAT(DISTINCT error_code) FROM import_row_errors WHERE batch_id=$C_BID AND error_level='ERROR'")
log "B1 行级错误码=$ERRCODE_B"
if [ "$C_CF_SUCC" != "2" ]; then
  note "B1 【缺陷 R80】同批同 SKU 跨仓的第二行命中同一幂等键（终态=$C_CF_STATUS，行级码=$ERRCODE_B）" \
       "initImportIdempotencyKey 按 §5.10 字面 <txn_type>:<biz_type>:<biz_ref_id>:<sku_id> 不含仓库 → 第二行同键；replayIfIdempotent 的 sameContent 比 warehouse_id → 判「内容不一致」抛 INV-5003。SSOT §10.4 的键格式自带 [:<suffix>] 后缀位，追加 warehouse_id 即可"
  note "B1 【缺陷 R79】该 INV-5003 未落成「行级失败 + PARTIAL_SUCCESS」，而炸成整批 FAILED + 全行 COM-0002" \
       "开发申报为「按重放处理、不重复累加、也不报错」，实测更糟：两行全失败、0 条流水、余额未变、批次 FAILED、错误码是 500 级的 COM-0002（未捕获的 UnexpectedRollbackException）。即便 R80 修好，任何由写服务内部抛出的行级业务异常仍会走同一路径（见 D4）"
else
  note "B1 同批同 SKU 跨仓两行均成功" "副仓余额=7.000；若开发所称「按重放处理」成立则应只 1 条流水"
fi
chk "B1 副仓流水是否存在（与余额行一致）" \
  "$(sql "SELECT IFNULL((SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND warehouse_id=9006 AND sku_id=9005),0)")" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$C_BID AND sku_id=9005 AND warehouse_id=9006")"

# ---- B2 隔离实验：同一 SKU 单独导入副仓（无键碰撞）应正常成功 ----
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-whb.csv" "B2"
chk "B2 副仓单行 confirm http" "200" "$C_CF_HTTP"
chk "B2 副仓单行终态 COMPLETED（无碰撞即可成功）" "COMPLETED" "$C_CF_STATUS"
chk "B2 副仓余额行被创建并=9.000" "9.000" \
  "$(sql "SELECT IFNULL((SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9006 AND sku_id=9005),'NO_ROW')")"
note "B2 因果隔离" "单独导入 WH-B 一行完全成功 ⇒ 唯一变量就是「同批次内同 SKU 第二行命中同键」，与仓库/框架无关"

# =============================================================== C qty=0
sep
log "=== C ★ qty=0（有余额行 / 无余额行） ==="
TX_BEFORE=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-zero.csv" "C1"
chk "C1 qty=0 校验通过(VALIDATED)" "VALIDATED" "$C_VAL_STATUS"
chk "C1 qty=0 计入 IMP-4006（已有余额行）" "1" "$C_VAL_WARN"
chk "C1 confirm http" "200" "$C_CF_HTTP"
chk "C1 qty=0 计为成功行" "1" "$C_CF_SUCC"
chk "C1 qty=0 不动余额" "130.000" \
  "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"
chk "C1 qty=0 不写流水" "0" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND biz_ref_id=$C_BID")"
chk "C1 全局流水条数不变" "$TX_BEFORE" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-zero-new.csv" "C2"
chk "C2 无余额 SKU 的 qty=0 也成功" "1" "$C_CF_SUCC"
chk "C2 但未建余额行（0 快照不留痕）" "0" \
  "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9007")"
note "C2 qty=0 口径" "SKU-D 无余额行 → 导入 0 后仍无行。后续出库将报 INV-5004（余额行不存在）而不是「余额 0 不足」；如需建 0 行须另立口径"

# =============================================================== D 引用错误
sep
log "=== D 引用错误（校验阶段拦截）与漂移（确认阶段行级失败） ==="
# ---- D1 未知仓库：校验阶段即拦 ----
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-badwh.csv" "D1"
chk "D1 未知仓库在校验阶段即被拦（error_rows=1）" "1" "$C_VAL_ERR"
chk "D1 错误码 CAT-3003" "CAT-3003" \
  "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$C_BID AND error_level='ERROR' LIMIT 1")"
chk "D1 有 ERROR 行 → confirm 被拒 422 IMP-4009" "422:IMP-4009" "$C_CF_HTTP:$C_CF_CODE"
chk "D1 被拒后余额未变" "130.000" \
  "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9002")"

# ---- D2 跨团队 SKU：校验阶段即拦 ----
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-crossteam.csv" "D2"
chk "D2 跨团队 SKU 在校验阶段即被拦" "1" "$C_VAL_ERR"
chk "D2 校验阶段错误码（现网口径 CAT-3005）" "CAT-3005" \
  "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$C_BID AND error_level='ERROR' LIMIT 1")"
chk "D2 全行失败 → 校验直接 422 FAILED（IMP-4010）" "422:FAILED" "$C_VAL_HTTP:$C_VAL_STATUS"
chk "D2 对 FAILED 批次 confirm 走重放分支（200 + 仍是 FAILED）" "200:FAILED:0" "$C_CF_HTTP:$C_CF_STATUS:$C_CF_FAIL"
chk "D2 未污染团队 8 的余额" "0" "$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=8")"
note "D2 同一情形两种错误码" \
     "跨团队 SKU 在 validate 落 CAT-3005（ImportValidationService:509 既有分支，403「关联对象不属于同一团队」），在 confirm 漂移路径落 CAT-3001（B10.5 新增）。IMPORT_WORKFLOW §7.5/§7.6 只列 CAT-3001 —— 建议后续统一，语义两者都成立，不阻塞"

# ---- D3 ★ 对照组：漂移在 parseDataRow 阶段失败 → 行级隔离应生效 ----
SKIP_CONFIRM=1
chain "$TOK_OPER" "INVENTORY_SNAPSHOT" "$TMPD_WIN/snap-drift.csv" "D3"
SKIP_CONFIRM=0
chk "D3 校验通过（两行 SKU 均存在）" "VALIDATED" "$C_VAL_STATUS"
chk "D3 校验无 ERROR" "0" "$C_VAL_ERR"
BID_D3=$C_BID
BALC_BEFORE=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9005")
sql_raw "DELETE FROM skus WHERE id=9007" >/dev/null 2>&1
log "D3 漂移：已删除 SKU 9007（校验后、确认前）"
R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST \
  "$BASE/api/v1/import/batches/$BID_D3/confirm" \
  -H "Authorization: Bearer $TOK_OPER" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: rb105-D3-$$" -d '{}')
CB=$(body "$R")
C_CF_HTTP=$(st "$R"); C_CF_CODE=$(code "$CB")
C_CF_STATUS=$(jqs "$CB" status); C_CF_SUCC=$(jqn "$CB" success_rows); C_CF_FAIL=$(jqn "$CB" failed_rows)
log "D3 confirm http=$C_CF_HTTP code=$C_CF_CODE status=$C_CF_STATUS success=$C_CF_SUCC failed=$C_CF_FAIL"
chk "D3 漂移后 confirm 仍 200（行级失败不阻断整批）" "200" "$C_CF_HTTP"
chk "D3 终态 PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$C_CF_STATUS"
chk "D3 成功/失败 = 1/1" "1:1" "$C_CF_SUCC:$C_CF_FAIL"
chk "D3 漂移行错误码 CAT-3001" "CAT-3001" \
  "$(sql "SELECT error_code FROM import_row_errors WHERE batch_id=$BID_D3 AND error_level='ERROR' LIMIT 1")"
chk "D3 合法行已落库（+4.000）" "1" \
  "$(sql "SELECT COUNT(*)=1 FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9005 AND on_hand_qty = $BALC_BEFORE + 4.000")"

# ---- D4 ★ 写服务内部抛错（SALES_DAILY 漂移上架日）→ 是否仍能行级隔离 ----
SKIP_CONFIRM=1
chain "$TOK_OPER" "SALES_DAILY" "$TMPD_WIN/sales-drift.csv" "D4"
SKIP_CONFIRM=0
chk "D4 校验通过（上架日尚未改）" "VALIDATED" "$C_VAL_STATUS"
BID_D4=$C_BID
sql_raw "UPDATE skus SET listed_date='2026-10-20' WHERE id=9003" >/dev/null 2>&1
log "D4 漂移：SKU-B 上架日改为 2026-10-20（晚于 sale_date 2026-10-09）→ batchUpsert 必抛 SAL-6003"
R=$(curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST \
  "$BASE/api/v1/import/batches/$BID_D4/confirm" \
  -H "Authorization: Bearer $TOK_OPER" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: rb105-D4-$$" -d '{}')
CB=$(body "$R")
C_CF_HTTP=$(st "$R"); C_CF_CODE=$(code "$CB")
C_CF_STATUS=$(jqs "$CB" status); C_CF_SUCC=$(jqn "$CB" success_rows); C_CF_FAIL=$(jqn "$CB" failed_rows)
log "D4 confirm http=$C_CF_HTTP code=$C_CF_CODE status=$C_CF_STATUS success=$C_CF_SUCC failed=$C_CF_FAIL"
ERRCODE_D4=$(sql "SELECT GROUP_CONCAT(DISTINCT error_code) FROM import_row_errors WHERE batch_id=$BID_D4 AND error_level='ERROR'")
log "D4 行级错误码=$ERRCODE_D4"
chk "D4 写服务内部失败应落 PARTIAL_SUCCESS（裁定 C1 行级隔离）" "PARTIAL_SUCCESS" "$C_CF_STATUS"
chk "D4 该行应记 SAL-6003（而非 COM-0002）" "SAL-6003" "$ERRCODE_D4"
sql_raw "UPDATE skus SET listed_date=NULL WHERE id=9003" >/dev/null 2>&1
note "D4 ★ 缺陷 R79：写服务内部 BizException 把外层事务标 rollback-only" \
     "applyInitialImport / SalesWriteService.batchUpsert 均为 @Transactional(REQUIRED)，被 importdata 外层 TransactionTemplate 包住时一旦抛 BizException，Spring 默认 globalRollbackOnParticipationFailure 会把共享事务标成 rollback-only → 提交时 UnexpectedRollbackException → C2 整批回滚 + 全行记 COM-0002。主数据 Pass 2 用的是 @Transactional(NESTED) 单行入口（CatalogWriteService:826），故不受影响"

# =============================================================== E 销售
sep
log "=== E SALES_DAILY（首导无 WARNING 的反向面 + 覆盖 + 文件内重复行） ==="
chain "$TOK_OPER" "SALES_DAILY" "$TMPD_WIN/sales-a.csv" "E1"
chk "E1 校验通过(VALIDATED)" "VALIDATED" "$C_VAL_STATUS"
chk "E1 首导不产生 IMP-4006（库内无同键）" "0" "$C_VAL_WARN"
chk "E1 confirm http" "200" "$C_CF_HTTP"
chk "E1 终态 COMPLETED" "COMPLETED" "$C_CF_STATUS"
BID_E1=$C_BID
chk "E1 sales_daily 行数=2" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
chk "E1 source=IMPORT" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND source='IMPORT'")"
chk "E1 batch_id 回指该批次" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND batch_id=$BID_E1")"
chk "E1 product_id 派生=9001" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND product_id=9001")"
chk "E1 qty 原值 12.000" "12.000" "$(sql "SELECT qty FROM sales_daily WHERE team_id=7 AND sku_id=9002")"
chk "E1 审计 SALES_DAILY_UPSERT 1 条（每批一次，不是每行）" "1" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action='SALES_DAILY_UPSERT'")"

chain "$TOK_OPER" "SALES_DAILY" "$TMPD_WIN/sales-overwrite.csv" "E2"
chk "E2 覆盖导入产生 IMP-4006 WARNING=2" "2" "$C_VAL_WARN"
chk "E2 confirm http" "200" "$C_CF_HTTP"
chk "E2 行数仍 2（覆盖而非累加）" "2" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
chk "E2 qty 被覆盖为 20.500（非 12+20.5）" "20.500" "$(sql "SELECT qty FROM sales_daily WHERE team_id=7 AND sku_id=9002")"
chk "E2 batch_id 更新为新批次" "$C_BID" "$(sql "SELECT batch_id FROM sales_daily WHERE team_id=7 AND sku_id=9002")"
chk "E2 审计共 2 条" "2" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action='SALES_DAILY_UPSERT'")"
chk "E2 第二条 change_summary 含「覆盖 2 行 / 共 2 行」" "1" \
  "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND action='SALES_DAILY_UPSERT' AND change_summary LIKE '%覆盖 2 行 / 共 2 行%'")"

chain "$TOK_OPER" "SALES_DAILY" "$TMPD_WIN/sales-dup.csv" "E3"
chk "E3 文件内重复业务键在校验阶段被拦（error_rows=1）" "1" "$C_VAL_ERR"
chk "E3 confirm 被拒 422 IMP-4009" "422:IMP-4009" "$C_CF_HTTP:$C_CF_CODE"
chk "E3 被拒后未落任何该 SKU 销售行" "0" \
  "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7 AND sku_id=9005")"
log "E3 重复行错误码=$(sql "SELECT GROUP_CONCAT(DISTINCT error_code) FROM import_row_errors WHERE batch_id=$C_BID AND error_level='ERROR'")"
note "E3 文件内重复行的处理" "同 SKU+仓库+日期 在文件内出现两次 → 校验阶段直接记 ERROR（不静默取后者），confirm 被 422 拦；比『后者覆盖』更安全，口径成立"

# =============================================================== F 主数据回归
sep
log "=== F ★ 主数据四类未被数据类分支带坏（SKU 导入回归） ==="
SKU_BEFORE=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
chain "$TOK_OPER" "SKU" "$TMPD_WIN/sku-master.csv" "F1"
chk "F1 SKU 导入校验通过" "VALIDATED" "$C_VAL_STATUS"
chk "F1 confirm http" "200" "$C_CF_HTTP"
chk "F1 终态 COMPLETED" "COMPLETED" "$C_CF_STATUS"
chk "F1 skus 行数 +1" "$((SKU_BEFORE+1))" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "F1 新 SKU 落库" "1" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7 AND sku_code='SKU-RB1'")"
chk "F1 模板仍为六类" "6" \
  "$(curl -s --noproxy '*' -H "Authorization: Bearer $TOK_OPER" "$BASE/api/v1/import/templates" | pyget "len(d['data'])")"

# =============================================================== G 副产物与租户
sep
log "=== G ★ 告警副产物 + 租户边界 ==="
chk "G1 WARNING 行不写 raw_data（raw_data 仅 ERROR 行）" "0" \
  "$(sql "SELECT COUNT(*) FROM import_row_errors WHERE error_level='WARNING' AND raw_data IS NOT NULL")"
chk "G2 全部写入行属团队 7" "0" \
  "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id<>7 AND biz_type='IMPORT'")"
chk "G3 团队 8 无任何 sales_daily（未被导入污染）" "0" \
  "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=8")"
chk "G4 幂等键无重复（uk_invtxn_team_idem 生效）" "0" \
  "$(sql "SELECT COUNT(*) FROM (SELECT team_id,idempotency_key FROM inventory_transactions GROUP BY team_id,idempotency_key HAVING COUNT(*)>1) t")"
log "G5 数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7") sales=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7") batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
log "G6 批次终态分布：$(sql "SELECT GROUP_CONCAT(CONCAT(status,':',c)) FROM (SELECT status,COUNT(*) c FROM import_batches WHERE team_id=7 GROUP BY status) t")"

sep
log "RB105_CHECK_TOTAL=$TOTAL"
log "RB105_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "RB105_RESULT=ALL PASS"; else log "RB105_RESULT=HAS_FAIL"; fi
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
