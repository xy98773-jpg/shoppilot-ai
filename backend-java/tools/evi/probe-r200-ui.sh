#!/usr/bin/env bash
# =============================================================================
# R200 界面闭环探针（M8，2026-10-22）
# =============================================================================
# 目的：验证「补齐补货建议页 + 登记到货表单」所依赖的**真实端点**逐个可用，
#       且请求形状与前端 `api/replenishment.ts` / `api/purchasing.ts` 逐字一致。
#
# 与界面一一对应：
#   列表筛选（状态/风险/轮次）        → GET  /replenishment/items
#   行内「提交审核」                  → POST /replenishment/items/{id}/submit        { }
#   行内「撤回」                      → POST .../withdraw                             { }
#   行内「审批通过」                  → POST .../approve   {expected_version, comment?}
#   行内「驳回」（comment 必填）      → POST .../reject    {expected_version, comment}
#   行内「作废」                      → POST .../cancel                              {comment?}
#   详情弹窗「审核时间线」            → GET  .../items/{id}/reviews
#   勾选转采购单（含 409 冲突确认）   → POST /purchasing/orders/from-replenishment   （见 §5.92 已验证）
#
# 用法：bash backend-java/tools/evi/probe-r200-ui.sh [BASE]
#   BASE 默认 http://localhost:8081（nginx 入口，走的是真实前端同一条链路）
# 退出码：0 = 全部断言通过；非 0 = 有失败项
# =============================================================================
set -uo pipefail

BASE="${1:-http://localhost:8081}"
API="$BASE/api/v1"
PW="1234"
FAIL=0

