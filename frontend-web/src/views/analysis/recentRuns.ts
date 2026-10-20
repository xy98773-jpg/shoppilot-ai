// 「本地最近创建」分析任务（列表 API `GET /analysis/runs` 属 B22，本批不伪造）。
//
// 只存**创建成功后服务端返回的最小快照**（run_id / run_no / status / created_at），
// 不做任何状态推断：打开详情页仍以 `GET /analysis/runs/{id}` 为准。
import type { AnalysisRunStatus } from '@/types/analysis'

/** localStorage 键名。 */
export const RECENT_RUNS_STORAGE_KEY = 'shoppilot_analysis_recent_runs'

/** 最多保留条数。 */
export const RECENT_RUNS_LIMIT = 10

/** 最近创建的一条记录。 */
export interface RecentRunEntry {
  run_id: number
  run_no: string
  status: AnalysisRunStatus
  created_at: string | null
}

/** 读取本地最近创建（损坏/不可用时返回空数组，绝不抛错）。 */
export function loadRecentRuns(): RecentRunEntry[] {
  try {
    const raw = localStorage.getItem(RECENT_RUNS_STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter(isRecentRunEntry).slice(0, RECENT_RUNS_LIMIT)
  } catch {
    return []
  }
}

/** 记住一次创建（按 run_id 去重，最新的排最前，最多 10 条）。 */
export function rememberRun(entry: RecentRunEntry): RecentRunEntry[] {
  const next = [entry, ...loadRecentRuns().filter((item) => item.run_id !== entry.run_id)].slice(
    0,
    RECENT_RUNS_LIMIT,
  )
  try {
    localStorage.setItem(RECENT_RUNS_STORAGE_KEY, JSON.stringify(next))
  } catch {
    // 存储不可用（隐私模式/配额满）不影响主流程
  }
  return next
}

/** 清空本地记录。 */
export function clearRecentRuns(): void {
  try {
    localStorage.removeItem(RECENT_RUNS_STORAGE_KEY)
  } catch {
    // ignore
  }
}

function isRecentRunEntry(value: unknown): value is RecentRunEntry {
  const v = value as RecentRunEntry | null
  return !!v && typeof v.run_id === 'number' && typeof v.run_no === 'string'
}
