// 分析状态/风险等级的展示映射（纯函数，无业务判定）。
//
// ★ 状态机权威在服务端（Java `AnalysisRunStatus`）；这里只决定文案与标签颜色，
//   不得据此推断「可取消」「可重试」等业务规则（那些由 `isInFlightStatus` 判定）。

/** 运行状态中文文案。 */
export function statusLabel(status: string | null | undefined): string {
  switch (status) {
    case 'PENDING':
      return '排队中'
    case 'RUNNING':
      return '运行中'
    case 'SUCCEEDED':
      return '已完成'
    case 'DEGRADED':
      return '已完成（LLM 降级）'
    case 'FAILED':
      return '失败'
    case 'TIMEOUT':
      return '超时'
    case 'CANCELLED':
      return '已取消'
    default:
      return status || '-'
  }
}

/** 状态标签类型（Element Plus `el-tag`）。 */
export function statusTagType(
  status: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (status) {
    case 'SUCCEEDED':
      return 'success'
    case 'DEGRADED':
      return 'warning'
    case 'FAILED':
    case 'TIMEOUT':
      return 'danger'
    case 'CANCELLED':
      return 'info'
    case 'RUNNING':
      return 'primary'
    default:
      return 'info'
  }
}

/** 风险等级标签类型。 */
export function riskTagType(
  level: string | null | undefined,
): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  switch (level) {
    case 'HIGH':
      return 'danger'
    case 'MEDIUM':
      return 'warning'
    case 'LOW':
      return 'success'
    case 'NONE':
      return 'info'
    default:
      return 'info'
  }
}

/** 风险等级可选值（`ANALYSIS_CONTRACT`：NONE/LOW/MEDIUM/HIGH/UNKNOWN）。 */
export const RISK_LEVELS = ['NONE', 'LOW', 'MEDIUM', 'HIGH', 'UNKNOWN'] as const

/** 明细排序选项（服务端定义排序语义，前端只传 `field,dir`）。 */
export const ITEM_SORT_OPTIONS = [
  { label: '风险等级（高→低）', value: 'risk_level,desc' },
  { label: '日均销量（高→低）', value: 'avg_daily_sales,desc' },
  { label: '建议补货量（高→低）', value: 'qty_suggest,desc' },
  { label: '覆盖天数（低→高）', value: 'coverage_days,asc' },
] as const
