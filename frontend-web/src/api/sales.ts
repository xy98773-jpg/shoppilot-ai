// 销售模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'

// TODO: 销售记录相关接口。
export function listSales(query: PageQuery): Promise<PageResult<unknown>> {
  return http.get<unknown, PageResult<unknown>>('/sales/records', { params: query })
}
