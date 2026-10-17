#!/usr/bin/env bash
# =============================================================================
# B10（M3-T18 前端导入向导 + 库存/销售页）逐页联调取证脚本
#
# 前置（脚本不负责起服务，避免与其它批次端口打架）：
#   1) 后端：isolated compose 项目（同一份 deploy/docker-compose.dev.yml）
#        MYSQL_PORT=3336 BACKEND_PORT=8082 \
#        docker compose -p shoppilot-b10 -f deploy/docker-compose.dev.yml up -d mysql backend-java
#   2) 前端：frontend-web 下 `npm run dev`（5173），代理指向 8082：
#        VITE_PROXY_TARGET=http://localhost:8082 npm run dev
#
# 用法：bash frontend-web/tools/b10/run-b10-evidence.sh
# 产物：frontend-web/tools/b10/evidence-b10.txt          ← 断言日志（入库）
#       frontend-web/tools/b10/evidence-b10-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#       frontend-web/tools/b10/sku-master.csv            ← 联调用导入文件（入库，便于复现）
#
# 覆盖（对应任务书【5】第 4 条）：
#   P1–P6  六个页面：SPA 路由可打开 + 该页真实调用的后端端点（经前端 dev 代理）返回 200
#   W1 导入确认同键重试 → 第二次 `Idempotent-Replayed: true`，skus 不新增
#   W2 盘点调整同键重试 → 第二次同 transaction_id + 重放头，余额不再二次变动
#   W3 冲销同键重试 → 第二次 200 + 重放头，REVERSAL 只有 1 行
#
# ★★ 为什么导入用 SKU 而不是 SALES_DAILY（本批禁改 Java，只能如实换口径）：
#   `ImportConfirmService.SUPPORTED_BIZ_TYPES` 只含 PRODUCT/SKU/SUPPLIER/WAREHOUSE
#   （该文件第 139 行的既有事实），`INVENTORY_SNAPSHOT`/`SALES_DAILY` 走确认必返
#   400 COM-0001（details[field=bizType]）。本批纯前端、不得改 Java，故：
#     · 导入向导 → 用 SKU 主数据批次，全链路（上传/预览/映射/校验/确认）均真实成功；
#     · /sales 页数据 → 用后端已验证通路 `POST /api/v1/sales/daily`（B8 的手工写入）播种。
#   该后端限制已在交付汇报「偏差申报」中列明，未做任何前端规避性假数据。
#
# ★ curl 必须 --noproxy '*'（本机全局 http 代理会劫持 localhost）
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
API="${B10_API:-http://localhost:8082}"
WEB="${B10_WEB:-http://localhost:5173}"
MYSQL_CT="${B10_MYSQL_CT:-shoppilot-b10-mysql-1}"
# ★ 用 compose 里的业务账号（root 口令由 MYSQL_ROOT_PASSWORD 决定，不可假定；
#   业务账号对 shoppilot 库有全部权限，种子的 DML 足够）
MYSQL_USER="${B10_MYSQL_USER:-shoppilot}"
MYSQL_PASSWORD="${B10_MYSQL_PASSWORD:-changeme_app_password}"
PW="S3cret!Passw0rd"
LOG="$OUT_DIR/evidence-b10.txt"
HDR="$OUT_DIR/evidence-b10-headers.log"
CSV="$OUT_DIR/sku-master.csv"

: > "$LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}

