// 通用后端响应与分页类型，与 Java 侧对齐。
// 形态权威来源：docs/03-api/API_CONTRACT.md §2（统一响应结构）。
// 注意：本文件为纯类型定义，不得依赖任何其它层。

/** 成功码（契约 §2.1：成功恒为 "OK"；前端只需判断 code === "OK"）。 */
export const SUCCESS_CODE = 'OK'

/** 请求追踪响应头名（契约 §7：响应体 requestId 必须等于该响应头）。 */
export const REQUEST_ID_HEADER = 'X-Request-Id'

/**
 * 失败响应中的字段级细节（与 Java ApiErrorDetail 对齐，契约 §2.3）。
 * - field: 出错字段路径，snake_case，支持嵌套（如 params.window_days）。
 * - message: 该字段的中文错误说明。
 * - rejectedValue: 被拒绝的原始值（敏感值不回显）。
 */
export interface ApiErrorDetail {
  field: string
  message: string
  rejectedValue?: unknown
}

/**
 * 统一 API 响应包裹结构（与 Java ApiResponse 对齐，契约 §2.1 / §2.3）。
 * - code: 成功恒为 "OK"；失败为错误码常量（如 "COM-0001" / "TEAM-2001"）。
 * - message: 成功为 "success"；失败为面向用户的中文提示。
 * - data: 业务数据；失败时恒为 null。
 * - details: 仅字段级校验错误时存在（如 COM-0001）。
 * - requestId: 链路追踪 ID，等同响应头 X-Request-Id。
 * - timestamp: ISO-8601 UTC 字符串，形如 2026-10-14T08:00:00.000Z。
 */
export interface ApiResponse<T = unknown> {
  code: string
  message: string
  data: T | null
  details?: ApiErrorDetail[]
  requestId: string
  timestamp: string
}

/**
 * 分页结果结构（与 Java PageResult 对齐，契约 §2.2）。
 * 分页响应把列表与分页元信息一起放在 data 内。
 * - list: 当前页数据列表。
 * - page: 当前页码（从 1 开始）。
 * - pageSize: 每页大小（1..200）。
 * - total: 满足筛选条件的总记录数。
 */
export interface PageResult<T = unknown> {
  list: T[]
  page: number
  pageSize: number
  total: number
}

/** 分页查询通用入参（契约 §2.2：page 从 1 开始，pageSize 1..200）。 */
export interface PageQuery {
  page: number
  pageSize: number
  [key: string]: unknown
}
