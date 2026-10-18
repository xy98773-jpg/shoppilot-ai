#!/usr/bin/env bash
# =============================================================================
# B12 独立复核真栈探针（项目负责人自建，不复用开发 tools/b12）
# 目标：只覆盖「口径已改、但开发未覆盖」的分支
#   P1 PARTIAL_SUCCESS 批重导 → 201（反向误杀：收窄状态集不得误伤 PS）
#   P2 跨团队对 D 重导 → 404 IMP-4007（团队过滤先于状态判断）
#   P3 D + storage_path=NULL → 422 IMP-4005（状态先于文件，不得 410）
#   P4 details 键集稳定：仅 field/message/rejectedValue，且 message 不含 D
#   P5 正确入口闭环：A(COMPLETED) 重导 → 201；随后 D 仍 4005（D 永远不是入口）
# =============================================================================
set -u
BASE="http://127.0.0.1:${APP_PORT:-18280}"
MYSQL_CT="${MYSQL_CT:-rb-b12-mysql}"
PW='S3cret!Passw0rd'
PASS=0; FAIL=0; TOTAL=0

log(){ printf '%s\n' "$*"; }
sep(){ log "---------------------------------------------------------------"; }
chk(){ TOTAL=$((TOTAL+1)); if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "PASS $1 => $3"; else FAIL=$((FAIL+1)); log "FAIL $1 => 期望[$2] 实际[$3]"; fi; }
sql(){ docker exec -i "$MYSQL_CT" mysql -uroot -proot --default-character-set=utf8mb4 -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }

