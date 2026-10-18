#!/usr/bin/env bash
# =============================================================================
# B12（R87 / R88）真栈取证脚本 —— 可重导状态集口径（D 自身重导必须 4005）
#
# 独立性：自建 fat jar + 独立 MySQL 8.0.36 容器，**不**复用任何 dev 栈。
# 端口 3363 / 18270 —— 任务书建议值，启动前已 netstat -ano 实测空闲。
#   已用：3319、3327–3342、3355–3357、3360、3361、18099、18107–18210、18220、18230、18240、18250；
#   ★ 3350 被宿主 WorkBuddy.exe 占用（禁用）。
#
# 组次（对应任务书【五】的 5 条硬要求 + 2 条附加）：
#   G1  A(COMPLETED) 重导 → 201，新批 attempt_no=2
#   G2  D(DUPLICATE_REJECTED) 自身重导 → 422 IMP-4005（错误码字符串，不猜 HTTP）
#   G3  D 重导被拒后零副作用：无新批次 / 无 FORCE_REIMPORT 审计 / A 槽位未变
#   G4  同文件再传 → 409 IMP-4004 且 existing_batch_id=新批
#   G5  A 槽位释放、新批占位、A.superseded_by_batch_id=新批
#   G6  提示语只列四个合法状态、不含 DUPLICATE_REJECTED；当前状态在 rejectedValue 机读可见
#
# 用法（先建 jar）：
#   cd backend-java && ./mvnw -B -DskipTests package
#   bash backend-java/tools/b12/run-b12-evidence.sh
#   可覆盖： B12_MYSQL_PORT=xxxx B12_APP_PORT=xxxxx
#
# 产物：
#   backend-java/tools/b12/evidence-b12.txt          ← 断言日志（入库）
#   backend-java/tools/b12/evidence-b12-app.log      ← 应用 stdout（*.log 已 gitignore）
#   backend-java/tools/b12/evidence-b12-headers.log  ← 最近一次响应头（*.log 已 gitignore）
#
# ★ 应用日志必须写独立文件：与 tee 共用同一文件会互相覆盖。
# ★ mysql 客户端必须 --default-character-set=utf8mb4；curl 必须 --noproxy '*'。
# ★ 显式设置 IMPORT_STORAGE_DIR：避免上传文件落进仓库工作区。
# =============================================================================
set -u

REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
EVI="$REPO/backend-java/tools/evi"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR=$(ls "$REPO"/backend-java/app/target/shoppilot-app-*.jar 2>/dev/null | head -1)
LOG="$OUT_DIR/evidence-b12.txt"
APP_LOG="$OUT_DIR/evidence-b12-app.log"
HDR_FILE="$OUT_DIR/evidence-b12-headers.log"
MYSQL_CT="b12-mysql"
MYSQL_PORT="${B12_MYSQL_PORT:-3363}"
APP_PORT="${B12_APP_PORT:-18270}"
IMPORT_DIR="${B12_IMPORT_DIR:-$OUT_DIR/.import-storage}"
PW="S3cret!Passw0rd"
BASE="http://localhost:$APP_PORT"
JWT_SECRET="test-only-jwt-secret-0123456789abcdef"

: > "$LOG"
: > "$APP_LOG"
log(){ echo "$*" | tee -a "$LOG"; }
sep(){ log "---------------------------------------------------------------"; }

if [ -z "$JAR" ]; then log "FATAL: fat jar not found（先 cd backend-java && ./mvnw -B -DskipTests package）"; exit 2; fi
log "JAR=$JAR"
log "JAR_SHA256=$(sha256sum "$JAR" | cut -c1-64)"
log "DATE=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
log "PORTS mysql=$MYSQL_PORT app=$APP_PORT（任务书建议 3363 / 18270，已 netstat 复核空闲）"
log "IMPORT_STORAGE_DIR=$IMPORT_DIR（显式设置，避免落进仓库工作区）"

cleanup(){
  log "--- cleanup ---"
  docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
  [ -n "${APP_PID:-}" ] && kill "$APP_PID" 2>/dev/null || true
  rm -rf "$IMPORT_DIR" 2>/dev/null || true
}
trap cleanup EXIT
mkdir -p "$IMPORT_DIR"

