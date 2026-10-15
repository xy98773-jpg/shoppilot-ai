// 审计日志模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'

// TODO: 审计日志列表相关接口。
export function listAuditLogs(query: PageQuery): Promise<PageResult<unknown>> {
  return http.get<unknown, PageResult<unknown>>('/audit/logs', { params: query })
}
