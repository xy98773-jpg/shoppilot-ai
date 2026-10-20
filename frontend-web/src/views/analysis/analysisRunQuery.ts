// 分析任务列表的筛选/排序选项与查询参数装配（纯函数，无副作用）。
//
// ★ 只做「控件值 → 请求参数」的搬运：空值不传、字符串原样透传，
//   不做任何业务判定（状态机、可见性由服务端决定）。
// ★ 排序默认**不传**：服务端按 `created_at,desc` 兜底；只有用户显式选择时才带 `sort`。
import type { AnalysisRunStatus, AnalysisRunsQuery } from '@/types/analysis'

/** 状态筛选项（七态，文案由 `statusLabel` 渲染）。 */
export const RUN_STATUS_OPTIONS: readonly AnalysisRunStatus[] = [
  'PENDING',
  'RUNNING',
  'SUCCEEDED',
  'DEGRADED',
  'FAILED',
  'TIMEOUT',
  'CANCELLED',
]

/**
 * 排序选项（值即后端白名单参数 `field,dir`）。
 *
 * 空串 = 不传 `sort`，由服务端按 `created_at,desc` 处理（保持默认口径）。
 */
export const RUN_SORT_OPTIONS = [
  { label: '创建时间（新→旧，默认）', value: '' },
  { label: '创建时间（旧→新）', value: 'created_at,asc' },
  { label: '状态（升序）', value: 'status,asc' },
  { label: '完成时间（新→旧）', value: 'finished_at,desc' },
] as const

/** 列表筛选控件的原始状态（空串/undefined/空数组均视为「不筛选」）。 */
export interface RunFilterInput {
  page: number
  pageSize: number
  status?: string
  dateFrom?: string
  dateTo?: string
  sort?: string
}

/** 装配 `GET /analysis/runs` 查询参数（空值一律省略，不传空串给后端）。 */
export function buildAnalysisRunsQuery(input: RunFilterInput): AnalysisRunsQuery {
  const query: AnalysisRunsQuery = { page: input.page, pageSize: input.pageSize }
  if (input.status) {
    query.status = input.status
  }
  if (input.dateFrom) {
    query.date_from = input.dateFrom
  }
  if (input.dateTo) {
    query.date_to = input.dateTo
  }
  if (input.sort) {
    query.sort = input.sort
  }
  return query
}
