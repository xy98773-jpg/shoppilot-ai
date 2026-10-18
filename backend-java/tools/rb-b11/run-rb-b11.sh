#!/usr/bin/env bash
# =============================================================================
# B11 独立复核真栈探针（项目负责人自建，不复用开发 tools/b11 的任何断言）
# 目的：只覆盖「SSOT 明文但开发未覆盖」的分支，验证 8cb7cbc 的完整性
#   探针 P1  IMP-4011（storage_path 空 / 路径不存在）→ 410，且零副作用
#   探针 P2  校验优先级：非终态 + 文件不可读 → 必须 422 IMP-4005（状态先于文件）
#   探针 P3  DUPLICATE_REJECTED 批次重导 → 201（终态可重导分支）
#   探针 P4  槽位反向：重导后同文件再 upload → 409 IMP-4004 且 data.batchId=新批
#   探针 P5  跨团队 reimport → 404 IMP-4007（不得 403/4004）
# 生命周期：本脚本自起自停 app；MySQL 容器由调用方预起（rb-b11-mysql:3362）
# =============================================================================
set -u
BASE="http://127.0.0.1:${APP_PORT:-18260}"
MYSQL_CT="${MYSQL_CT:-rb-b11-mysql}"
PW='S3cret!Passw0rd'
HDR_FILE="$(mktemp)"
PASS=0; FAIL=0; TOTAL=0