token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$4"; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE$2" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "${4:-{}}"; }
reimport(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/reimport" \
  -H "Authorization: Bearer $1"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }

MAP_SKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'
MAP_SALES='{"field_mapping_json":{"sku_code":"sku_code","warehouse_code":"warehouse_code","sale_date":"sale_date","qty":"qty"},"parse_options_json":{}}'

chain(){ # token file bizType map idemKey -> batch_id（推进到终态）
  local R B
  R=$(upload "$1" x "$(win "$2")" "$3"); B=$(jqn "$(body "$R")" batch_id)
  getq "$1" "/api/v1/import/batches/$B/preview?limit=50" >/dev/null
  putm "$1" "$B" "$4" >/dev/null
  validate "$1" "$B" >/dev/null
  R=$(postn "$1" "/api/v1/import/batches/$B/confirm" "$5")
  echo "$B"
}

OUT_DIR="${OUT_DIR:-$(pwd)}"
CSV_SKU="$OUT_DIR/rb12-sku.csv"
CSV_SALES="$OUT_DIR/rb12-sales.csv"
printf 'sku_code,product_code\nSKU-RB12,P-BAG-001\n' > "$CSV_SKU"
printf 'sku_code,warehouse_code,sale_date,qty\nSKU-A,WH-MAIN,2026-10-01,4.000\nSKU-B,WH-MAIN,2026-10-01,5.000\n' > "$CSV_SALES"
HASH_SKU=$(sha256sum "$CSV_SKU" | cut -c1-64)

OP=$(token operator01); OP8=$(token owner8)
[ -n "$OP" ] && [ -n "$OP8" ] || { log "FATAL login failed"; exit 6; }
sql "DELETE FROM import_row_errors; DELETE FROM import_batches; DELETE FROM audit_logs;"
log "TOKEN ok | RESET batches=$(sql "SELECT COUNT(*) FROM import_batches")"

# ============================================================ 前置：A(COMPLETED) + D(DUPLICATE_REJECTED)
sep; log "前置：造 A(COMPLETED) 与 D(DUPLICATE_REJECTED)"
A=$(chain "$OP" "$CSV_SKU" SKU "$MAP_SKU" rb12-a)
chk "前置-A终态" "COMPLETED" "$(sql "SELECT status FROM import_batches WHERE id=$A")"
chk "前置-A占槽位" "$HASH_SKU" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$A")"
R=$(upload "$OP" x "$(win "$CSV_SKU")" SKU)
log "前置-重复上传 status=$(st "$R") body=$(body "$R")"
chk "前置-重复上传409" "409" "$(st "$R")"
D=$(sql "SELECT id FROM import_batches WHERE team_id=7 AND status='DUPLICATE_REJECTED' ORDER BY id DESC LIMIT 1")
chk "前置-D状态" "DUPLICATE_REJECTED" "$(sql "SELECT status FROM import_batches WHERE id=$D")"
chk "前置-D不占槽位" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$D")"

# ============================================================ P2 跨团队
sep; log "P2 跨团队对 D 重导 → 404 IMP-4007（团队过滤先于状态判断）"
R=$(reimport "$OP8" "$D")
log "P2 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P2-跨团队404" "404" "$(st "$R")"
chk "P2-错误码4007" "IMP-4007" "$(code "$(body "$R")")"

# ============================================================ P3 优先级
sep; log "P3 D + storage_path=NULL → 必须 422 IMP-4005（状态先于文件，不得 410）"
ORIG_D_PATH=$(sql "SELECT IFNULL(storage_path,'NULL') FROM import_batches WHERE id=$D")
sql "UPDATE import_batches SET storage_path=NULL WHERE id=$D"
R=$(reimport "$OP" "$D")
log "P3 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P3-状态先判422" "422" "$(st "$R")"
chk "P3-是4005不是4011" "IMP-4005" "$(code "$(body "$R")")"
if [ "$ORIG_D_PATH" != "NULL" ]; then sql "UPDATE import_batches SET storage_path='$ORIG_D_PATH' WHERE id=$D"; fi

# ============================================================ P4 details 结构
sep; log "P4 details 键集稳定 + message 不含 D"
R=$(reimport "$OP" "$D"); B4=$(body "$R")
log "P4 原始响应 body=$B4"
MSG=$(echo "$B4" | grep -o '"message":"[^"]*"' | tail -1 | sed 's/"message":"//;s/"//')
chk "P4-details.field=status" "status" "$(echo "$B4" | grep -o '"field":"[^"]*"' | head -1 | sed 's/"field":"//;s/"//')"
chk "P4-rejectedValue=D" "DUPLICATE_REJECTED" "$(echo "$B4" | grep -o '"rejectedValue":"[^"]*"' | head -1 | sed 's/"rejectedValue":"//;s/"//')"
chk "P4-message不含D" "0" "$(echo "$MSG" | grep -c 'DUPLICATE_REJECTED' || true)"
chk "P4-无新增value键" "0" "$(echo "$B4" | grep -c '"value":' || true)"
chk "P4-message含四个合法状态" "4" "$(echo "$MSG" | grep -o 'COMPLETED\|PARTIAL_SUCCESS\|FAILED\|CANCELLED' | sort -u | wc -l)"

# ============================================================ P5 正确入口闭环
sep; log "P5 正确入口：A(COMPLETED) 重导 → 201；随后 D 仍 4005"
AT_A=$(sql "SELECT attempt_no FROM import_batches WHERE id=$A")
R=$(reimport "$OP" "$A")
log "P5 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P5-A重导201" "201" "$(st "$R")"
N=$(jqn "$(body "$R")" batch_id)
chk "P5-新批attempt递推" "$((AT_A+1))" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$N")"
chk "P5-新批占槽位" "$HASH_SKU" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$N")"
chk "P5-A槽位释放" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$A")"
R=$(reimport "$OP" "$D")
chk "P5-D仍4005" "IMP-4005" "$(code "$(body "$R")")"

# ============================================================ P1 PARTIAL_SUCCESS 重导
sep; log "P1 PARTIAL_SUCCESS 批重导 → 201（收窄状态集不得误伤 PS）"
R=$(upload "$OP" x "$(win "$CSV_SALES")" SALES_DAILY); S=$(jqn "$(body "$R")" batch_id)
getq "$OP" "/api/v1/import/batches/$S/preview?limit=50" >/dev/null
putm "$OP" "$S" "$MAP_SALES" >/dev/null
R=$(validate "$OP" "$S"); chk "P1-校验通过" "VALIDATED" "$(echo "$(body "$R")" | grep -o '"status":"[^"]*"' | head -1 | sed 's/"status":"//;s/"//')"
sql "UPDATE skus SET listed_date='2026-10-10' WHERE team_id=7 AND id=9003"
R=$(postn "$OP" "/api/v1/import/batches/$S/confirm" rb12-sales)
log "P1 confirm status=$(st "$R") body=$(body "$R")"
chk "P1-PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$(echo "$(body "$R")" | grep -o '"status":"[^"]*"' | head -1 | sed 's/"status":"//;s/"//')"
AT_S=$(sql "SELECT attempt_no FROM import_batches WHERE id=$S")
R=$(reimport "$OP" "$S")
log "P1 重导原始响应 status=$(st "$R") body=$(body "$R")"
chk "P1-PS重导201" "201" "$(st "$R")"
NS=$(jqn "$(body "$R")" batch_id)
chk "P1-PS新批attempt递推" "$((AT_S+1))" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$NS")"

sep
log "RB_B12_CHECK_TOTAL=$TOTAL  RB_B12_FAIL_COUNT=$FAIL"
[ "$FAIL" -eq 0 ] && log "RB_B12_RESULT=ALL PASS" || log "RB_B12_RESULT=HAS FAIL"
exit "$FAIL"
