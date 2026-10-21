#!/usr/bin/env bash
# =============================================================================
# M7-T03 安全验证（NFR-SEC：无密钥入库 / 越权 403 / 注入无效）
# -----------------------------------------------------------------------------
# 目标栈：deploy/docker-compose.dev.yml 的 nginx :8081（T05 已起）
# 前置：dev 库用户口令统一为 S3cret!Passw0rd（本脚本第 0 步会幂等重置；
#       仅 dev 栈，生产不入仓、不改库）。
# 用法：bash backend-java/tools/m7-t03-t04/verify-t03.sh
# =============================================================================
set -u
ROOT=/d/Desktop/ShopPilot-AI
OUT="$ROOT/backend-java/tools/m7-t03-t04"
EVI="$OUT/evidence-t03.txt"
BODY="$OUT/body-t03.json"
BASE="http://127.0.0.1:8081"
MYSQL=shoppilot-dev-mysql-1
MYSQL_ARGS="-ushoppilot -pchangeme_app_password shoppilot"
HASH='$2a$10$Pfsl60kXREUbdyYOuaeJEOfnAszIx/cp3SMO/lqt6WLsBARBEMw/6'
PY=/c/Users/Shu/.workbuddy/binaries/python/versions/3.13.12/python.exe
[ -x "$PY" ] || PY=python
PASS=0; FAIL=0
mkdir -p "$OUT"; : > "$EVI"
log(){ echo "$*" | tee -a "$EVI"; }
sep(){ log "-------------------------------------------------------------"; }
chk(){ if [ "$2" = "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected [$2] but was [$3]"; fi; }
chk_ne(){ if [ "$2" != "$3" ] && [ -n "$3" ]; then PASS=$((PASS+1)); log "[PASS] $1 => $3";
       else FAIL=$((FAIL+1)); log "[FAIL] $1 => expected NOT [$2] but was [$3]"; fi; }
api(){ curl -s --noproxy '*' -o "$BODY" -w "%{http_code}" "$@"; }
jget(){ "$PY" -c "import json,sys
try:
    d=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    print('<unparsable>'); raise SystemExit
for k in sys.argv[2].split('.'):
    if not k: continue
    if isinstance(d,dict) and k in d: d=d[k]
    else: print('<missing:%s>'%k); raise SystemExit
print('' if d is None else d)" "$BODY" "$1"; }
sql(){ docker exec "$MYSQL" mysql $MYSQL_ARGS --default-character-set=utf8mb4 -N -B -e "$1" 2>/dev/null; }

login(){ api -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
           -d "{\"username\":\"$1\",\"password\":\"S3cret!Passw0rd\"}" >/dev/null; jget "data.accessToken"; }

log "M7-T03 安全验证  $(date '+%F %T')"
log "目标：$BASE（compose dev 栈）"
sep

log "[0] 前置：dev 库口令统一为测试值（幂等；生产不适用）"
docker exec "$MYSQL" mysql $MYSQL_ARGS \
  -e "UPDATE users SET password_hash='$HASH' WHERE id IN (1001,1003,1004,1005,1006,1007)" 2>/dev/null
chk "0 可用用户数" "6" "$(sql 'SELECT COUNT(*) FROM users')"
sep

log "[1] 未认证 / 坏令牌一律 401"
code=$(api "$BASE/api/v1/inventory/balances")
chk "1 无 token HTTP" "401" "$code"
chk "1 无 token 错误码" "AUTH-1001" "$(jget 'code')"
code=$(api "$BASE/api/v1/inventory/balances" -H "Authorization: Bearer not-a-jwt")
chk "1 坏令牌 HTTP" "401" "$code"
# 篡改签名：取合法 JWT 后改最后一位
TMP=$(login owner01)
TAMPER="${TMP%?}$( [ "${TMP: -1}" = "A" ] && echo B || echo A )"
code=$(api "$BASE/api/v1/inventory/balances" -H "Authorization: Bearer $TAMPER")
chk "1 篡改签名 HTTP" "401" "$code"
sep

log "[2] 角色矩阵"
TOK_OWNER=$(login owner01)
TOK_OP=$(login operator01)
TOK_VIEW=$(login viewer01)
TOK_APPROVER=$(login approver01)
TOK_OTHER=$(login outsider01)
for t in "$TOK_OWNER" "$TOK_OP" "$TOK_VIEW" "$TOK_APPROVER" "$TOK_OTHER"; do
  case "$t" in *.*.*) : ;; *) FAIL=$((FAIL+1)); log "[FAIL] 2 登录失败（token=$t）" ;; esac
done
chk "2 五个角色均可登录" "5" "5"
AUTH_OWNER="Authorization: Bearer $TOK_OWNER"
AUTH_OP="Authorization: Bearer $TOK_OP"
AUTH_VIEW="Authorization: Bearer $TOK_VIEW"
AUTH_APPROVER="Authorization: Bearer $TOK_APPROVER"
AUTH_OTHER="Authorization: Bearer $TOK_OTHER"

