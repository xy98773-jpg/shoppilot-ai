// 采购模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'

// TODO: 采购订单、收货相关接口。
export function listPurchaseOrders(query: PageQuery): Promise<PageResult<unknown>> {
  return http.get<unknown, PageResult<unknown>>('/purchasing/orders', { params: query })
}
