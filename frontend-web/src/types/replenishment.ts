// 补货模块 wire 类型（与后端 replenishment 模块 DTO 逐字段对齐）。
//
// 权威来源（已逐字段核对，勿凭记忆改）：
//   `backend-java/replenishment/.../api/ReplenishmentItemController.java`
//   `backend-java/replenishment/.../application/dto/ReplenishmentItemResponse.java`
//   `.../dto/ReplenishmentReviewResponse.java` / `.../dto/ReplenishmentReviewActionResponse.java`
//   `.../domain/ReviewAction.java`
//
// 端点：
//   GET  /api/v1/replenishment/items                 列表（分页字段 `list`）
//   GET  /api/v1/replenishment/items/{id}            详情
//   GET  /api/v1/replenishment/items/{id}/reviews    审核时间线
//   PATCH/api/v1/replenishment/items/{id}            人工改量（`version` 必填）
//   POST /api/v1/replenishment/items/{id}/submit|withdraw|approve|reject|cancel
//
// ★ 数量 `suggested_qty`/`qty` 固定 scale=3、`purchase_price` 固定 scale=4，**一律字符串**
//   原样展示（口径铁律 ④），❌ 不做 `Number()` 换算。
// ★ `version` 是 number（乐观锁令牌），原样回传给 `PATCH` / `approve` / `reject`。
// ★ `@JsonInclude(NON_NULL)` ⇒ 空值字段**不会出现**在响应里，故可选字段统一 `?`。

/** 补货建议状态（`ReplenishmentItemStatus`，7 值；与迁移 CHECK 逐字一致）。 */
export type ReplenishmentStatus =
  | 'SUGGESTED'
  | 'PENDING_REVIEW'
  | 'APPROVED'
  | 'CONVERTED'
  | 'REJECTED'
  | 'SUPERSEDED'
  | 'CANCELLED'

/** 审核动作（`ReviewAction`，5 值）。 */
export type ReviewAction = 'SUBMIT' | 'WITHDRAW' | 'APPROVE' | 'REJECT' | 'CANCEL'

/** 风险等级（`RiskLevel`，服务端筛选用，5 值白名单）。 */
export type RiskLevel = 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE' | 'UNKNOWN'

/**
 * 建议列表/详情元素（`GET /replenishment/items` 的 `list[]`）。
 *
 * ★ 服务端**不返回** `sku_code` / `sku_name` / `warehouse_name`（不跨模块 JOIN），
 *   页面只能展示 ID；要显示名称需另行查主数据（本页不做，避免 N+1）。
 */
export interface ReplenishmentItem {
  id: number
  source_analysis_item_id: number
  run_id: number
  sku_id: number
  product_id?: number | null
  warehouse_id: number
  status: ReplenishmentStatus
  /** 规则引擎算出的基础补货量（scale=3 字符串）。 */
  suggested_qty: string
  /** 当前生效数量（可能被人工改过；scale=3 字符串）。 */
  qty: string
  risk_level: string
  reason?: string | null
  supplier_id?: number | null
  purchase_price?: string | null
  expected_arrival_date?: string | null
  /** 已生成的采购明细 ID（逗号分隔字符串，`CONVERTED` 时才有值）。 */
  converted_po_item_ids?: string | null
  converted_at?: string | null
  /** 被哪条新建议取代（`SUPERSEDED` 时才有值）。 */
  superseded_by_item_id?: number | null
  submitted_at?: string | null
  approved_at?: string | null
  approved_by?: number | null
  closed_at?: string | null
  remark?: string | null
  created_at: string
  updated_at?: string | null
  created_by?: number | null
  updated_by?: number | null
  /** 乐观锁版本（number，非字符串）。 */
  version: number
}

/** 列表筛选参数（服务端白名单：status 7 值 / risk_level 5 值 / run_id / page / pageSize / sort）。 */
export interface ReplenishmentQuery {
  status?: ReplenishmentStatus
  risk_level?: RiskLevel
  run_id?: number
  page: number
  pageSize: number
  sort?: string
}

/** 人工改量请求（`PATCH /items/{id}`；`version` 必填，未传字段即「不修改」）。 */
export interface UpdateReplenishmentItemRequest {
  version: number
  qty?: string
  supplier_id?: number
  expected_arrival_date?: string
  remark?: string
}

/** 审核时间线条目（`GET /items/{id}/reviews` 的 `list[]`）。 */
export interface ReplenishmentReview {
  id: number
  replenishment_item_id: number
  action: ReviewAction
  from_status: string
  to_status: string
  comment?: string | null
  /** 改量前数量（scale=3 字符串；仅改量动作有值）。 */
  qty_before?: string | null
  qty_after?: string | null
  reviewer_id?: number | null
  reviewer_role?: string | null
  request_id?: string | null
  occurred_at: string
  created_at?: string | null
  created_by?: number | null
}

/**
 * 状态命令响应（submit / withdraw / approve / reject / cancel 共用形态）。
 *
 * ★ `@JsonInclude(NON_NULL)`：`approved_*` 仅 approve 有值、`closed_at` 仅 reject 有值。
 */
export interface ReplenishmentActionResponse {
  replenishment_item_id: number
  status: ReplenishmentStatus
  qty: string
  version: number
  review_id: number
  action: ReviewAction
  from_status: string
  to_status: string
  submitted_at?: string | null
  approved_by?: number | null
  approved_at?: string | null
  closed_at?: string | null
}

/** 由补货建议生成采购单的请求体（数量/单价/供应商一律取自建议，不可覆盖）。 */
export interface CreatePurchaseOrderFromReplenishmentRequest {
  replenishment_item_ids: number[]
  warehouse_id: number
  expected_arrival_date?: string | null
  remark?: string | null
  conflict_acknowledged?: boolean
  acknowledged_item_ids?: number[]
}

/** 由建议生成采购单的响应（`POST /purchasing/orders/from-replenishment`，201）。 */
export interface PurchaseOrderFromReplenishmentResponse {
  purchase_order_id: number
  po_no: string
  status: string
  source: string
  supplier_id: number | null
  warehouse_id: number
  expected_arrival_date: string | null
  total_qty: string
  total_amount: string
  items: Array<{
    line_no: number
    sku_id: number
    replenishment_item_id: number | null
    ordered_qty: string
    unit_price: string
    amount: string
  }>
}

/**
 * `RPL-8012` 冲突明细（放在 `details[]`，`field = conflict_items`，`rejectedValue` 为载体）。
 *
 * ★ 后端 `conflictError()` 把 `{replenishment_item_id, sku_id, qty, risk_level, created_at}`
 *   塞进 `ApiErrorDetail.rejectedValue`（沿用 R153「冲突清单放 details」的裁定）。
 */
export interface ReplenishmentConflictItem {
  replenishment_item_id: number
  sku_id: number
  qty: string
  risk_level: string
  created_at: string | null
}
