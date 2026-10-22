// 分析创建表单 → 请求体的纯逻辑（可单测，不依赖组件）。
//
// 权威来源：`CreateAnalysisRunRequest.java` + `AnalysisParams.java`
//   - `team_id` **不传**（服务端从 JWT 解析）；
//   - `params` 全量 8 字段（服务端缺省会补默认值，但显式传全量便于用户确认快照）；
//   - 比率/预算字段提交时必须为 **JSON 数值**（后端 `isNumber` 校验，字符串 → ANL-7009）；
//     表单内保留字符串仅为了 `el-input` 双向绑定，提交前经 `Number()` 转换。
import type {
  AnalysisParamsInput,
  AnalysisScopeType,
  CreateAnalysisRunRequest,
} from '@/types/analysis'

/** 表单输入（全部为字符串，便于 `el-input` 双向绑定）。 */
export interface AnalysisFormInput {
  scope_type: AnalysisScopeType
  warehouse_id: string
  date_from: string
  date_to: string
  /** 逗号/空白分隔的 SKU ID 文本（仅 `SELECTED_SKUS` 使用）。 */
  sku_ids: string
  /** 类目名（仅 `CATEGORY` 使用）。 */
  category: string
  window_days: string
  min_days: string
  coverage_days: string
  lead_time_default: string
  ss_ratio: string
  cv_high: string
  budget_limit: string
  enable_llm: boolean
  algorithm_version: string
  prompt_version: string
  model_name: string
}

/** 默认参数（`DESIGN_BASIS §10.6` / `ANALYSIS_CONTRACT §1.1`）。 */
export const DEFAULT_PARAMS = {
  window_days: '28',
  min_days: '7',
  coverage_days: '7',
  lead_time_default: '7',
  ss_ratio: '0.5',
  cv_high: '1.0',
} as const

/** 默认算法版本（`API_CONTRACT §11.5` 示例值）。 */
export const DEFAULT_ALGORITHM_VERSION = 'avg-ma-3sigma-v1'
/** 默认 Prompt 版本（`enable_llm=true` 时必填）。 */
export const DEFAULT_PROMPT_VERSION = 'explain_risk.v1'

/** 生成默认表单值；日期区间默认「最近 28 天（含今日）」。 */
export function createDefaultForm(today: Date = new Date()): AnalysisFormInput {
  const { date_from, date_to } = defaultDateRange(today)
  return {
    scope_type: 'ALL_ACTIVE',
    warehouse_id: '',
    date_from,
    date_to,
    sku_ids: '',
    category: '',
    ...DEFAULT_PARAMS,
    budget_limit: '',
    enable_llm: true,
    algorithm_version: DEFAULT_ALGORITHM_VERSION,
    prompt_version: DEFAULT_PROMPT_VERSION,
    model_name: '',
  }
}

/** 默认日期区间：截止今日，起点 = 今日 − (windowDays − 1) 天（`yyyy-MM-dd`）。 */
export function defaultDateRange(
  today: Date = new Date(),
  windowDays = 28,
): { date_from: string; date_to: string } {
  const end = new Date(today.getFullYear(), today.getMonth(), today.getDate())
  const start = new Date(end)
  start.setDate(end.getDate() - (windowDays - 1))
  return { date_from: toDateText(start), date_to: toDateText(end) }
}

