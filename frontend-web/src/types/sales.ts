// 销售模块 wire 类型（与后端 sales 模块 DTO 逐字段对齐）。
//
// 权威来源：`backend-java/sales/.../application/dto/SalesDailyResponse.java`（@JsonNaming SnakeCase）
// 与 `SalesController#listDaily` 的查询参数。
//
// ★ 红线（口径铁律 ④）：`qty` / `return_qty` / `sales_amount` 均为**字符串**
//   （如 `"12.000"` / `"1080.00"`），前端原样展示，不做精确算术、不重算净销量。
// ★ 本批只做**只读**列表：销售数据经导入进入（PRD F-SAL-01），不做录入表单。

/** 日销售行（`GET /sales/daily`）。 */
export interface SalesDailyRow {
  id: number
  warehouse_id: number
  sku_id: number
  sku_code: string | null
  product_id: number | null
  /** 业务日期 `yyyy-MM-dd`（后端 `@JsonFormat` 固定）。 */
  sale_date: string
  /** 净销售数量（字符串，原样展示）。 */
  qty: string
  return_qty: string
  /** 销售金额（可空）。 */
  sales_amount: string | null
  order_count: number | null
  /** `MANUAL` / `IMPORT`（来源）。 */
  source: string
  batch_id: number | null
  created_at: string
  updated_at: string
}

/** 日销售查询参数（`GET /sales/daily`）。 */
export interface SalesDailyQuery {
  page: number
  pageSize: number
  sort?: string[]
  sku_id?: number
  warehouse_id?: number
  /** 起止日期 `yyyy-MM-dd`（后端参数名为 dateFrom / dateTo，非 snake_case）。 */
  dateFrom?: string
  dateTo?: string
  /** 不传 = 原始行；`sku|warehouse|date` = 维度聚合。本批只用原始行。 */
  groupBy?: string
}