# ---------------------------------------------------------------- 起独立 MySQL
docker rm -f "$MYSQL_CT" >/dev/null 2>&1 || true
docker run -d --name "$MYSQL_CT" -p "$MYSQL_PORT:3306" \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=shoppilot \
  mysql:8.0.36 --default-time-zone=+00:00 >> "$APP_LOG" 2>&1
READY=0
for i in $(seq 1 90); do
  if docker exec "$MYSQL_CT" mysqladmin -h127.0.0.1 -uroot -proot ping --silent >/dev/null 2>&1; then READY=1; break; fi
  sleep 2
done
log "MYSQL_READY=$READY (port $MYSQL_PORT, 独立容器 $MYSQL_CT)"
[ "$READY" = "1" ] || { log "FATAL mysql not ready"; exit 3; }

# ---------------------------------------------------------------- 起独立 app
SPRING_DATASOURCE_URL="jdbc:mysql://localhost:$MYSQL_PORT/shoppilot?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&serverTimezone=UTC&connectionTimezone=UTC" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
JWT_SECRET="$JWT_SECRET" \
IMPORT_STORAGE_DIR="$IMPORT_DIR" \
nohup java -jar "$JAR" --server.port=$APP_PORT >> "$APP_LOG" 2>&1 &
APP_PID=$!
log "APP_PID=$APP_PID (port $APP_PORT, stdout -> evidence-b12-app.log)"

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
  && log "SEED_OK (seed-b95.sql：团队7 / 仓库9004=WH-MAIN / 商品9001=P-BAG-001 / operator01)" \
  || { log "FATAL seed failed"; exit 5; }