function toDateText(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 解析 SKU ID 文本（逗号/空白/分号分隔；非法项忽略）。 */
export function parseSkuIds(text: string): number[] {
  return text
    .split(/[\s,;，；]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
    .map((s) => Number(s))
    .filter((n) => Number.isInteger(n) && n > 0)
}

/** 正整数或 `null`（空串/非法 → null）。 */
function toPositiveIntOrNull(text: string): number | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  const n = Number(trimmed)
  return Number.isInteger(n) && n > 0 ? n : null
}

/** 非负整数（空串/非法 → NaN，交由校验拦下）。 */
function toInt(text: string): number {
  const trimmed = text.trim()
  return trimmed ? Number(trimmed) : Number.NaN
}

/**
 * 表单校验：返回第一条错误文案；通过返回 `null`。
 *
 * ★ 只做「明显非法」的前置拦截（必填/区间/上下界），最终以服务端
 *   `COM-0001` / `ANL-7007` / `ANL-7009` / `ANL-7013` 为准。
 */
export function validateAnalysisForm(input: AnalysisFormInput): string | null {
  if (!input.date_from || !input.date_to) {
    return '请选择数据窗口的起止日期'
  }
  if (input.date_from > input.date_to) {
    return '起始日期不能晚于截止日期'
  }
  if (input.scope_type === 'SELECTED_SKUS' && parseSkuIds(input.sku_ids).length === 0) {
    return '请填写至少一个 SKU ID'
  }
  if (input.scope_type === 'CATEGORY' && !input.category.trim()) {
    return '请填写分析类目'
  }
  if (input.warehouse_id.trim() && toPositiveIntOrNull(input.warehouse_id) === null) {
    return '仓库 ID 必须是正整数'
  }
  const windowDays = toInt(input.window_days)
  const minDays = toInt(input.min_days)
  const coverageDays = toInt(input.coverage_days)
  const leadTime = toInt(input.lead_time_default)
  if (!Number.isInteger(windowDays) || windowDays < 1 || windowDays > 365) {
    return '统计窗口必须在 1..365 天之间'
  }
  if (!Number.isInteger(minDays) || minDays < 1 || minDays > windowDays) {
    return '最少有效天数必须在 1..统计窗口 之间'
  }
  if (!Number.isInteger(coverageDays) || coverageDays < 0 || coverageDays > 365) {
    return '覆盖天数必须在 0..365 之间'
  }
  if (!Number.isInteger(leadTime) || leadTime < 0 || leadTime > 365) {
    return '默认交期必须在 0..365 天之间'
  }
  if (!isNonNegativeDecimal(input.ss_ratio)) {
    return '安全库存系数必须是非负数值'
  }
  if (!isNonNegativeDecimal(input.cv_high)) {
    return 'CV 高波动阈值必须是非负数值'
  }
  if (input.budget_limit.trim() && !isNonNegativeDecimal(input.budget_limit)) {
    return '预算上限必须是非负数值（留空表示不限制）'
  }
  if (!input.algorithm_version.trim()) {
    return '请填写算法版本'
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(input.algorithm_version.trim())) {
    return '算法版本只能包含字母、数字、点、下划线、连字符，且不得以符号开头'
  }
  if (input.enable_llm && !input.prompt_version.trim()) {
    return '启用 LLM 解释时必须填写 Prompt 版本'
  }
  return null
}

function isNonNegativeDecimal(text: string): boolean {
  const trimmed = text.trim()
  if (!trimmed) return false
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return false
  return Number(trimmed) >= 0
}

/** 组装创建请求体（`team_id` 不传；空值字段省略或置 null）。 */
export function buildCreatePayload(input: AnalysisFormInput): CreateAnalysisRunRequest {
  const params: AnalysisParamsInput = {
    window_days: toInt(input.window_days),
    min_days: toInt(input.min_days),
    coverage_days: toInt(input.coverage_days),
    lead_time_default: toInt(input.lead_time_default),
    // ★ 比率/预算字段必须是 JSON 数值（后端 decimalField 用 isNumber 校验，
    //   字符串会被拒 ANL-7009）；表单绑定用字符串仅为 el-input 双向绑定方便。
    ss_ratio: Number(input.ss_ratio),
    cv_high: Number(input.cv_high),
    budget_limit: input.budget_limit.trim() ? Number(input.budget_limit) : null,
    enable_llm: input.enable_llm,
  }

  let scopeJson: CreateAnalysisRunRequest['scope_json'] = null
  if (input.scope_type === 'SELECTED_SKUS') {
    scopeJson = { sku_ids: parseSkuIds(input.sku_ids) }
  } else if (input.scope_type === 'CATEGORY') {
    scopeJson = { category: input.category.trim() }
  }

  return {
    scope_type: input.scope_type,
    scope_json: scopeJson,
    warehouse_id: toPositiveIntOrNull(input.warehouse_id),
    date_from: input.date_from,
    date_to: input.date_to,
    params,
    algorithm_version: input.algorithm_version.trim(),
    prompt_version: input.enable_llm ? input.prompt_version.trim() : null,
    model_name: input.model_name.trim() ? input.model_name.trim() : null,
  }
}