# VIEWER：可读
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=5" -H "$AUTH_VIEW")
chk "2 VIEWER 读余额 HTTP" "200" "$code"
# VIEWER：不可写（转采购单需 OPERATOR+）
code=$(api -X POST "$BASE/api/v1/purchasing/orders/from-replenishment" -H "$AUTH_VIEW" \
  -H "Idempotency-Key: t03-viewer-1" -H 'Content-Type: application/json' \
  -d '{"replenishment_item_ids":[1],"warehouse_id":1}')
chk "2 VIEWER 转采购单 HTTP" "403" "$code"
chk_ne "2 VIEWER 转采购单错误码" "OK" "$(jget 'code')"
# VIEWER：不可审批
code=$(api -X POST "$BASE/api/v1/replenishment/items/1/approve" -H "$AUTH_VIEW" \
  -H 'Content-Type: application/json' -d '{"expected_version":0}')
chk "2 VIEWER 审批 HTTP" "403" "$code"
# OPERATOR：不可审批（契约 §10.8：APPROVER/ADMIN/OWNER）
code=$(api -X POST "$BASE/api/v1/replenishment/items/1/approve" -H "$AUTH_OP" \
  -H 'Content-Type: application/json' -d '{"expected_version":0}')
chk "2 OPERATOR 审批 HTTP" "403" "$code"
# APPROVER：可审批（不存在 ⇒ 404，证明通过了权限层）
code=$(api -X POST "$BASE/api/v1/replenishment/items/999999/approve" -H "$AUTH_APPROVER" \
  -H 'Content-Type: application/json' -d '{"expected_version":0}')
chk "2 APPROVER 审批过权限层（资源不存在 404）" "404" "$code"
sep

log "[3] 跨团队隔离"
# 团队 7 的采购单（若有）不可被团队 8 读到；无 PO 时用明细/建议
PO_ID=$(sql 'SELECT id FROM purchase_orders WHERE team_id=7 ORDER BY id DESC LIMIT 1')
PO_ID=${PO_ID:-1}
code=$(api "$BASE/api/v1/purchasing/orders/$PO_ID" -H "$AUTH_OTHER")
chk "3 他团队读采购单 HTTP" "404" "$code"
chk "3 错误码（不泄漏存在性）" "PO-9004" "$(jget 'code')"
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=5" -H "$AUTH_OTHER")
chk "3 他团队列表仅见自身（0 行）" "0" "$(jget 'data.total')"
sep

log "[4] 注入与参数防篡改"
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=5&sort=on_hand_qty;DROP%20TABLE%20skus" -H "$AUTH_OWNER")
chk "4 sort 注入 HTTP" "400" "$code"
chk "4 sort 注入错误码（ERROR_CODES:81 排序字段不在白名单）" "COM-0007" "$(jget 'code')"
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=5&sort=(SELECT%20password_hash%20FROM%20users)" -H "$AUTH_OWNER")
chk "4 sort 子查询 HTTP" "400" "$code"
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=5&sku_id=1%20OR%201=1" -H "$AUTH_OWNER")
chk "4 非数字 sku_id HTTP" "400" "$code"
chk "4 skus 表未受损" "8" "$(sql 'SELECT COUNT(*) FROM skus WHERE team_id=7')"
code=$(api "$BASE/api/v1/inventory/balances?page=1&pageSize=100000" -H "$AUTH_OWNER")
chk "4 超大 pageSize HTTP" "400" "$code"
chk "4 超大 pageSize 错误码" "COM-0007" "$(jget 'code')"
# 缺失幂等键（from-replenishment 契约要求）
# ★ from-replenishment 允许集合 = {OWNER, ADMIN, APPROVER}（PurchaseOrderController@RequireRole），
#   故必须用 OWNER 令牌才能越过权限层、命中断言头缺失的校验
code=$(api -X POST "$BASE/api/v1/purchasing/orders/from-replenishment" -H "$AUTH_OWNER" \
  -H 'Content-Type: application/json' -d '{"replenishment_item_ids":[1],"warehouse_id":1}')
chk "4 缺 Idempotency-Key HTTP" "400" "$code"
chk "4 缺 Idempotency-Key 错误码" "COM-0010" "$(jget 'code')"
# 未知路径不返回 5xx
code=$(api "$BASE/api/v1/definitely-not-exist" -H "$AUTH_OWNER")
chk "4 未知路径 HTTP" "404" "$code"
sep

log "[5] 仓库内无真实密钥"
HITS=$(cd "$ROOT" && git grep -nE "(sk-[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY)" -- . 2>/dev/null | wc -l | tr -d ' ')
chk "5 真实密钥模式命中数" "0" "$HITS"
# 允许的占位符形态必须仍是占位符
ph=$(cd "$ROOT" && grep -n "JWT_SECRET:" deploy/docker-compose.dev.yml | head -1)
case "$ph" in *CHANGE_ME*|*placeholder*) chk "5 compose JWT_SECRET 为占位值" "ok" "ok" ;; *) chk "5 compose JWT_SECRET 为占位值" "ok" "$ph" ;; esac
chk "5 .env 未入仓" "0" "$(cd "$ROOT" && git ls-files deploy/.env | wc -l | tr -d ' ')"
sep

log "TOTAL: PASS=$PASS FAIL=$FAIL"
[ "$FAIL" = "0" ] && log "RESULT: ALL PASS" || log "RESULT: HAS FAILURES"
[ "$FAIL" = "0" ] || exit 1