ok()   { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
bad()  { printf '  \033[31mFAIL\033[0m %s\n' "$1"; FAIL=$((FAIL+1)); }
head2(){ printf '\n=== %s ===\n' "$1"; }

jget() { python -c "import sys,json;d=json.load(sys.stdin);print($1)"; }

# 容错版：解析失败（data=null / 字段缺失）时输出空串，不抛栈 —— 探针要报告失败项，
# 不能因为一次 400 就把整个脚本炸掉（否则后续断言全不执行，看不出全貌）。
jget_safe() {
  python -c "
import sys,json
try:
    d=json.load(sys.stdin)
    v=$1
    print('' if v is None else v)
except Exception:
    print('')
"
}

tok() {
  curl -s --noproxy '*' -X POST "$API/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | jget "d['data']['accessToken']"
}

# 断言 code==OK 并回显 data（stdout 供后续解析，stderr 供人看）
call() { # method url token body
  local m="$1" u="$2" t="$3" b="${4:-}"
  if [ -n "$b" ]; then
    curl -s --noproxy '*' -X "$m" "$API$u" -H "Authorization: Bearer $t" \
      -H 'Content-Type: application/json' ${MORE_HDR:+-H "$MORE_HDR"} -d "$b"
  else
    curl -s --noproxy '*' -X "$m" "$API$u" -H "Authorization: Bearer $t" ${MORE_HDR:+-H "$MORE_HDR"}
  fi
}

OP=$(tok operator01); APP=$(tok approver01)
[ -n "$OP" ] && ok "登录 operator01（密码 $PW）" || bad "登录 operator01 失败"
[ -n "$APP" ] && ok "登录 approver01" || bad "登录 approver01 失败"

# ---------------------------------------------------------------- 1. 列表筛选
head2 "1. 列表筛选（页面顶部三个筛选项）"
R=$(call GET "/replenishment/items?page=1&pageSize=20" "$OP")
TOTAL=$(echo "$R" | jget "d['data']['total']")
[ "$TOTAL" -gt 0 ] && ok "无筛选 total=$TOTAL" || bad "无筛选取不到数据"
# 列表元素必须含页面展示所需字段
echo "$R" | jget "'id' in d['data']['list'][0] and 'suggested_qty' in d['data']['list'][0] and 'version' in d['data']['list'][0]" \
  | grep -q True && ok "列表元素含 id/suggested_qty/qty/version（页面列依赖）" || bad "列表元素字段缺失"

R=$(call GET "/replenishment/items?page=1&pageSize=20&status=APPROVED" "$OP")
C=$(echo "$R" | jget "len([x for x in d['data']['list'] if x['status']!='APPROVED'])" 2>/dev/null || echo "?")
[ "$C" = "0" ] && ok "status=APPROVED 过滤生效" || bad "status 过滤未生效（混入 $C 条）"

R=$(call GET "/replenishment/items?page=1&pageSize=20&risk_level=HIGH" "$OP")
C=$(echo "$R" | jget "len([x for x in d['data']['list'] if x['risk_level']!='HIGH'])" 2>/dev/null || echo "?")
[ "$C" = "0" ] && ok "risk_level=HIGH 过滤生效" || bad "risk_level 过滤未生效（混入 $C 条）"

# 非法筛选值必须被拒（页面下拉是白名单，前端不会发非法值；此处验证服务端兜底）
CODE=$(call GET "/replenishment/items?page=1&pageSize=20&status=NOPE" "$OP" | jget "d['code']")
[ "$CODE" = "COM-0007" ] && ok "非法 status ⇒ COM-0007（服务端白名单兜底）" || bad "非法 status 返回 $CODE（期望 COM-0007）"

# ---------------------------------------------------------------- 2. 状态机往返
head2 "2. 状态机：提交 → 撤回（往返，净效果为零）"
SID=$(call GET "/replenishment/items?page=1&pageSize=1&status=SUGGESTED" "$OP" | jget "d['data']['list'][0]['id']")
echo "  用建议 #$SID 做往返"

R=$(call POST "/replenishment/items/$SID/submit" "$OP" '{}')
echo "$R" | jget "d['data']['to_status']" | grep -qx "PENDING_REVIEW" \
  && ok "submit ⇒ PENDING_REVIEW（请求体 {} 可空）" || bad "submit 未到 PENDING_REVIEW：$(echo "$R" | jget "d")"
echo "$R" | jget "d['data']['review_id']" | grep -qE '^[0-9]+$' \
  && ok "submit 返回 review_id（审计可追溯）" || bad "submit 未返回 review_id"

R=$(call POST "/replenishment/items/$SID/withdraw" "$OP" '{}')
echo "$R" | jget "d['data']['to_status']" | grep -qx "SUGGESTED" \
  && ok "withdraw ⇒ SUGGESTED（回到待提交）" || bad "withdraw 未回 SUGGESTED"

# ---------------------------------------------------------------- 3. 审批（含乐观锁）
head2 "3. 审批：通过 / 驳回 / 作废"
R=$(call POST "/replenishment/items/$SID/submit" "$OP" '{}')
VER=$(echo "$R" | jget "d['data']['version']")
echo "  提交后 version=$VER"

# 3-a 错误版本 ⇒ 409 RPL-8003（页面此时提示「已被他人修改，已刷新」）
CODE=$(call POST "/replenishment/items/$SID/approve" "$APP" "{\"expected_version\":$((VER+9))}" | jget "d['code']")
[ "$CODE" = "RPL-8003" ] && ok "approve 版本不符 ⇒ RPL-8003（乐观锁生效）" || bad "approve 版本不符返回 $CODE（期望 RPL-8003）"

# 3-b OPERATOR 不能审批 ⇒ 403（页面据此对 OPERATOR 隐藏「审批通过」按钮）
CODE=$(call POST "/replenishment/items/$SID/approve" "$OP" "{\"expected_version\":$VER}" | jget "d['code']")
[ "$CODE" = "TEAM-2003" ] && ok "OPERATOR 审批 ⇒ TEAM-2003（按钮对该角色隐藏，服务端兜底）" || bad "OPERATOR 审批返回 $CODE（期望 TEAM-2003）"

# 3-c 正确版本 + 意见 ⇒ APPROVED
R=$(call POST "/replenishment/items/$SID/approve" "$APP" "{\"expected_version\":$VER,\"comment\":\"探针：同意\"}")
echo "$R" | jget "d['data']['to_status']" | grep -qx "APPROVED" && ok "approve ⇒ APPROVED（带 comment）" || bad "approve 未到 APPROVED：$(echo "$R" | jget "d")"

# 3-d 作废（APPROVED → CANCELLED；页面「作废」按钮同此）
R=$(call POST "/replenishment/items/$SID/cancel" "$OP" '{"comment":"探针：恢复现场"}')
echo "$R" | jget "d['data']['to_status']" | grep -qx "CANCELLED" && ok "cancel ⇒ CANCELLED（请求体键是 comment）" || bad "cancel 未到 CANCELLED：$(echo "$R" | jget "d")"

# 3-e 驳回：另一条建议走 reject，且 comment 必填
SID2=$(call GET "/replenishment/items?page=1&pageSize=1&status=SUGGESTED" "$OP" | jget "d['data']['list'][0]['id']")
VER2=$(call POST "/replenishment/items/$SID2/submit" "$OP" '{}' | jget "d['data']['version']")
CODE=$(call POST "/replenishment/items/$SID2/reject" "$APP" "{\"expected_version\":$VER2,\"comment\":\"   \"}" | jget "d['code']")
[ "$CODE" = "RPL-8005" ] && ok "reject 空意见 ⇒ RPL-8005（驳回原因必填）" || bad "reject 空意见返回 $CODE（期望 RPL-8005）"
R=$(call POST "/replenishment/items/$SID2/reject" "$APP" "{\"expected_version\":$VER2,\"comment\":\"探针：驳回\"}")
echo "$R" | jget "d['data']['to_status']" | grep -qx "REJECTED" && ok "reject ⇒ REJECTED（终态）" || bad "reject 未到 REJECTED"

# ---------------------------------------------------------------- 4. 审核时间线
head2 "4. 详情弹窗「审核时间线」"
R=$(call GET "/replenishment/items/$SID/reviews?page=1&pageSize=100&sort=occurred_at,asc" "$OP")
N=$(echo "$R" | jget "d['data']['total']")
ACTIONS=$(echo "$R" | jget "'/'.join([x['action'] for x in d['data']['list']])")
[ "$N" -ge 3 ] && ok "建议 #$SID 时间线 $N 条：$ACTIONS" || bad "时间线仅 $N 条（期望 ≥3）"
echo "$R" | jget "'reviewer_role' in d['data']['list'][0] and 'occurred_at' in d['data']['list'][0]" \
  | grep -q True && ok "时间线字段含 reviewer_role/occurred_at（页面列依赖）" || bad "时间线字段缺失"

# ---------------------------------------------------------------- 5. 到货登记前置条件
head2 "5. 登记到货表单的前置数据（采购单需 ORDERED / PARTIALLY_RECEIVED）"
N=$(call GET "/purchasing/orders?page=1&pageSize=5&status=ORDERED" "$OP" | jget "d['data']['total']")
NPART=$(call GET "/purchasing/orders?page=1&pageSize=5&status=PARTIALLY_RECEIVED" "$OP" | jget "d['data']['total']")
if [ "$N" -gt 0 ] || [ "$NPART" -gt 0 ]; then
  ok "可登记到货的采购单：ORDERED=$N / PARTIALLY_RECEIVED=$NPART（可直接走界面登记）"
else
  echo "  无 ORDERED 采购单 ⇒ 自动准备一张（★ 仅首次执行；下次已有则跳过）"
  # 挑一条「带供应商」的待提交建议（无供应商 ⇒ from-replenishment 必 400 COM-0001）
  SID3=$(call GET "/replenishment/items?page=1&pageSize=50&status=SUGGESTED" "$APP" | python -c "
import sys,json
d=json.load(sys.stdin)
rows=d['data']['list']
pref=[r for r in rows if r.get('supplier_id')]
print((pref or rows)[0]['id'] if (pref or rows) else '')
")
  if [ -z "$SID3" ]; then
    bad "没有可用的待提交建议 ⇒ 先跑一次「🤖 新建 AI 分析」再重试"
  else
  call POST "/replenishment/items/$SID3/submit" "$APP" '{}' >/dev/null
  V3=$(call GET "/replenishment/items/$SID3" "$APP" | jget "d['data']['version']")
  ST3=$(call POST "/replenishment/items/$SID3/approve" "$APP" "{\"expected_version\":$V3,\"comment\":\"探针：准备到货测试单\"}" | jget "d['data']['to_status']")
  [ "$ST3" = "APPROVED" ] && ok "准备：建议 #$SID3 → APPROVED" || bad "准备：审批未通过（$ST3）"
  WH3=$(call GET "/replenishment/items/$SID3" "$APP" | jget "d['data']['warehouse_id']")

  # 幂等键按「被转的建议 ID」固定：同一条建议重复执行不会重复建单；
  # 换一条建议则换键（否则服务端判 COM-0009「幂等键已用于不同的请求内容」）。
  POJSON=$(curl -s --noproxy '*' -X POST "$API/purchasing/orders/from-replenishment" \
    -H "Authorization: Bearer $APP" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: PROBE-R200-PO-$SID3" \
    -d "{\"replenishment_item_ids\":[$SID3],\"warehouse_id\":$WH3,\"remark\":\"R200 到货测试单\"}")
  POID=$(echo "$POJSON" | jget_safe "d['data']['purchase_order_id']")
  PONO=$(echo "$POJSON" | jget_safe "d['data']['po_no']")
  if [ -z "$POID" ]; then
    bad "准备：转采购单失败：$(echo "$POJSON" | jget_safe "d['code']+' '+str(d.get('details'))")"
  else
    ok "准备：生成采购单 $PONO（#POID=$POID，草稿）"
    # ★ 新建采购单的 version 是 0（不是 1）⇒ 必须先从详情读版本再 confirm，
    #   硬编码 expected_version 会直接 409。（界面也是从详情拿 version 的）
    V4=$(call GET "/purchasing/orders/$POID" "$APP" | jget "d['data']['version']")
    ST4=$(call POST "/purchasing/orders/$POID/confirm" "$APP" "{\"expected_version\":$V4}" | jget_safe "d['data']['status']")
    [ "$ST4" = "PENDING" ] && ok "准备：确认采购单 ⇒ PENDING（version=$V4）" || bad "准备：确认未到 PENDING（$ST4）"
    V5=$(call GET "/purchasing/orders/$POID" "$APP" | jget "d['data']['version']")
    ST5=$(call POST "/purchasing/orders/$POID/order" "$APP" "{\"expected_version\":$V5}" | jget_safe "d['data']['status']")
    [ "$ST5" = "ORDERED" ] && ok "准备：采购单已下单 ⇒ ORDERED（界面「登记到货」现在可用了）" || bad "准备：下单未到 ORDERED（$ST5）"
  fi
  fi
fi

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf '\033[32m全部断言通过\033[0m\n'
  exit 0
fi
printf '\033[31m失败 %d 项\033[0m\n' "$FAIL"
exit 1