FAIL=0
FAILED_LIST=""
chk(){ # name expected actual
  if [ "$2" = "$3" ]; then log "PASS $1 => $3"; else log "FAIL $1 expected=$2 actual=$3"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: expected=[$2] actual=[$3]"; fi
}
chkne(){ # name unexpected actual
  if [ "$2" != "$3" ]; then log "PASS $1 => $3（≠$2）"; else log "FAIL $1 不得等于[$2]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 不得等于[$2]"; fi
}
contains(){ # name needle haystack
  case "$3" in *"$2"*) log "PASS $1 => 含[$2]";; *) log "FAIL $1 期望包含[$2] 实际[$3]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 期望包含[$2] 实际[$3]";; esac
}
notcontains(){ # name needle haystack
  case "$3" in *"$2"*) log "FAIL $1 不得包含[$2] 实际[$3]"; FAIL=$((FAIL+1)); FAILED_LIST="$FAILED_LIST
  - $1: 不得包含[$2] 实际[$3]";; *) log "PASS $1 => 不含[$2]";; esac
}
sql(){ docker exec -i "$MYSQL_CT" mysql "${MYSQL_FLAGS[@]}" -N -B shoppilot -e "$1" 2>/dev/null; }
body(){ echo "${1%|*}"; }
st(){ echo "${1##*|}"; }
jqn(){ echo "$1" | grep -o "\"$2\":[0-9]*" | head -1 | sed 's/.*://'; }
jstr(){ echo "$1" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
token(){ curl -s --noproxy '*' -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | grep -o '"accessToken":"[^"]*"' | head -1 | sed 's/"accessToken":"//;s/"//'; }
upload(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches" \
  -H "Authorization: Bearer $1" -F "file=@$3;type=text/csv" -F "bizType=$4"; }
getq(){ curl -s --noproxy '*' -w "|%{http_code}" -H "Authorization: Bearer $1" "$BASE$2"; }
putm(){ curl -s --noproxy '*' -w "|%{http_code}" -X PUT "$BASE/api/v1/import/batches/$2/mapping" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
postn(){ # bearer path idemKey
  curl -s --noproxy '*' -D "$HDR_FILE" -w "|%{http_code}" -X POST "$BASE$2" \
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H "Idempotency-Key: $3" -d '{}'
}
reimport(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/reimport" \
  -H "Authorization: Bearer $1"; }
validate(){ curl -s --noproxy '*' -w "|%{http_code}" -X POST "$BASE/api/v1/import/batches/$2/validate" \
  -H "Authorization: Bearer $1"; }
win(){ echo "$(cd "$(dirname "$1")" && pwd -W)/$(basename "$1")"; }
# 只取 data 段里的字段（先剥信封，避免抓到信封里的 "code":"OK"）
dataField(){ local d=$(echo "$1" | sed 's/.*"data"://'); echo "$d" | grep -o "\"$2\":\"[^\"]*\"" | head -1 | sed "s/\"$2\":\"//;s/\"//"; }
# ★ 数字型字段必须单独取值：dataField 的模式要求值带双引号，
#   existing_batch_id 是 JSON number（"existing_batch_id":1），用 dataField 会得到空串 ⇒ 假 FAIL。
dataNum(){ local d=$(echo "$1" | sed 's/.*"data"://'); echo "$d" | grep -o "\"$2\":[0-9][0-9]*" | head -1 | sed "s/\"$2\"://"; }

OP=$(token operator01)
log "TOKEN op=${OP:0:8}"
[ -n "$OP" ] || { log "FATAL login failed"; exit 6; }

MAP_SKU='{"field_mapping_json":{"sku_code":"sku_code","product_code":"product_code"},"parse_options_json":{}}'
CSV="$OUT_DIR/sku-b12.csv"
printf 'sku_code,product_code\nSKU-B12-1,P-BAG-001\n' > "$CSV"
HASH=$(sha256sum "$CSV" | cut -c1-64)

# ---------------------------------------------------------------- 建 A（COMPLETED）
sep; log "前置：上传 → 预览 → 映射 → 校验 → 确认，得到 A(COMPLETED)"
R=$(upload "$OP" x "$(win "$CSV")" SKU)
A=$(jqn "$(body "$R")" batch_id)
chk "前置-上传201" "201" "$(st "$R")"
getq "$OP" "/api/v1/import/batches/$A/preview?limit=50" >/dev/null
putm "$OP" "$A" "$MAP_SKU" >/dev/null
R=$(validate "$OP" "$A")
chk "前置-校验200" "200" "$(st "$R")"
R=$(postn "$OP" "/api/v1/import/batches/$A/confirm" "b12-a")
log "前置-confirm=$(st "$R") body=$(body "$R")"
chk "前置-A终态COMPLETED" "COMPLETED" "$(jstr "$(body "$R")" status)"
log "A.id=$A hash=$HASH"

# ---------------------------------------------------------------- 造 D
sep; log "造 D：同文件再传 → 409 IMP-4004，并落一条 DUPLICATE_REJECTED（dedupe_hash=NULL）"
R=$(upload "$OP" x "$(win "$CSV")" SKU)
log "dup-upload=$(st "$R") body=$(body "$R")"
chk "D-重复上传409" "409" "$(st "$R")"
chk "D-错误码IMP-4004" "IMP-4004" "$(jstr "$(body "$R")" code)"
chk "D-existing_batch_id=A" "$A" "$(dataNum "$(body "$R")" existing_batch_id)"
D=$(sql "SELECT id FROM import_batches WHERE team_id=7 AND status='DUPLICATE_REJECTED' ORDER BY id DESC LIMIT 1")
log "D.id=$D 原始行=$(sql "SELECT CONCAT(id,'|',status,'|',IFNULL(dedupe_hash,'NULL'),'|',attempt_no,'|',duplicate_of_batch_id) FROM import_batches WHERE id=$D")"
chk "D-状态DUPLICATE_REJECTED" "DUPLICATE_REJECTED" "$(sql "SELECT status FROM import_batches WHERE id=$D")"
chk "D-槽位为NULL" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$D")"
chk "D-attempt_no=2" "2" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$D")"

BATCHES_BEFORE=$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")
AUDITS_BEFORE=$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")
A_HASH_BEFORE=$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$A")

# ---------------------------------------------------------------- G2/G3/G6：D 自身重导
sep; log "G2 D 自身重导 → 422 IMP-4005（不猜 HTTP，直接看错误码）"
R=$(reimport "$OP" "$D")
log "D-reimport=$(st "$R") body=$(body "$R")"
chk "G2-D重导422" "422" "$(st "$R")"
chk "G2-错误码IMP-4005" "IMP-4005" "$(jstr "$(body "$R")" code)"
chkne "G2-不是IMP-4004（不再是「请刷新」死路）" "IMP-4004" "$(jstr "$(body "$R")" code)"

sep; log "G6 提示语：只列四个合法状态、不含 DUPLICATE_REJECTED；当前状态在 rejectedValue 机读可见"
MSG=$(dataField "$(body "$R")" message)
log "G6-details.message=$MSG"
contains "G6-含COMPLETED" "COMPLETED" "$MSG"
contains "G6-含PARTIAL_SUCCESS" "PARTIAL_SUCCESS" "$MSG"
contains "G6-含FAILED" "FAILED" "$MSG"
contains "G6-含CANCELLED" "CANCELLED" "$MSG"
notcontains "G6-不含DUPLICATE_REJECTED" "DUPLICATE_REJECTED" "$MSG"
chk "G6-details.field=status" "status" "$(dataField "$(body "$R")" field)"
chk "G6-当前状态仍机读可见(rejectedValue)" "DUPLICATE_REJECTED" "$(dataField "$(body "$R")" rejectedValue)"

sep; log "G3 被拒后零副作用：无新批次 / 无 FORCE_REIMPORT 审计 / A 槽位未变"
log "批次快照=$(sql "SELECT GROUP_CONCAT(CONCAT(id,'/',status) ORDER BY id) FROM import_batches WHERE team_id=7")"
chk "G3-批次总数不变" "$BATCHES_BEFORE" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7")"
chk "G3-无FORCE_REIMPORT审计" "$AUDITS_BEFORE" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"
chk "G3-A槽位未变" "$A_HASH_BEFORE" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$A")"
chk "G3-A未被标记取代" "NULL" "$(sql "SELECT IFNULL(superseded_by_batch_id,'NULL') FROM import_batches WHERE id=$A")"
chk "G3-D状态未变" "DUPLICATE_REJECTED" "$(sql "SELECT status FROM import_batches WHERE id=$D")"

# ---------------------------------------------------------------- G1/G5：A 重导仍正常
sep; log "G1/G5 A(COMPLETED) 重导 → 201，新批 attempt_no=2、占槽位；A 释放并被标记取代"
R=$(reimport "$OP" "$A")
log "A-reimport=$(st "$R") body=$(body "$R")"
B=$(jqn "$(body "$R")" batch_id)
chk "G1-A重导201" "201" "$(st "$R")"
chk "G1-新批attempt_no=2" "2" "$(sql "SELECT attempt_no FROM import_batches WHERE id=$B")"
chk "G5-新批占槽位" "$HASH" "$(sql "SELECT dedupe_hash FROM import_batches WHERE id=$B")"
chk "G5-A槽位释放" "NULL" "$(sql "SELECT IFNULL(dedupe_hash,'NULL') FROM import_batches WHERE id=$A")"
chk "G5-A.superseded_by=新批" "$B" "$(sql "SELECT superseded_by_batch_id FROM import_batches WHERE id=$A")"
chk "G5-同hash仅1行占用" "1" "$(sql "SELECT COUNT(*) FROM import_batches WHERE team_id=7 AND dedupe_hash='$HASH'")"
chk "G5-FORCE_REIMPORT审计+1" "$((AUDITS_BEFORE + 1))" "$(sql "SELECT COUNT(*) FROM audit_logs WHERE action='IMPORT_BATCH_FORCE_REIMPORT' AND team_id=7")"

# ---------------------------------------------------------------- G4：同文件再传
sep; log "G4 同文件再传 → 409 IMP-4004，existing_batch_id=新批（原批槽位确已释放）"
R=$(upload "$OP" x "$(win "$CSV")" SKU)
log "post-reimport-upload=$(st "$R") body=$(body "$R")"
chk "G4-再传409" "409" "$(st "$R")"
chk "G4-错误码IMP-4004" "IMP-4004" "$(jstr "$(body "$R")" code)"
chk "G4-existing_batch_id=新批" "$B" "$(dataNum "$(body "$R")" existing_batch_id)"

# ---------------------------------------------------------------- 汇总
sep
log "批次全景 team7：$(sql "SELECT GROUP_CONCAT(CONCAT(id,'/',status,'/attempt=',attempt_no,'/hash=',IFNULL(LEFT(dedupe_hash,8),'NULL')) ORDER BY id) FROM import_batches WHERE team_id=7")"
log "B12_CHECK_TOTAL=$(( $(grep -c '^PASS ' "$LOG") + FAIL ))"
log "B12_FAIL_COUNT=$FAIL"
if [ "$FAIL" = "0" ]; then log "B12_RESULT=ALL PASS"; else log "B12_RESULT=FAILED$FAILED_LIST"; fi
[ "$FAIL" = "0" ] || exit 7