sql(){ docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -N -B shoppilot -e "$1" 2>/dev/null; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"$//"; }
hdr(){ grep -i "^$1:" "$HDR" 2>/dev/null | head -1 | tr -d '\r' | sed 's/^[^:]*: *//'; }
token(){ curl -s --noproxy '*' -X POST "$API/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$API$2"; }
postj(){ # bearer path idemKey json -> body|http（响应头落 $HDR）
  curl -s --noproxy '*' -D "$HDR" -w "|%{http_code}" -X POST "$API$2" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "$4"
}
webcode(){ curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$WEB$1"; }
webproxy(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$WEB$2"; }

log "API=$API"
log "WEB=$WEB"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

# ---------------------------------------------------------------- 前置探活
HC=$(curl -s --noproxy '*' -o /dev/null -w "%{http_code}" "$API/api/v1/health" || echo 000)
log "BACKEND_HEALTH_HTTP=$HC"
[ "$HC" = "200" ] || { log "FATAL: backend not healthy at $API"; exit 2; }
WHC=$(webcode "/")
log "FRONTEND_DEV_HTTP=$WHC"
[ "$WHC" = "200" ] || { log "FATAL: vite dev server not up at $WEB"; exit 3; }

# ---------------------------------------------------------------- 种子（隔离库）
# ★ seed-b95.sql 不含导入/销售表：本批联调会产生这两类数据，若不清会造成
#   ① 团队删除被 FK(RESTRICT) 挡住 ② 重复文件哈希撞 IMP-4004。故先按 FK 安全顺序清理。
sql "DELETE FROM import_row_errors" >/dev/null 2>&1 || true
sql "DELETE FROM import_batches" >/dev/null 2>&1 || true
sql "DELETE FROM sales_daily" >/dev/null 2>&1 || true
docker exec -i "$MYSQL_CT" mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" shoppilot \
  < "$REPO/backend-java/tools/evi/seed-b95.sql" >/dev/null 2>&1 \
  && log "SEED_OK (backend-java/tools/evi/seed-b95.sql)" || { log "FATAL: seed failed"; exit 4; }

OWN=$(token owner01); OP=$(token operator01); VIEW=$(token viewer01)
log "TOKENS own=${OWN:0:8} op=${OP:0:8} view=${VIEW:0:8}"
[ -n "$OWN" ] || { log "FATAL: login failed"; exit 5; }

# ============================================================== P1 导入向导
sep; log "P1 /importdata/wizard：模板端点 + 上传→预览→映射→校验（经前端 dev 代理）"
chk "P1-路由200" "200" "$(webcode /importdata/wizard)"
R=$(webproxy "$OP" "/api/v1/import/templates")
log "P1-api=$(st "$R") templates=$(echo "$(body "$R")" | grep -o '"biz_type":"[^"]*"' | wc -l | tr -d ' ')"
chk "P1-模板api200" "200" "$(st "$R")"
chk "P1-模板含SKU" "1" "$(echo "$(body "$R")" | grep -c '"biz_type":"SKU"')"

# 联调用导入文件：两个【新】SKU 编码（挂到种子已有的 P-BAG-001 下），
# 这样「首次确认新增 2 行 / 重试不新增」才有可断言的增量。
cat > "$CSV" <<'CSVEOF'
SKU编码,所属商品编码,展示名
SKU-B10-1,P-BAG-001,B10 联调 SKU 一
SKU-B10-2,P-BAG-001,B10 联调 SKU 二
CSVEOF
log "CSV=$(cat "$CSV" | tr '\n' ' ')"
# ★ 必须转成 Windows 路径：curl 是原生 Windows 程序，读不懂 Git Bash 的 /d/... 形式
#   （否则 `-F file=@` 打不开文件，curl 直接 exit 26 → HTTP 000）。
CSV_WIN="$(cd "$(dirname "$CSV")" && pwd -W)/$(basename "$CSV")"
log "CSV_WIN=$CSV_WIN"

# 步骤 1 上传（multipart）
SKU0=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
UP=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$API/api/v1/import/batches" \
  -H "Authorization: Bearer $OP" -F "file=@$CSV_WIN;type=text/csv" -F "bizType=SKU")
BATCH=$(jqn "$(body "$UP")" batch_id)
log "P1-upload=$(st "$UP") batch=$BATCH status=$(jstr "$(body "$UP")" status)"
chk "P1-上传201" "201" "$(st "$UP")"

# 步骤 2 预览（首次触发解析）
PV=$(getq "$OP" "/api/v1/import/batches/$BATCH/preview?limit=20")
log "P1-preview=$(st "$PV") status=$(jstr "$(body "$PV")" status) rows=$(jqn "$(body "$PV")" total_rows)"
chk "P1-预览200" "200" "$(st "$PV")"
chk "P1-预览解析2行" "2" "$(jqn "$(body "$PV")" total_rows)"

# 步骤 3 映射
MAP=$(curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$API/api/v1/import/batches/$BATCH/mapping" \
  -H "Authorization: Bearer $OP" -H 'Content-Type: application/json' -d '{
    "field_mapping_json": {
      "sku_code": "SKU编码", "product_code": "所属商品编码", "name": "展示名"
    }
  }')
log "P1-mapping=$(st "$MAP") status=$(jstr "$(body "$MAP")" status)"
chk "P1-映射200" "200" "$(st "$MAP")"

# 步骤 4 校验
VAL=$(curl -s --noproxy '*' -w "|%{http_code}" -X POST "$API/api/v1/import/batches/$BATCH/validate" \
  -H "Authorization: Bearer $OP")
log "P1-validate=$(st "$VAL") body=$(body "$VAL")"
chk "P1-校验200" "200" "$(st "$VAL")"
chk "P1-校验无错误行" "0" "$(jqn "$(body "$VAL")" error_rows)"

# ============================================================== P2 导入批次
sep; log "P2 /importdata/batches：批次列表 + 详情 + 错误分页 + 错误导出"
chk "P2-路由200" "200" "$(webcode /importdata/batches)"
R=$(webproxy "$OP" "/api/v1/import/batches?page=1&pageSize=10")
log "P2-api=$(st "$R") body=$(body "$R")"
chk "P2-列表api200" "200" "$(st "$R")"
chk "P2-分页字段list" "1" "$(echo "$(body "$R")" | grep -c '"list"')"
chk "P2-列表含本批次" "1" "$(echo "$(body "$R")" | grep -c "\"batch_id\":$BATCH")"

R=$(webproxy "$OP" "/api/v1/import/batches/$BATCH")
log "P2-详情=$(st "$R")"
chk "P2-详情api200" "200" "$(st "$R")"

R=$(webproxy "$OP" "/api/v1/import/batches/$BATCH/errors?page=1&pageSize=20")
log "P2-错误分页=$(st "$R") body=$(body "$R")"
chk "P2-错误api200" "200" "$(st "$R")"
chk "P2-错误分页字段list" "1" "$(echo "$(body "$R")" | grep -c '"list"')"

# 错误导出（文件流，非 ApiResponse 包络 → 断言 200 + text/csv + BOM）
EXH="$OUT_DIR/evidence-b10-export.csv"
EXC=$(curl -s --noproxy '*' -o "$EXH" -w "%{http_code}" -D /dev/null \
  -H "Authorization: Bearer $OP" "$API/api/v1/import/batches/$BATCH/errors/export?errorLevel=ERROR")
log "P2-错误导出=http=$EXC bytes=$(wc -c < "$EXH" | tr -d ' ') bom=$(head -c 3 "$EXH" | od -An -tx1 | tr -d ' \n')"
chk "P2-错误导出200" "200" "$EXC"
chk "P2-导出UTF8_BOM" "efbbbf" "$(head -c 3 "$EXH" | od -An -tx1 | tr -d ' \n')"
rm -f "$EXH"

# ============================================================== P3 库存概览
sep; log "P3 /inventory/overview：SPA 路由 + 余额/预警端点"
chk "P3-路由200" "200" "$(webcode /inventory/overview)"
R=$(webproxy "$OWN" "/api/v1/inventory/balances?page=1&pageSize=10")
log "P3-balances=$(st "$R") sample=$(body "$R")"
chk "P3-余额api200" "200" "$(st "$R")"
chk "P3-四列齐全" "4" "$(echo "$(body "$R")" | grep -o -E '"(on_hand_qty|in_transit_qty|committed_qty|available_qty)":' | sort -u | wc -l | tr -d ' ')"
R=$(webproxy "$OWN" "/api/v1/inventory/alerts?page=1&pageSize=10")
chk "P3-预警api200" "200" "$(st "$R")"

# ============================================================== P4 库存流水
sep; log "P4 /inventory/transactions：SPA 路由 + 流水列表端点"
chk "P4-路由200" "200" "$(webcode /inventory/transactions)"
R=$(webproxy "$OWN" "/api/v1/inventory/transactions?page=1&pageSize=10")
log "P4-api=$(st "$R") body=$(body "$R")"
chk "P4-流水api200" "200" "$(st "$R")"

# ============================================================== P5 盘点调整页
sep; log "P5 /inventory/count-adjust：SPA 路由（写路径在 W2 验证）"
chk "P5-路由200" "200" "$(webcode /inventory/count-adjust)"

# ============================================================== P6 销售列表
sep; log "P6 /sales：先经 POST /sales/daily 播种 2 行，再验证只读列表端点与筛选"
chk "P6-路由200" "200" "$(webcode /sales)"

S0=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")
INS() { # sku date qty ret amount orders
  curl -s --noproxy '*' -o /dev/null -w "%{http_code}" -X POST "$API/api/v1/sales/daily" \
    -H "Authorization: Bearer $OWN" -H 'Content-Type: application/json' -d "{
      \"sku_id\":$1,\"warehouse_id\":9004,\"sale_date\":\"$2\",
      \"qty\":\"$3\",\"return_qty\":\"$4\",\"sales_amount\":\"$5\",\"order_count\":$6}"
}
HC1=$(INS 9002 2026-10-15 12.000 1.000 1080.00 8)
HC2=$(INS 9003 2026-10-16 5.500 0.000 495.00 4)
log "P6-播种 http=$HC1/$HC2 rows=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"
chk "P6-播种1_201" "201" "$HC1"
chk "P6-播种2_201" "201" "$HC2"
chk "P6-落库2行" "$((S0 + 2))" "$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7")"

R=$(webproxy "$OWN" "/api/v1/sales/daily?page=1&pageSize=10")
log "P6-sales=$(st "$R") body=$(body "$R")"
chk "P6-销售api200" "200" "$(st "$R")"
chk "P6-分页字段list" "1" "$(echo "$(body "$R")" | grep -c '"list"')"
chk "P6-列表2行" "$((S0 + 2))" "$(jqn "$(body "$R")" total)"
# 筛选（页面「日期区间」用驼峰 dateFrom/dateTo）
R=$(webproxy "$OWN" "/api/v1/sales/daily?page=1&pageSize=10&dateFrom=2026-10-16&dateTo=2026-10-16")
log "P6-日期筛选=$(st "$R") body=$(body "$R")"
chk "P6-日期筛选api200" "200" "$(st "$R")"
chk "P6-日期筛选1行" "1" "$(jqn "$(body "$R")" total)"
# 数量/金额原样字符串（前端不做精确算术的前提）
chk "P6-qty=12.000" "1" "$(echo "$(body "$(webproxy "$OWN" "/api/v1/sales/daily?page=1&pageSize=10&sku_id=9002")")" | grep -c '"qty":"12.000"')"

# ============================================================== W1 导入确认同键重试
sep; log "W1 导入确认：SKU 批次 $BATCH 同键重试不得新增第二条记录"
SKUA=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
C1=$(postj "$OP" "/api/v1/import/batches/$BATCH/confirm" "b10-w1-confirm" '{}')
log "W1-首次http=$(st "$C1") header=[$(hdr Idempotent-Replayed)] body=$(body "$C1")"
chk "W1-首次200" "200" "$(st "$C1")"
chk "W1-首次无重放头" "" "$(hdr Idempotent-Replayed)"
chk "W1-首次批次COMPLETED" "COMPLETED" "$(sql "SELECT status FROM import_batches WHERE id=$BATCH")"
SKUB=$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")
chk "W1-首次落库2行" "$((SKUA + 2))" "$SKUB"
AUD1=$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND biz_id IN (SELECT id FROM skus WHERE team_id=7 AND sku_code LIKE 'SKU-B10-%')")
log "W1-首次审计行数=$AUD1"
chk "W1-首次审计2条" "2" "$AUD1"

C2=$(postj "$OP" "/api/v1/import/batches/$BATCH/confirm" "b10-w1-confirm" '{}')
log "W1-重试http=$(st "$C2") header=[$(hdr Idempotent-Replayed)] body=$(body "$C2")"
chk "W1-重试200" "200" "$(st "$C2")"
chk "W1-重试带重放头" "true" "$(hdr Idempotent-Replayed)"
chk "W1-重试不新增记录" "$SKUB" "$(sql "SELECT COUNT(*) FROM skus WHERE team_id=7")"
chk "W1-成功行数=2" "2" "$(sql "SELECT success_rows FROM import_batches WHERE id=$BATCH")"
chk "W1-失败行数=0" "0" "$(sql "SELECT failed_rows FROM import_batches WHERE id=$BATCH")"
chk "W1-重试不重复写审计" "$AUD1" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE team_id=7 AND biz_id IN (SELECT id FROM skus WHERE team_id=7 AND sku_code LIKE 'SKU-B10-%')")"

# ============================================================== W2 盘点调整同键重试
sep; log "W2 盘点调整：SKU-B 10.000 → 6.000，同键重试不得二次变动余额"
BAL0=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")
TXN0=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")
A1=$(postj "$OP" "/api/v1/inventory/adjustments" "b10-w2-adjust" \
  '{"warehouse_id":9004,"sku_id":9003,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"6.000","reason":"B10 联调盘点"}')
T1=$(jqn "$(body "$A1")" transaction_id)
log "W2-首次http=$(st "$A1") txn=$T1 header=[$(hdr Idempotent-Replayed)] bal=$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")"
chk "W2-首次201" "201" "$(st "$A1")"
chk "W2-首次无重放头" "" "$(hdr Idempotent-Replayed)"
chk "W2-余额6.000" "6.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")"
chk "W2-流水+1" "$((TXN0 + 1))" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"

