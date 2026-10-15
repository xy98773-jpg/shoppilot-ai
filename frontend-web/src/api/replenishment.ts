// 补货模块（骨架占位）。
// 红线：补货量计算等业务规则在服务端，前端不得承载。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'

// TODO: 补货建议列表、审核相关接口（仅展示与提交，规则在服务端）。
export function listReplenishments(query: PageQuery): Promise<PageResult<unknown>> {
  return http.get<unknown, PageResult<unknown>>('/replenishment/suggestions', { params: query })
}
