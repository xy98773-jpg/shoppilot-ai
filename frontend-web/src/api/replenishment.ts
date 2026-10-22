// 补货模块 api（M8-R200：补齐界面所需读写端点）。
//
// 权威来源：`backend-java/replenishment/.../api/ReplenishmentItemController.java`
// （前缀 `/api/v1/replenishment`，`http.ts` 的 baseURL 已带 `/api/v1`）。
//
// ★ 响应已由 `http.ts` 解包，本层返回值就是业务数据；分页字段是 `list`。
// ★ 红线：补货量、风险判定等**业务规则在服务端**，前端只搬运参数与结果；
//   状态机同样以服务端为准，前端仅按当前状态决定显示哪些按钮（体验层）。
// ★ 各动作的**权限口径**（决定按钮显隐，服务端仍是唯一安全边界）：
//   submit / withdraw / cancel  → OWNER, ADMIN, OPERATOR, APPROVER
//   approve / reject            → OWNER, ADMIN, APPROVER（**不含 OPERATOR**）
import http from './http'
import type { PageResult } from '@/types/common'
import type {
  ReplenishmentActionResponse,
  ReplenishmentItem,
  ReplenishmentQuery,
  ReplenishmentReview,
  UpdateReplenishmentItemRequest,
} from '@/types/replenishment'

/** 建议列表（`GET /replenishment/items`；任何已登录成员，含 VIEWER）。 */
export function listReplenishmentItems(
  query: ReplenishmentQuery,
): Promise<PageResult<ReplenishmentItem>> {
  return http.get<unknown, PageResult<ReplenishmentItem>>('/replenishment/items', {
    params: query,
  })
}

/** 建议详情（`GET /replenishment/items/{id}`；不存在/跨团队 ⇒ 404 `RPL-8004`）。 */
export function getReplenishmentItem(id: number | string): Promise<ReplenishmentItem> {
  return http.get<unknown, ReplenishmentItem>(`/replenishment/items/${id}`)
}

/** 审核时间线（`GET /replenishment/items/{id}/reviews`；默认 `occurred_at asc`）。 */
export function listReplenishmentReviews(
  id: number | string,
): Promise<PageResult<ReplenishmentReview>> {
  return http.get<unknown, PageResult<ReplenishmentReview>>(`/replenishment/items/${id}/reviews`, {
    params: { page: 1, pageSize: 100, sort: 'occurred_at,asc' },
  })
}

/** 人工改量（`PATCH /replenishment/items/{id}`；`version` 必填，缺失 ⇒ `COM-0001`）。 */
export function updateReplenishmentItem(
  id: number | string,
  payload: UpdateReplenishmentItemRequest,
): Promise<ReplenishmentItem> {
  return http.patch<unknown, ReplenishmentItem>(`/replenishment/items/${id}`, payload)
}

/**
 * 提交审核（`SUGGESTED → PENDING_REVIEW`；请求体**可空**）。
 *
 * ★ 请求体字段名是 `comment`（不是 `reason`）；超 500 字符 ⇒ `COM-0001`。
 */
export function submitReplenishment(
  id: number | string,
  comment?: string,
): Promise<ReplenishmentActionResponse> {
  return http.post<unknown, ReplenishmentActionResponse>(
    `/replenishment/items/${id}/submit`,
    comment ? { comment } : {},
  )
}

/** 撤回提交（`PENDING_REVIEW → SUGGESTED`；★ `submitted_at` 不清空）。 */
export function withdrawReplenishment(
  id: number | string,
  comment?: string,
): Promise<ReplenishmentActionResponse> {
  return http.post<unknown, ReplenishmentActionResponse>(
    `/replenishment/items/${id}/withdraw`,
    comment ? { comment } : {},
  )
}

/**
 * 审批通过（`PENDING_REVIEW → APPROVED`；`expected_version` 必填）。
 *
 * ★ 版本冲突 ⇒ 409 `RPL-8003`（刷新后重试即可）；已审批 ⇒ 409 `RPL-8002`。
 */
export function approveReplenishment(
  id: number | string,
  expectedVersion: number,
  comment?: string,
): Promise<ReplenishmentActionResponse> {
  return http.post<unknown, ReplenishmentActionResponse>(
    `/replenishment/items/${id}/approve`,
    { expected_version: expectedVersion, ...(comment ? { comment } : {}) },
  )
}

/** 驳回（`PENDING_REVIEW → REJECTED`，终态；`comment` 必填非空白，空 ⇒ `RPL-8005`）。 */
export function rejectReplenishment(
  id: number | string,
  expectedVersion: number,
  comment: string,
): Promise<ReplenishmentActionResponse> {
  return http.post<unknown, ReplenishmentActionResponse>(
    `/replenishment/items/${id}/reject`,
    { expected_version: expectedVersion, comment },
  )
}

/**
 * 作废（`SUGGESTED|APPROVED → CANCELLED`）。
 *
 * ★ 合法来源状态**只有** `SUGGESTED` 与 `APPROVED`；`PENDING_REVIEW` 须先 `withdraw`
 *   否则 422 `RPL-8001`（提示「请先撤回再作废」）。
 * ★ 不要求 `expected_version`；请求体字段名是 `comment`（不是 `reason`）。
 */
export function cancelReplenishment(
  id: number | string,
  comment?: string,
): Promise<ReplenishmentActionResponse> {
  return http.post<unknown, ReplenishmentActionResponse>(
    `/replenishment/items/${id}/cancel`,
    comment ? { comment } : {},
  )
}
