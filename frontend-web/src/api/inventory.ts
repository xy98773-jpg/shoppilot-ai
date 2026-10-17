// 库存模块 api（M3-B10）。
//
// 权威来源：`backend-java/inventory/.../api/InventoryController.java`
// （前缀 `/api/v1/inventory`，`http.ts` 已带 `/api/v1`）。
//
// ★ 写操作只有两个：`POST /adjustments`（盘点 / 手工二合一）与 `POST /transactions/{id}/reverse`；
//   两者都**必须**带 `Idempotency-Key`（缺失 → 400 COM-0001），统一走 `postWithIdempotency`。
// ★ 前端不计算 IP、不判状态机：本层只搬运参数与结果。
import http, { postWithIdempotency, type ReplayableResult } from './http'
import type { PageResult } from '@/types/common'
import type {
  AdjustmentRequest,
  AdjustmentResponse,
  AlertQuery,
  AlertView,
  BalanceQuery,
  BalanceView,
  TransactionQuery,
  TransactionView,
} from '@/types/inventory'

/** 库存余额列表（`GET /inventory/balances`）。 */
export function listBalances(query: BalanceQuery): Promise<PageResult<BalanceView>> {
  return http.get<unknown, PageResult<BalanceView>>('/inventory/balances', { params: query })
}

/** 库存流水列表（`GET /inventory/transactions`）。 */
export function listTransactions(query: TransactionQuery): Promise<PageResult<TransactionView>> {
  return http.get<unknown, PageResult<TransactionView>>('/inventory/transactions', {
    params: query,
  })
}

/** 单笔流水详情（`GET /inventory/transactions/{id}`；不存在/异团队 → INV-5008）。 */
export function getTransaction(id: number): Promise<TransactionView> {
  return http.get<unknown, TransactionView>(`/inventory/transactions/${id}`)
}

/**
 * 库存调整（`POST /inventory/adjustments`，必须带 `Idempotency-Key`）。
 *
 * ★ `COUNT_ADJUST` 传 `target_on_hand_qty`；`MANUAL_ADJUST` 传 `qty_delta`（带符号）。
 * ★ 差额为 0 的盘点返回 `transaction_id: null` 且**不带** `Idempotent-Replayed`
 *   （`§5.5` 步骤 G；R74 已裁定「无变更成功」不是重放）。
 */
export function adjustInventory(
  payload: AdjustmentRequest,
  idempotencyKey: string,
): Promise<ReplayableResult<AdjustmentResponse>> {
  return postWithIdempotency<AdjustmentResponse>(
    '/inventory/adjustments',
    payload,
    idempotencyKey,
  )
}

/** 冲销某笔流水（`POST /inventory/transactions/{id}/reverse`，必须带 `Idempotency-Key`）。 */
export function reverseTransaction(
  id: number,
  reason: string,
  idempotencyKey: string,
): Promise<ReplayableResult<AdjustmentResponse>> {
  return postWithIdempotency<AdjustmentResponse>(
    `/inventory/transactions/${id}/reverse`,
    { reason },
    idempotencyKey,
  )
}

/** 库存预警列表（`GET /inventory/alerts`，只读、无状态）。 */
export function listAlerts(query: AlertQuery): Promise<PageResult<AlertView>> {
  return http.get<unknown, PageResult<AlertView>>('/inventory/alerts', { params: query })
}
