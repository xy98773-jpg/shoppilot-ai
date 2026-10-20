// 分析任务相关类型（M4-B21b）。
//
// 权威来源：
//   - `docs/03-api/API_CONTRACT.md §11.5 / §11.6`
//   - `backend-java/analysis/.../application/dto/*.java`
//     CreateAnalysisRunRequest / CreateRunResponse / AnalysisRunStatusResponse
//     AnalysisItemView / CancelRunResponse
//
// ★ 口径铁律 ④：数量列一律**字符串**（如 `"8.000"`、`cv` 4 位小数），
//   前端原样展示，禁止 `Number()` / `parseFloat()` 后再 `toFixed()`（会丢精度）。
// ★ 字段名保持 wire 形状（snake_case），不做驼峰归一化。

// ---------------------------------------------------------------------------
// 既有占位类型（M1 骨架，保持不动；新代码请用下方 AnalysisRunStatus 等）
// ---------------------------------------------------------------------------

export type AnalysisStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'

export interface AnalysisRun {
  id: string
  type: string
  status: AnalysisStatus
  createdAt: string
}

// ---------------------------------------------------------------------------
// 运行状态（`AnalysisRunStatus`，DESIGN_BASIS §9.7）
// ---------------------------------------------------------------------------

/**
 * 运行状态。
 *
 * 合法转换：`PENDING → RUNNING → {SUCCEEDED | DEGRADED | FAILED | TIMEOUT | CANCELLED}`；
 * 终态不可再变。`DEGRADED` = 规则计算成功但 LLM 解释失败，业务可用。
 */
export type AnalysisRunStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'DEGRADED'
  | 'FAILED'
  | 'TIMEOUT'
  | 'CANCELLED'

/** 终态集合（终态不再轮询）。 */
export const TERMINAL_RUN_STATUSES: readonly AnalysisRunStatus[] = [
  'SUCCEEDED',
  'DEGRADED',
  'FAILED',
  'TIMEOUT',
  'CANCELLED',
]

/** 是否终态。 */
export function isTerminalStatus(status: string | null | undefined): boolean {
  return !!status && (TERMINAL_RUN_STATUSES as readonly string[]).includes(status)
}

/** 是否中间态（仅中间态轮询）。 */
export function isInFlightStatus(status: string | null | undefined): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

/** 范围类型（`AnalysisScope`）。 */
export type AnalysisScopeType = 'ALL_ACTIVE' | 'SELECTED_SKUS' | 'CATEGORY'

/** 风险等级（`risk_level`）。 */
export type AnalysisRiskLevel = 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'UNKNOWN'

/** LLM 阶段状态（`llm_status`）。 */
export type AnalysisLlmStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'TIMEOUT' | 'SKIPPED'

// ---------------------------------------------------------------------------
// 创建（`POST /analysis/runs`）
// ---------------------------------------------------------------------------

/**
 * 运行参数（`AnalysisParams`；缺省即服务端补默认值）。
 *
 * `ss_ratio` / `cv_high` 为 4 位小数的**字符串**；`budget_limit` 3 位小数或 `null`。
 */
export interface AnalysisParamsInput {
  window_days?: number
  min_days?: number
  coverage_days?: number
  lead_time_default?: number
  ss_ratio?: string
  cv_high?: string
  budget_limit?: string | null
  enable_llm?: boolean
}

/** 创建请求体（`CreateAnalysisRunRequest`；**不含** `team_id`，由服务端从 JWT 解析）。 */
export interface CreateAnalysisRunRequest {
  scope_type: AnalysisScopeType
  scope_json?: { sku_ids?: number[]; category?: string } | null
  warehouse_id?: number | null
  /** `yyyy-MM-dd`。 */
  date_from: string
  /** `yyyy-MM-dd`。 */
  date_to: string
  params?: AnalysisParamsInput | null
  algorithm_version: string
  prompt_version?: string | null
  model_name?: string | null
}

/** 创建成功响应 `data`（202，`CreateRunResponse`）。 */
export interface CreateRunResponse {
  run_id: number
  run_no: string
  status: AnalysisRunStatus
  scope_type: AnalysisScopeType
  total_skus: number
  processed_skus: number
  progress: number
  current_step: string | null
  llm_status: AnalysisLlmStatus | null
  timeout_at: string | null
  created_at: string | null
}

// ---------------------------------------------------------------------------
// 状态查询（`GET /analysis/runs/{id}`）
// ---------------------------------------------------------------------------

/**
 * 运行状态响应 `data`（`AnalysisRunStatusResponse`）。
 *
 * ★ 中间态缺省字段**不存在**（后端 `@JsonInclude(NON_NULL)`）——用 `undefined`
 *   区分「还没算完」与「算完是 0」，不要用 0 兜底。
 */
export interface AnalysisRunStatusResponse {
  run_id: number
  run_no: string
  status: AnalysisRunStatus
  scope_type: AnalysisScopeType
  warehouse_id: number
  total_skus: number
  processed_skus: number
  progress: number
  current_step?: string | null
  llm_status?: AnalysisLlmStatus | null
  llm_error_message?: string | null
  risk_sku_count?: number | null
  suggestion_count?: number | null
  data_start_date?: string | null
  data_end_date?: string | null
  timeout_at?: string | null
  started_at?: string | null
  finished_at?: string | null
}

// ---------------------------------------------------------------------------
// 结果明细（`GET /analysis/runs/{id}/items`）
// ---------------------------------------------------------------------------

/** 单条结果快照（`AnalysisItemView`，34 字段逐项对齐契约 §11.6）。 */
export interface AnalysisItemView {
  id: number
  run_id: number
  sku_id: number
  sku_code: string | null
  warehouse_id: number
  /** 数量列均为字符串（3 位小数；`null` = 数据不足/未产出，是有意义的 null）。 */
  on_hand_qty: string | null
  in_transit_qty?: string | null
  effective_in_transit_qty?: string | null
  committed_qty?: string | null
  inventory_position_qty?: string | null
  available_qty?: string | null
  avg_daily_sales: string | null
  sales_window_days?: number | null
  valid_days?: number | null
  total_sales_qty?: string | null
  /** 变异系数（4 位小数）。 */
  cv?: string | null
  lead_time_days?: number | null
  lead_time_source?: string | null
  coverage_days?: number | null
  safety_stock_qty?: string | null
  volatility_adjusted?: boolean | null
  qty_base: string | null
  qty_suggest: string | null
  moq_applied?: string | null
  order_multiple_applied?: string | null
  truncated_by_budget?: boolean | null
  risk_level: AnalysisRiskLevel | string | null
  risk_types?: string[] | null
  risk_findings?: unknown[] | null
  data_quality_json?: Record<string, unknown> | null
  llm_explanation?: string | null
  llm_evidence?: string[] | null
  llm_status?: AnalysisLlmStatus | string | null
  warnings?: string[] | null
}

/** 明细查询参数（`risk_level` / `page` / `pageSize` / `sort`）。 */
export interface AnalysisItemsQuery {
  page: number
  pageSize: number
  risk_level?: string
  /** 形如 `avg_daily_sales,desc`（服务端定义排序语义）。 */
  sort?: string
}

// ---------------------------------------------------------------------------
// 取消（`POST /analysis/runs/{id}/cancel`）
// ---------------------------------------------------------------------------

/** 取消成功响应 `data`（`CancelRunResponse`）。 */
export interface CancelRunResponse {
  run_id: number
  status: 'CANCELLED'
  cancelled_at: string | null
}
