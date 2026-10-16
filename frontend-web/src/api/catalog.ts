// 主数据（catalog）模块——契约 `API_CONTRACT §10.3` 的 **全部 24 行端点**。
//
// ★ 红线：api 层不 import stores / 不在 views 里直接 import axios（PROJECT_STRUCTURE §6.1）。
// ★ 端点存在性（★ 不得"补全"）：
//   - `warehouses` / `skus` 有 `PATCH /{id}/status`（显式启停）；
//   - `suppliers` / `products` **没有**（契约 §10.3 未定义）。
// ★ PATCH 为**真·部分更新**：只传要改的字段；请求体里的 `status` 被服务端忽略
//   （状态流转只走 DELETE 或 /status，防止绕过前置校验）。
import http from './http'
import type { PageResult } from '@/types/common'
import type {
  CatalogStatus,
  Product,
  ProductCreateInput,
  ProductQuery,
  ProductSkusQuery,
  ProductUpdateInput,
  Sku,
  SkuCreateInput,
  SkuQuery,
  SkuUpdateInput,
  Supplier,
  SupplierCreateInput,
  SupplierQuery,
  SupplierUpdateInput,
  Warehouse,
  WarehouseCreateInput,
  WarehouseQuery,
  WarehouseUpdateInput,
} from '@/types/catalog'

// ===========================================================================
// 仓库（§10.3：6 行）
// ===========================================================================

/** 仓库列表（分页；任何已登录成员）。 */
export function listWarehouses(query: WarehouseQuery): Promise<PageResult<Warehouse>> {
  return http.get<unknown, PageResult<Warehouse>>('/warehouses', { params: query })
}

/** 新建仓库（OPERATOR+）→ 201。 */
export function createWarehouse(payload: WarehouseCreateInput): Promise<Warehouse> {
  return http.post<unknown, Warehouse>('/warehouses', payload)
}

/** 仓库详情（任何已登录成员；异团队 403 / 不存在 404 `CAT-3003`）。 */
export function getWarehouse(id: number): Promise<Warehouse> {
  return http.get<unknown, Warehouse>(`/warehouses/${id}`)
}

/** 修改仓库（OPERATOR+）→ 200。 */
export function updateWarehouse(id: number, payload: WarehouseUpdateInput): Promise<Warehouse> {
  return http.patch<unknown, Warehouse>(`/warehouses/${id}`, payload)
}

/** 停用仓库（软删 → `INACTIVE`，幂等；OPERATOR+）→ 200。 */
export function deactivateWarehouse(id: number): Promise<Warehouse> {
  return http.delete<unknown, Warehouse>(`/warehouses/${id}`)
}

/** 显式改仓库状态（`ACTIVE`/`INACTIVE`，含重新启用；OPERATOR+）→ 200。 */
export function changeWarehouseStatus(id: number, status: CatalogStatus): Promise<Warehouse> {
  return http.patch<unknown, Warehouse>(`/warehouses/${id}/status`, { status })
}

// ===========================================================================
// 供应商（§10.3：5 行；★ 无 /status 端点）
// ===========================================================================

/** 供应商列表（分页；任何已登录成员）。 */
export function listSuppliers(query: SupplierQuery): Promise<PageResult<Supplier>> {
  return http.get<unknown, PageResult<Supplier>>('/suppliers', { params: query })
}

/** 新建供应商（OPERATOR+）→ 201。 */
export function createSupplier(payload: SupplierCreateInput): Promise<Supplier> {
  return http.post<unknown, Supplier>('/suppliers', payload)
}

/** 供应商详情（任何已登录成员；异团队 403 / 不存在 404 `CAT-3001`）。 */
export function getSupplier(id: number): Promise<Supplier> {
  return http.get<unknown, Supplier>(`/suppliers/${id}`)
}

/** 修改供应商（OPERATOR+）→ 200。 */
export function updateSupplier(id: number, payload: SupplierUpdateInput): Promise<Supplier> {
  return http.patch<unknown, Supplier>(`/suppliers/${id}`, payload)
}

