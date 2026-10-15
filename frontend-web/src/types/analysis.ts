// 分析任务相关类型（占位，纯类型）。

export type AnalysisStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface AnalysisRun {
  id: string
  type: string
  status: AnalysisStatus
  createdAt: string
}
