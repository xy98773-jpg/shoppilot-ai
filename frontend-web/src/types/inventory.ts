// 库存模块 wire 类型（与后端 inventory 模块 DTO 逐字段对齐）。
//
// 权威来源：`backend-java/inventory/.../application/dto/*.java`（@JsonNaming SnakeCase）
//   BalanceView     `GET /inventory/balances`
//   TransactionView `GET /inventory/transactions[/{id}]`
//   AlertView       `GET /inventory/alerts`
//   AdjustmentRequest / AdjustmentResponse / ReverseRequest
//
// ★ 红线（口径铁律 ①②④）：
//   - 数量一律**字符串**（如 `"10.000"`），前端原样展示，不做精确算术；
//   - `available_qty` = `on_hand_qty − committed_qty`（后端生成列，**不含在途**），
//     **不是**补货口径 IP，禁止在此层或视图层用它推算任何补货量。

/** 库存流水类型（`InventoryTxnType`）。 */
export type InventoryTxnType =
  | 'INIT_IMPORT'
  | 'PURCHASE_INBOUND'
  | 'SALE_OUTBOUND'
  | 'COUNT_ADJUST'
  | 'MANUAL_ADJUST'
  | 'REVERSAL'

/** 库存业务类型（`InventoryBizType`）。 */
export type InventoryBizType =
  | 'GOODS_RECEIPT'
  | 'COUNT'
  | 'MANUAL'
  | 'IMPORT'
  | 'SALE'
  | 'REVERSAL'

/** 调整请求的两种模式（同一个端点二合一）。 */
export type AdjustmentMode = 'COUNT_ADJUST' | 'MANUAL_ADJUST'

/** 库存预警类型（`INVENTORY_WORKFLOW §5.7`）。 */
export type AlertType = 'STOCKOUT' | 'LOWSTOCK' | 'SLOWMOVING'

/** 库存余额行（`GET /inventory/balances`）。 */
export interface BalanceView {
  id: number
  warehouse_id: number
  sku_id: number
  sku_code: string | null
  /** 现有库存。 */
  on_hand_qty: string
  /** 原始在途（≠ `effective_in_transit`，不可用于补货口径）。 */
  in_transit_qty: string
  /** 已承诺（已分配未发货）。 */
  committed_qty: string
  /** 可用量 = on_hand − committed（生成列，不含在途）。 */
  available_qty: string
  last_txn_at: string | null
  version: number
}

/** 库存流水（`GET /inventory/transactions` 与 `/{id}`）。 */
export interface TransactionView {
  id: number
  warehouse_id: number
  sku_id: number
  sku_code: string | null
  txn_type: InventoryTxnType
  biz_type: InventoryBizType
  qty_delta: string
  qty_before: string
  qty_after: string
  biz_ref_id: number | null
  biz_ref_no: string | null
  idempotency_key: string
  reason: string | null
  reversed_txn_id: number | null
  operator_id: number | null
  occurred_at: string
  created_at: string
}

/** 库存预警行（无状态、不落库）。 */
export interface AlertView {
  warehouse_id: number
  sku_id: number
  sku_code: string | null
  alert_type: AlertType
  on_hand_qty: string
  available_qty: string
  /** 仅 `LOWSTOCK` 有值（策略 `safety_stock_qty`）。 */
  threshold: string | null
  message: string
}

/**
 * 库存调整请求体（`POST /inventory/adjustments`）。
 *
 * ★ `COUNT_ADJUST` 用 `target_on_hand_qty`；`MANUAL_ADJUST` 用 `qty_delta`（带符号）。
 * ★ 幂等键走请求头 `Idempotency-Key`，不在请求体内。
 */
export interface AdjustmentRequest {
  warehouse_id: number
  sku_id: number
  txn_type: AdjustmentMode
  target_on_hand_qty?: string
  qty_delta?: string
  reason: string
}

/** 库存变更响应（调整 / 冲销共用）。 */
export interface AdjustmentResponse {
  /** 差额为 0 的盘点不写流水，此时为 `null`（`§5.5` 步骤 G）。 */
  transaction_id: number | null
  warehouse_id: number | null
  sku_id: number | null
  txn_type: string
  qty_delta: string
  qty_before: string
  qty_after: string
  balance_version: number | null
  occurred_at: string
}

/** 冲销请求体（`POST /inventory/transactions/{id}/reverse`）。 */
export interface ReverseRequest {
  reason: string
}

/** 库存余额查询参数。 */
export interface BalanceQuery {
  page: number
  pageSize: number
  sort?: string[]
  warehouse_id?: number
  sku_id?: number
}

/** 库存流水查询参数。 */
export interface TransactionQuery {
  page: number
  pageSize: number
  sort?: string[]
  warehouse_id?: number
  sku_id?: number
  txn_type?: string
  dateFrom?: string
  dateTo?: string
}

/** 库存预警查询参数。 */
export interface AlertQuery {
  page: number
  pageSize: number
  sort?: string[]
  warehouse_id?: number
  sku_id?: number
}