/** 停用供应商（软删 → `INACTIVE`，幂等；OPERATOR+）→ 200。★ 无对应"启用"端点。 */
export function deactivateSupplier(id: number): Promise<Supplier> {
  return http.delete<unknown, Supplier>(`/suppliers/${id}`)
}

// ===========================================================================
// 商品（§10.3：7 行，含商品下 SKU 子资源；★ 无 /status 端点）
// ===========================================================================

/** 商品列表（分页，可按 `category`/`status` 筛选；任何已登录成员）。 */
export function listProducts(query: ProductQuery): Promise<PageResult<Product>> {
  return http.get<unknown, PageResult<Product>>('/products', { params: query })
}

/** 新建商品（OPERATOR+）→ 201。 */
export function createProduct(payload: ProductCreateInput): Promise<Product> {
  return http.post<unknown, Product>('/products', payload)
}

/** 商品详情（任何已登录成员；异团队 403 / 不存在 404 `CAT-3001`）。 */
export function getProduct(id: number): Promise<Product> {
  return http.get<unknown, Product>(`/products/${id}`)
}

/** 修改商品（OPERATOR+）→ 200。 */
export function updateProduct(id: number, payload: ProductUpdateInput): Promise<Product> {
  return http.patch<unknown, Product>(`/products/${id}`, payload)
}

/** 停用商品（软删 → `INACTIVE`；商品下有 ACTIVE SKU 时 422 `CAT-3009`；OPERATOR+）→ 200。 */
export function deactivateProduct(id: number): Promise<Product> {
  return http.delete<unknown, Product>(`/products/${id}`)
}

/** 商品下 SKU 列表（分页；任何已登录成员；商品不存在 404 `CAT-3001`）。 */
export function listProductSkus(
  productId: number,
  query: ProductSkusQuery,
): Promise<PageResult<Sku>> {
  return http.get<unknown, PageResult<Sku>>(`/products/${productId}/skus`, { params: query })
}

/** 在指定商品下新建 SKU（OPERATOR+）→ 201。 */
export function createProductSku(productId: number, payload: SkuCreateInput): Promise<Sku> {
  return http.post<unknown, Sku>(`/products/${productId}/skus`, payload)
}

// ===========================================================================
// SKU（§10.3：6 行）
// ===========================================================================

/** SKU 列表（分页，可按 `productId`/`status`/`keyword` 筛选；任何已登录成员）。 */
export function listSkus(query: SkuQuery): Promise<PageResult<Sku>> {
  return http.get<unknown, PageResult<Sku>>('/skus', { params: query })
}

/** 新建 SKU（OPERATOR+；请求体须带 `product_id`）→ 201。 */
export function createSku(payload: SkuCreateInput): Promise<Sku> {
  return http.post<unknown, Sku>('/skus', payload)
}

/** SKU 详情（任何已登录成员；异团队 403 / 不存在 404 `CAT-3001`）。 */
export function getSku(id: number): Promise<Sku> {
  return http.get<unknown, Sku>(`/skus/${id}`)
}

/** 修改 SKU（OPERATOR+；采购属性/交期/MOQ 等）→ 200。 */
export function updateSku(id: number, payload: SkuUpdateInput): Promise<Sku> {
  return http.patch<unknown, Sku>(`/skus/${id}`, payload)
}

/** 停用 SKU（软删 → `INACTIVE`，幂等；OPERATOR+）→ 200。 */
export function deactivateSku(id: number): Promise<Sku> {
  return http.delete<unknown, Sku>(`/skus/${id}`)
}

/** 显式改 SKU 状态（`ACTIVE`/`INACTIVE`，含重新启用；OPERATOR+）→ 200。 */
export function changeSkuStatus(id: number, status: CatalogStatus): Promise<Sku> {
  return http.patch<unknown, Sku>(`/skus/${id}/status`, { status })
}
