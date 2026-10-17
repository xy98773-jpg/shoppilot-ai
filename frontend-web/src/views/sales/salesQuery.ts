// 销售列表的查询参数装配（纯函数，无副作用）。
//
// ★ 后端参数名是 `dateFrom` / `dateTo`（驼峰），**不是** snake_case——逐字对齐
//   `SalesController#listDaily`，不要"顺手"改成 `date_from`（会静默失效）。
// ★ 只搬运参数，不重算净销量/金额：金额与数量一律原样展示（口径铁律 ④）。
import type { SalesDailyQuery } from '@/types/sales'

/** 销售列表筛选控件状态。 */
export interface SalesFilterInput {
  sku_id?: string | number
  warehouse_id?: string | number
  dateFrom?: string
  dateTo?: string
}

/** 正整数解析：空/非数字/非正 → undefined（不把脏值发给后端）。 */
export function toPositiveInt(value?: string | number): number | undefined {
  if (value === undefined || value === null || value === '') {
    return undefined
  }
  const n = typeof value === 'number' ? value : Number(value)
  return Number.isFinite(n) && n > 0 ? Math.trunc(n) : undefined
}

/** 构造 `GET /sales/daily` 查询参数（空筛选不传）。 */
export function buildSalesQuery(
  input: SalesFilterInput,
  page: number,
  pageSize: number,
): SalesDailyQuery {
  const query: SalesDailyQuery = { page, pageSize }
  const skuId = toPositiveInt(input.sku_id)
  const warehouseId = toPositiveInt(input.warehouse_id)
  if (skuId !== undefined) {
    query.sku_id = skuId
  }
  if (warehouseId !== undefined) {
    query.warehouse_id = warehouseId
  }
  if (input.dateFrom) {
    query.dateFrom = input.dateFrom
  }
  if (input.dateTo) {
    query.dateTo = input.dateTo
  }
  return query
}
