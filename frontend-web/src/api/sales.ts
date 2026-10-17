// 销售模块 api（M3-B10）。
//
// 权威来源：`backend-java/sales/.../api/SalesController.java`（前缀 `/api/v1/sales`）。
//
// ★ 本批只实现**只读**列表（F-SAL-02）：销售数据经导入进入（PRD F-SAL-01），
//   按任务书「本批不做录入表单」，故不暴露 `POST /sales/daily`。
// ★ 后端还支持 `groupBy=sku|warehouse|date` 的维度聚合（响应为 `SalesGroupView`）；
//   本批列表只展示原始行，聚合能力待后续批次接图表时再接（接口参数已保留）。
import http from './http'
import type { PageResult } from '@/types/common'
import type { SalesDailyQuery, SalesDailyRow } from '@/types/sales'

/** 日销售列表（`GET /sales/daily`，无 `groupBy` 时返回原始行）。 */
export function listSalesDaily(query: SalesDailyQuery): Promise<PageResult<SalesDailyRow>> {
  return http.get<unknown, PageResult<SalesDailyRow>>('/sales/daily', { params: query })
}
