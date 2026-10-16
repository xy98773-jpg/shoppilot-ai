// 主数据（catalog）相关类型（纯类型，不依赖任何其它层）。
//
// ★ 形态权威来源：
//   - `docs/03-api/API_CONTRACT.md §10.3`（catalog 端点清单）、`§11.2`（商品 / SKU 示例）
//   - 后端响应体：`WarehouseView` / `SupplierView` / `ProductView` / `SkuView`
//     （均 `@JsonNaming(SnakeCaseStrategy)`，故字段全为 snake_case）
//   - `§9.4`：**数量与金额以字符串传输**（保留标度），故 `moq` / `order_multiple` /
//     `qty_step` / 单价等为 `string`，不是 `number`。
//
// ★ 两处**非对称**（契约如此，不要"顺手抹平"）：
//   1. `ProductView` **不含 `team_id`**（商品响应未输出），`WarehouseView` / `SupplierView` /
//      `SkuView` **含 `team_id`**。
//   2. 启停端点的**存在性不同**：`warehouses` 与 `skus` 有 `PATCH /{id}/status`；
//      `suppliers` 与 `products` **没有**（不得"补全"）。
import type { PageQuery, PageResult } from './common'

/** 通用主数据状态（语义由服务端定义，前端仅展示）。 */
export type CatalogStatus = 'ACTIVE' | 'INACTIVE' | string

// ---------------------------------------------------------------------------
// 仓库
// ---------------------------------------------------------------------------

/** 仓库（对应后端 `WarehouseView`）。 */
export interface Warehouse {
  id: number
  team_id: number
  code: string
  name: string
  status: CatalogStatus
  /** 是否团队默认仓（同团队至多 1 个，由服务端保证）。 */
  is_default: boolean
  address: string | null
  contact_name: string | null
  contact_phone: string | null
  remark: string | null
  /** ISO-8601 UTC 字符串。 */
  created_at: string
}

/** 新建仓库入参（服务端必填：`code` / `name`）。 */
export interface WarehouseCreateInput {
  code: string
  name: string
  address?: string | null
  contact_name?: string | null
  contact_phone?: string | null
  remark?: string | null
  is_default?: boolean
}

/** 修改仓库入参（PATCH 为真·部分更新：只传要改的字段；`status` 被服务端忽略）。 */
export type WarehouseUpdateInput = Partial<WarehouseCreateInput>

export type WarehousePage = PageResult<Warehouse>
export type WarehouseQuery = PageQuery

// ---------------------------------------------------------------------------
// 供应商
// ---------------------------------------------------------------------------

/** 供应商（对应后端 `SupplierView`）。 */
export interface Supplier {
  id: number
  team_id: number
  code: string
  name: string
  status: CatalogStatus
  contact_name: string | null
  contact_phone: string | null
  email: string | null
  address: string | null
  lead_time_days: number | null
  /** 最小起订量（字符串，保留标度；契约 §9.4）。 */
  moq: string | null
  /** 采购倍数（字符串，保留标度；契约 §9.4）。 */
  order_multiple: string | null
  payment_terms: string | null
  remark: string | null
  created_at: string
}

/** 新建供应商入参（服务端必填：`code` / `name`）。 */
export interface SupplierCreateInput {
  code: string
  name: string
  contact_name?: string | null
  contact_phone?: string | null
  email?: string | null
  address?: string | null
  lead_time_days?: number | null
  moq?: string | null
  order_multiple?: string | null
  payment_terms?: string | null
  remark?: string | null
}

/** 修改供应商入参。 */
export type SupplierUpdateInput = Partial<SupplierCreateInput>

export type SupplierPage = PageResult<Supplier>
export type SupplierQuery = PageQuery

// ---------------------------------------------------------------------------
// 商品
// ---------------------------------------------------------------------------

/** 商品（对应后端 `ProductView`；★ 不含 `team_id`）。 */
export interface Product {
  id: number
  product_code: string
  name: string
  category: string | null
  brand: string | null
  unit: string | null
  status: CatalogStatus
  default_supplier_id: number | null
  /** 规格摘要（展示用；商品的完整规格在 SKU 的 `spec_json`）。 */
  spec_summary: string | null
  created_at: string
}

/** 新建商品入参（服务端必填：`product_code` / `name`）。 */
export interface ProductCreateInput {
  product_code: string
  name: string
  category?: string | null
  brand?: string | null
  unit?: string | null
  default_supplier_id?: number | null
  spec_summary?: string | null
  remark?: string | null
}

/** 修改商品入参。 */
export type ProductUpdateInput = Partial<ProductCreateInput>

export type ProductPage = PageResult<Product>

/** 商品列表筛选入参（契约 §10.3：可按 `category` / `status` 筛选）。 */
export interface ProductQuery extends PageQuery {
  category?: string
  status?: string
}

// ---------------------------------------------------------------------------
// SKU
// ---------------------------------------------------------------------------

/** SKU（对应后端 `SkuView`；★ 含 `team_id`）。 */
export interface Sku {
  id: number
  team_id: number
  product_id: number
  sku_code: string
  name: string
  status: CatalogStatus
  supplier_id: number | null
  default_lead_time_days: number | null
  /** 最小起订量（字符串，保留标度）。 */
  moq: string | null
  /** 采购倍数（字符串，保留标度）。 */
  order_multiple: string | null
  /** 数量步长（字符串，保留标度）。 */
  qty_step: string | null
  /** 上架日期（`yyyy-MM-dd`）。 */
  listed_date: string | null
  created_at: string
}

/** 新建 SKU 入参（服务端必填：`sku_code` / `name`；`product_id` 由路径或请求体给定）。 */
export interface SkuCreateInput {
  /** 独立 `POST /skus` 时必填；`POST /products/{id}/skus` 由路径给定（二者不可不一致 → CAT-3004）。 */
  product_id?: number
  sku_code: string
  name: string
  barcode?: string | null
  /** 规格键值对（服务端按 JSON 文本落库）。 */
  spec_json?: Record<string, unknown> | null
  supplier_id?: number | null
  default_lead_time_days?: number | null
  moq?: string | null
  order_multiple?: string | null
  qty_step?: string | null
  purchase_price?: string | null
  sale_price?: string | null
  weight_gram?: string | null
  /** 上架日期（`yyyy-MM-dd`）。 */
  listed_date?: string | null
}

/** 修改 SKU 入参（★ `product_id` 不可变：请求体中的 `product_id` 被服务端忽略）。 */
export type SkuUpdateInput = Partial<Omit<SkuCreateInput, 'product_id'>>

export type SkuPage = PageResult<Sku>

/** SKU 列表筛选入参（契约 §10.3：可按 `productId` / `status` / `keyword` 筛选）。 */
export interface SkuQuery extends PageQuery {
  /** ★ 注意 wire 参数名为 camelCase 的 `productId`（后端 `@RequestParam(name = "productId")`）。 */
  productId?: number
  status?: string
  keyword?: string
}

/** 商品下 SKU 列表入参（契约 §10.3 `GET /products/{id}/skus`：仅分页/排序）。 */
export type ProductSkusQuery = PageQuery
