// 采购模块 api（M6-T11）。
//
// 权威来源：`backend-java/purchasing/.../api/PurchaseOrderController.java`
//           `backend-java/purchasing/.../api/GoodsReceiptController.java`
// （前缀 `/api/v1/purchasing`，`http.ts` 的 baseURL 已带 `/api/v1`）。
//
// ★ 响应已由 `http.ts` 拦截器解包（`body.data`），本层返回值**就是**业务数据，
//   调用方不得再 `.data.data`；分页字段是 `list`（不是 `items`）。
// ★ `POST /receipts`（登记）与 `POST /receipts/{id}/confirm`（确认到货）**必须**带
//   `Idempotency-Key`（缺失 ⇒ `COM-0010`），统一走 `postWithIdempotency`；
//   其余动作端点服务端未强制幂等，按普通 POST 处理。
// ★ 本层只搬运参数与结果，不计算待收量、不判状态机（服务端是唯一业务边界）。
import http, { postWithIdempotency, type ReplayableResult } from './http'
import type { PageResult } from '@/types/common'
import type {
  CreateGoodsReceiptRequest,
  GoodsReceiptCancelResponse,
  GoodsReceiptDetail,
  GoodsReceiptQuery,
  GoodsReceiptSummary,
  PurchaseOrderActionResponse,
  PurchaseOrderDetail,
  PurchaseOrderQuery,
  PurchaseOrderSummary,
} from '@/types/purchasing'

// ------------------------------------------------------------------ 采购单

/** 采购单列表（`GET /purchasing/orders`；任何已登录成员可看）。 */
export function listPurchaseOrders(
  query: PurchaseOrderQuery,
): Promise<PageResult<PurchaseOrderSummary>> {
  return http.get<unknown, PageResult<PurchaseOrderSummary>>('/purchasing/orders', {
    params: query,
  })
}

/** 采购单详情（`GET /purchasing/orders/{id}`；不存在/跨团队 ⇒ PO-9004）。 */
export function getPurchaseOrder(id: number | string): Promise<PurchaseOrderDetail> {
  return http.get<unknown, PurchaseOrderDetail>(`/purchasing/orders/${id}`)
}

/** A1 确认采购单（`DRAFT → PENDING`；`expected_version` 取自详情响应的 `version`）。 */
export function confirmPurchaseOrder(
  id: number | string,
  expectedVersion: number,
): Promise<PurchaseOrderActionResponse> {
  return http.post<unknown, PurchaseOrderActionResponse>(`/purchasing/orders/${id}/confirm`, {
    expected_version: expectedVersion,
  })
}

/** A2 下单（`PENDING → ORDERED`；`expected_version` 取自详情响应）。 */
export function orderPurchaseOrder(
  id: number | string,
  expectedVersion: number,
): Promise<PurchaseOrderActionResponse> {
  return http.post<unknown, PurchaseOrderActionResponse>(`/purchasing/orders/${id}/order`, {
    expected_version: expectedVersion,
  })
}

/** A3 取消采购单（`reason` 必填 ≤255；缺失/超长 ⇒ COM-0001）。 */
export function cancelPurchaseOrder(
  id: number | string,
  reason: string,
): Promise<PurchaseOrderActionResponse> {
  return http.post<unknown, PurchaseOrderActionResponse>(`/purchasing/orders/${id}/cancel`, {
    reason,
  })
}

// ------------------------------------------------------------------ 到货单

/** 到货单列表（`GET /purchasing/receipts`；任何已登录成员可看）。 */
export function listGoodsReceipts(
  query: GoodsReceiptQuery,
): Promise<PageResult<GoodsReceiptSummary>> {
  return http.get<unknown, PageResult<GoodsReceiptSummary>>('/purchasing/receipts', {
    params: query,
  })
}

/** 到货单详情（`GET /purchasing/receipts/{id}`；不存在/跨团队 ⇒ PO-9005）。 */
export function getGoodsReceipt(id: number | string): Promise<GoodsReceiptDetail> {
  return http.get<unknown, GoodsReceiptDetail>(`/purchasing/receipts/${id}`)
}

/**
 * A1 登记到货（草稿 DRAFT，**必须**带 `Idempotency-Key`）。
 *
 * ★ 一次用户意图一个键（重试复用），见 `utils/idempotency.ts`。
 */
export function createGoodsReceipt(
  payload: CreateGoodsReceiptRequest,
  idempotencyKey: string,
): Promise<ReplayableResult<GoodsReceiptDetail>> {
  return postWithIdempotency<GoodsReceiptDetail>('/purchasing/receipts', payload, idempotencyKey)
}

/**
 * A5 确认到货入库（`DRAFT → CONFIRMED`，触发 `PURCHASE_INBOUND`；**必须**带 `Idempotency-Key`）。
 *
 * ★ 无请求体（`GoodsReceiptController#confirm` 只接 `@PathVariable`）。
 * ★ 超额 ⇒ `PO-9002`（409，带 `details[]`，不产生任何库存变更）。
 */
export function confirmGoodsReceipt(
  id: number | string,
  idempotencyKey: string,
): Promise<ReplayableResult<GoodsReceiptDetail>> {
  return postWithIdempotency<GoodsReceiptDetail>(
    `/purchasing/receipts/${id}/confirm`,
    undefined,
    idempotencyKey,
  )
}

/** A3 作废到货草稿（仅 DRAFT；非 DRAFT ⇒ PO-9008）。 */
export function cancelGoodsReceipt(
  id: number | string,
): Promise<GoodsReceiptCancelResponse> {
  return http.post<unknown, GoodsReceiptCancelResponse>(`/purchasing/receipts/${id}/cancel`)
}