A2=$(postj "$OP" "/api/v1/inventory/adjustments" "b10-w2-adjust" \
  '{"warehouse_id":9004,"sku_id":9003,"txn_type":"COUNT_ADJUST","target_on_hand_qty":"6.000","reason":"B10 联调盘点"}')
T2=$(jqn "$(body "$A2")" transaction_id)
log "W2-重试http=$(st "$A2") txn=$T2 header=[$(hdr Idempotent-Replayed)]"
chk "W2-重试201" "201" "$(st "$A2")"
chk "W2-同一transaction_id" "$T1" "$T2"
chk "W2-重试带重放头" "true" "$(hdr Idempotent-Replayed)"
chk "W2-余额仍6.000" "6.000" "$(sql "SELECT on_hand_qty FROM inventory_balances WHERE team_id=7 AND warehouse_id=9004 AND sku_id=9003")"
chk "W2-流水不新增" "$((TXN0 + 1))" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7")"

# ============================================================== W3 冲销同键重试
sep; log "W3 冲销：seed-init-9005（SKU-C 10.000 → 0.000），同键重试不得二次冲销"
ORIG=$(sql "SELECT id FROM inventory_transactions WHERE idempotency_key='seed-init-9005'")
R0=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'")
R1=$(postj "$OWN" "/api/v1/inventory/transactions/$ORIG/reverse" "b10-w3-reverse" '{"reason":"B10 联调冲销"}')
RT1=$(jqn "$(body "$R1")" transaction_id)
log "W3-首次http=$(st "$R1") txn=$RT1 header=[$(hdr Idempotent-Replayed)]"
chk "W3-首次200" "200" "$(st "$R1")"
chk "W3-首次无重放头" "" "$(hdr Idempotent-Replayed)"
chk "W3-REVERSAL+1" "$((R0 + 1))" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'")"

