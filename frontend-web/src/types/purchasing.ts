// 采购模块 wire 类型（与后端 purchasing 模块 DTO 逐字段对齐）。
//
// 权威来源：`backend-java/purchasing/.../application/dto/*.java`（@JsonNaming SnakeCase）
//   PurchaseOrderSummary / PurchaseOrderDetail  `GET /purchasing/orders[/{id}]`
//   GoodsReceiptSummary  / GoodsReceiptDetail   `GET /purchasing/receipts[/{id}]`
//   PurchaseOrderActionRequest / PurchaseOrderCancelRequest
//   CreateGoodsReceiptRequest / GoodsReceiptItemRequest
//
// ★ 口径铁律（与 inventory 模块一致）：
//   - 数量（scale=3）、单价（scale=4）、金额（scale=2）一律**字符串**（如 `"10.000"`），
//     视图层原样展示，❌ 不做 `Number()` / `parseFloat()` 换算（会丢精度）；
//   - 状态是两个**独立**值空间：采购单（6 值）≠ 到货单（4 值），不得互串。

/** 采购单状态（`PurchaseOrderStatus`，与迁移 CHECK 约束逐字一致）。 */
export type PurchaseOrderStatus =
  | 'DRAFT'
  | 'PENDING'
  | 'ORDERED'
  | 'PARTIALLY_RECEIVED'
  | 'RECEIVED'
  | 'CANCELLED'

/** 采购单来源（`PurchaseOrderSource`）。 */
export type PurchaseOrderSource = 'MANUAL' | 'REPLENISHMENT'

/** 到货单状态（`GoodsReceiptStatus`）。 */
export type GoodsReceiptStatus = 'DRAFT' | 'CONFIRMED' | 'REVERSED' | 'CANCELLED'

/** 采购单列表元素（`GET /purchasing/orders` 的 `list[]`；**不含** `items[]`）。 */
export interface PurchaseOrderSummary {
  purchase_order_id: number
  po_no: string
  status: PurchaseOrderStatus
  source: PurchaseOrderSource
  supplier_id: number | null
  warehouse_id: number
  expected_arrival_date: string | null
  total_qty: string
  received_qty: string
  total_amount: string
  remark: string | null
  version: number
  created_at: string
}

/** 采购单明细行（详情 `items[]`；`id` 即 `purchase_order_item_id`）。 */
export interface PurchaseOrderItem {
  id: number
  line_no: number
  sku_id: number
  product_id: number | null
  replenishment_item_id: number | null
  ordered_qty: string
  received_qty: string
  unit_price: string
  amount: string
  expected_arrival_date: string | null
}

/** 采购单详情（`GET /purchasing/orders/{id}`）。 */
export interface PurchaseOrderDetail {
  purchase_order_id: number
  po_no: string
  status: PurchaseOrderStatus
  source: PurchaseOrderSource
  supplier_id: number | null
  warehouse_id: number
  expected_arrival_date: string | null
  total_qty: string
  total_amount: string
  received_qty: string
  remark: string | null
  confirmed_by: number | null
  confirmed_at: string | null
  ordered_at: string | null
  cancelled_at: string | null
  cancel_reason: string | null
  closed_at: string | null
  idempotency_key: string | null
  version: number
  created_at: string
  items: PurchaseOrderItem[]
}

/** 采购单状态动作响应（confirm / order / cancel 共用形态，未涉及字段不出现）。 */
export interface PurchaseOrderActionResponse {
  purchase_order_id: number
  po_no: string
  status: PurchaseOrderStatus
  confirmed_by?: number
  confirmed_at?: string
  ordered_at?: string
  cancelled_at?: string
  cancel_reason?: string
  version: number
}

/** 采购单列表筛选参数（`PurchaseOrderController#list`）。 */
export interface PurchaseOrderQuery {
  status?: PurchaseOrderStatus
  supplier_id?: number
  warehouse_id?: number
  po_no?: string
  page: number
  pageSize: number
}

/** 到货单列表元素（`GET /purchasing/receipts` 的 `list[]`；**不含** `items[]`）。 */
export interface GoodsReceiptSummary {
  goods_receipt_id: number
  receipt_no: string
  purchase_order_id: number
  warehouse_id: number
  status: GoodsReceiptStatus
  receipt_date: string
  total_qty: string
  total_amount: string
  remark: string | null
  version: number
  created_at: string
}

/** 到货明细行（详情 `items[]`）。 */
export interface GoodsReceiptItem {
  goods_receipt_item_id: number
  purchase_order_item_id: number
  sku_id: number
  received_qty: string
  unit_price: string
  amount: string
  batch_no: string | null
  remark: string | null
}

/** 到货单详情（`GET /purchasing/receipts/{id}`）。 */
export interface GoodsReceiptDetail {
  goods_receipt_id: number
  receipt_no: string
  purchase_order_id: number
  warehouse_id: number
  status: GoodsReceiptStatus
  receipt_date: string
  total_qty: string
  total_amount: string
  remark: string | null
  version: number
  items: GoodsReceiptItem[]
  created_at: string
  updated_at: string | null
  cancelled_at: string | null
  confirmed_at: string | null
  confirmed_by: number | null
}

/** 到货单列表筛选参数（`GoodsReceiptController#list`）。 */
export interface GoodsReceiptQuery {
  purchase_order_id?: number
  status?: GoodsReceiptStatus
  warehouse_id?: number
  receipt_no?: string
  page: number
  pageSize: number
}

/** 到货登记明细行（`GoodsReceiptItemRequest`；`sku_id` / `amount` 由服务端派生）。 */
export interface CreateGoodsReceiptItem {
  purchase_order_item_id: number
  received_qty: string
  unit_price: string
  batch_no?: string
  remark?: string
}

/** 到货登记请求（`CreateGoodsReceiptRequest`；状态/汇总/单号由服务端派生）。 */
export interface CreateGoodsReceiptRequest {
  purchase_order_id: number
  warehouse_id: number
  receipt_date: string
  remark?: string
  items: CreateGoodsReceiptItem[]
}

/** 到货作废响应（`GoodsReceiptCancelResponse`）。 */
export interface GoodsReceiptCancelResponse {
  goods_receipt_id: number
  receipt_no: string
  status: GoodsReceiptStatus
  cancelled_at: string
  version: number
}
