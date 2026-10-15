// 数据导入模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'

// TODO: 导入向导、导入批次相关接口。
export function listImportBatches(query: PageQuery): Promise<PageResult<unknown>> {
  return http.get<unknown, PageResult<unknown>>('/importdata/batches', { params: query })
}