R2=$(postj "$OWN" "/api/v1/inventory/transactions/$ORIG/reverse" "b10-w3-reverse" '{"reason":"B10 联调冲销"}')
RT2=$(jqn "$(body "$R2")" transaction_id)
log "W3-重试http=$(st "$R2") txn=$RT2 header=[$(hdr Idempotent-Replayed)]"
chk "W3-重试200" "200" "$(st "$R2")"
chk "W3-同一transaction_id" "$RT1" "$RT2"
chk "W3-重试带重放头" "true" "$(hdr Idempotent-Replayed)"
chk "W3-REVERSAL仍1" "$((R0 + 1))" "$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7 AND txn_type='REVERSAL'")"

# ============================================================== 汇总
sep
TOTAL=$(grep -c "^PASS " "$LOG")
log "B10_CHECK_TOTAL=$TOTAL"
log "B10_FAIL_COUNT=$FAIL"
[ "$FAIL" = "0" ] && log "B10_RESULT=ALL PASS" || log "B10_RESULT=HAS FAILURES$FAILED_LIST"
log "数据全景 team7：balances=$(sql "SELECT COUNT(*) FROM inventory_balances WHERE team_id=7") txn=$(sql "SELECT COUNT(*) FROM inventory_transactions WHERE team_id=7") sales=$(sql "SELECT COUNT(*) FROM sales_daily WHERE team_id=7") batches=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
exit $([ "$FAIL" = "0" ] && echo 0 || echo 1)