log(){ printf '%s\n' "$*"; }
sep(){ log "---------------------------------------------------------------"; }
chk(){ TOTAL=$((TOTAL+1)); if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "PASS $1 => $3"; else FAIL=$((FAIL+1)); log "FAIL $1 => 期望[$2] 实际[$3]"; fi; }
sql(){ docker exec -i "$MYSQL_CT" mysql -uroot -proot --default-character-set=utf8mb4 -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
code(){ echo "$1" | grep -o '"code":"[^"]*"' | head -1 | sed 's/"code":"//;s/"//'; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }

token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$4"; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE$2" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d "${4:-{}}"; }
reimport(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/reimport" \
  -H "Authorization: Bearer $1"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }

MAP_SKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'

# 走完整链路到 COMPLETED，返回 batch_id
chain_completed(){ # token file bizType idemKey
  local R B
  R=$(upload "$1" x "$(win "$2")" "$3"); B=$(jqn "$(body "$R")" batch_id)
  getq "$1" "/api/v1/import/batches/$B/preview?limit=50" >/dev/null
  putm "$1" "$B" "$MAP_SKU" >/dev/null
  validate "$1" "$B" >/dev/null
  R=$(postn "$1" "/api/v1/import/batches/$B/confirm" "$4")
  echo "$B"
}

OUT_DIR="${OUT_DIR:-$(pwd)}"
CSV1="$OUT_DIR/rb-csv-1.csv"; CSV2="$OUT_DIR/rb-csv-2.csv"
printf 'sku_code,product_code\nSKU-RB-P1,P-BAG-001\n' > "$CSV1"
printf 'sku_code,product_code\nSKU-RB-P2,P-BAG-001\n' > "$CSV2"
HASH1=$(sha256sum "$CSV1" | cut -c1-64)

OP=$(token operator01); OP8=$(token owner8)
[ -n "$OP" ] && [ -n "$OP8" ] || { log "FATAL login failed"; exit 6; }
log "TOKEN op=${OP:0:8} op8=${OP8:0:8}"

# 清场：只清导入侧，保留种子主数据
sql "DELETE FROM import_row_errors; DELETE FROM import_batches; DELETE FROM audit_logs;"
log "RESET import_batches=$(sql "SELECT COUNT(*) FROM import_batches") audit=$(sql "SELECT COUNT(*) FROM audit_logs")"

# ============================================================ P1 IMP-4011
sep; log "P1 IMP-4011：终态批 storage_path 不可用 → 410，且零副作用"
A=$(chain_completed "$OP" "$CSV1" SKU rb-p1)
ORIG_STORAGE=$(sql "SELECT storage_path FROM import_batches WHERE id=$A")
log "P1-基线批 A=$A status=$(sql "SELECT status FROM import_batches WHERE id=$A") storage=$ORIG_STORAGE"
B_BEFORE=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")
AU_BEFORE=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")

sql "UPDATE import_batches SET storage_path=NULL WHERE id=$A"
R=$(reimport "$OP" "$A")
log "P1a 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P1a-空路径-410" "410" "$(st "$R")"
chk "P1a-错误码" "IMP-4011" "$(code "$(body "$R")")"
chk "P1a-不产生新批次" "$B_BEFORE" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
chk "P1a-无重导审计" "$AU_BEFORE" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"
chk "P1a-原批未被取代" "NULL" "$(sql "SELECT IFNULL(superseded_by_batch_id,'NULL') FROM import_batches WHERE id=$A")"
chk "P1a-槽位未被释放" "$HASH1" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$A")"

sql "UPDATE import_batches SET storage_path='/no/such/dir/ghost.csv' WHERE id=$A"
R=$(reimport "$OP" "$A")
log "P1b 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P1b-路径不存在-410" "410" "$(st "$R")"
chk "P1b-错误码" "IMP-4011" "$(code "$(body "$R")")"
sql "UPDATE import_batches SET storage_path='$ORIG_STORAGE' WHERE id=$A"
chk "P1b-路径已还原" "$ORIG_STORAGE" "$(sql "SELECT storage_path FROM import_batches WHERE id=$A")"

# ============================================================ P2 校验优先级
sep; log "P2 校验优先级：非终态 + 文件不可读 → 必须 422 IMP-4005（状态先于文件）"
R=$(upload "$OP" x "$(win "$CSV2")" SKU); B2=$(jqn "$(body "$R")" batch_id)
log "P2-基线批 B=$B2 status=$(sql "SELECT status FROM import_batches WHERE id=$B2")"
sql "UPDATE import_batches SET storage_path=NULL WHERE id=$B2"
R=$(reimport "$OP" "$B2")
log "P2 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P2-非终态先判-422" "422" "$(st "$R")"
chk "P2-错误码是4005而非4011" "IMP-4005" "$(code "$(body "$R")")"

# ============================================================ P3 DUPLICATE_REJECTED 重导
sep; log "P3 DUPLICATE_REJECTED（终态且可重导）→ 201，attempt_no 递推"
R=$(upload "$OP" x "$(win "$CSV1")" SKU)
log "P3 重复上传原始响应 status=$(st "$R") body=$(body "$R")"
chk "P3-重复上传409" "409" "$(st "$R")"
D=$(sql "SELECT id FROM import_batches WHERE team_id=7 AND status='DUPLICATE_REJECTED' ORDER BY id DESC LIMIT 1")
chk "P3-留痕批次状态" "DUPLICATE_REJECTED" "$(sql "SELECT status FROM import_batches WHERE id=$D")"
chk "P3-留痕批不占槽位" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$D")"
AT_D=$(sql "SELECT attempt_no FROM import_batches WHERE id=$D")
log "P3-留痕批 D=$D attempt_no=$AT_D"
R=$(reimport "$OP" "$D")
log "P3 重导原始响应 status=$(st "$R") body=$(body "$R")"
chk "P3-重导201" "201" "$(st "$R")"
N=$(jqn "$(body "$R")" batch_id)
chk "P3-新批attempt递推" "$((AT_D+1))" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$N")"
chk "P3-新批UPLOADED" "UPLOADED" "$(sql "SELECT status FROM import_batches WHERE id=$N")"
chk "P3-新批占槽位" "$HASH1" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$N")"
chk "P3-原留痕批被取代" "$N" "$(sql "SELECT IFNULL(superseded_by_batch_id,'NULL') FROM import_batches WHERE id=$D")"

# ============================================================ P4 槽位转移（独立文件，不依赖 P3）
sep; log "P4 槽位转移：新文件重导后同文件再 upload → 409 IMP-4004 且 data.existing_batch_id=重导新批"
CSV3="$OUT_DIR/rb-csv-3.csv"
printf 'sku_code,product_code\nSKU-RB-P4,P-BAG-001\n' > "$CSV3"
HASH3=$(sha256sum "$CSV3" | cut -c1-64)
X=$(chain_completed "$OP" "$CSV3" SKU rb-p4)
log "P4-基线批 X=$X status=$(sql "SELECT status FROM import_batches WHERE id=$X")"
R=$(reimport "$OP" "$X")
log "P4 重导原始响应 status=$(st "$R") body=$(body "$R")"
chk "P4-重导201" "201" "$(st "$R")"
N3=$(jqn "$(body "$R")" batch_id)
chk "P4-新批占槽位" "$HASH3" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$N3")"
chk "P4-原批槽位释放" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$X")"
chk "P4-原批被取代" "$N3" "$(sql "SELECT IFNULL(superseded_by_batch_id,'NULL') FROM import_batches WHERE id=$X")"
R=$(upload "$OP" x "$(win "$CSV3")" SKU)
log "P4 重复上传原始响应 status=$(st "$R") body=$(body "$R")"
chk "P4-同文件再传409" "409" "$(st "$R")"
chk "P4-回带批次=重导新批" "$N3" "$(jqn "$(body "$R")" existing_batch_id)"

# ============================================================ P5 跨团队
sep; log "P5 跨团队 reimport → 404 IMP-4007（不得 403/4004）"
R=$(reimport "$OP8" "$A")
log "P5 原始响应 status=$(st "$R") body=$(body "$R")"
chk "P5-跨团队404" "404" "$(st "$R")"
chk "P5-错误码" "IMP-4007" "$(code "$(body "$R")")"

sep
log "RB_B11_CHECK_TOTAL=$TOTAL  RB_B11_FAIL_COUNT=$FAIL"
[ "$FAIL" -eq 0 ] && log "RB_B11_RESULT=ALL PASS" || log "RB_B11_RESULT=HAS FAIL"
exit "$FAIL"
