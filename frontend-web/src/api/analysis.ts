// 分析任务模块（骨架占位）。
import http from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type { AnalysisRun } from '@/types/analysis'

// TODO: 分析任务列表、详情相关接口。
export function listAnalysisRuns(query: PageQuery): Promise<PageResult<AnalysisRun>> {
  return http.get<unknown, PageResult<AnalysisRun>>('/analysis/runs', { params: query })
}
