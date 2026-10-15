// 通用后端响应与分页类型，与 Java 侧对齐。
// 注意：本文件为纯类型定义，不得依赖任何其它层。

/**
 * 统一 API 响应包裹结构（与 Java ApiResponse 对齐）。
 * - code: 业务状态码，0 / 200 等约定为成功（具体以服务端为准）。
 * - message: 提示信息。
 * - data: 业务数据载荷。
 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

/**
 * 分页结果结构（与 Java PageResult 对齐）。
 * - records: 当前页数据列表。
 * - total: 总记录数。
 * - page: 当前页码（从 1 开始）。
 * - size: 每页大小。
 */
export interface PageResult<T = unknown> {
  records: T[]
  total: number
  page: number
  size: number
}

/** 分页查询通用入参。 */
export interface PageQuery {
  page: number
  size: number
  [key: string]: unknown
}
