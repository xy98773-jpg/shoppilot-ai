// 导入模块 wire 类型（与后端 importdata 模块 DTO 逐字段对齐）。
//
// 权威来源：`backend-java/importdata/.../application/dto/*.java`（@JsonNaming SnakeCase）
//   上传      ImportBatchUploadView
//   预览      ImportPreviewView / ImportColumnView
//   映射      PutMappingRequest → MappingResultView
//   校验      ValidationResultView / RowErrorView
//   列表      ImportBatchListView
//   详情      ImportBatchDetailView
//   错误明细  ImportErrorRowView
//   确认      ConfirmRequest → ConfirmResultView
//   模板      ImportTemplateView
//
// ★ 红线：字段名一律 snake_case（契约 §2.1），**禁止**在类型层臆造/改名；
//   数量以字符串传输（§9.4），本模块暂无数量字段，但状态/行数均为后端原值。

/** 导入业务类型（`ImportBizType`，6 类）。 */
export type ImportBizType =
  | 'PRODUCT'
  | 'SKU'
  | 'SUPPLIER'
  | 'WAREHOUSE'
  | 'INVENTORY_SNAPSHOT'
  | 'SALES_DAILY'

/**
 * 导入批次状态机（`IMPORT_WORKFLOW §13`）。
 * UPLOADED → PARSED → MAPPED → VALIDATED → IMPORTING → COMPLETED / PARTIAL_SUCCESS / FAILED
 */
export type ImportBatchStatus =
  | 'UPLOADED'
  | 'PARSED'
  | 'MAPPED'
  | 'VALIDATED'
  | 'IMPORTING'
  | 'COMPLETED'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELLED'
  | 'DUPLICATE_REJECTED'

/** 行级错误级别（`import_row_errors.error_level`）。 */
export type ImportErrorLevel = 'ERROR' | 'WARNING'

/** 上传成功响应（201）。 */
export interface ImportBatchUpload {
  batch_id: number
  batch_no: string
  biz_type: ImportBizType
  file_name: string
  file_hash: string
  file_size: number
  status: ImportBatchStatus
  created_at: string
}

/** 单列描述（预览响应 `columns[]`；`mapped_field` 为 null 表示未映射）。 */
export interface ImportColumn {
  index: number
  source_name: string
  mapped_field: string | null
}

/**
 * 解析与预览响应（`GET /{id}/preview`）。
 *
 * ★ `preview[]` 每行以**源列名**为键，另含 `row_no`（1 起）；
 *   映射关系由 `columns[].mapped_field` 单独表达（后端裁定的稳定结构）。
 */
export interface ImportPreview {
  batch_id: number
  status: ImportBatchStatus
  biz_type: ImportBizType
  template_version: string | null
  total_rows: number
  preview_rows: number
  columns: ImportColumn[]
  preview: Record<string, string>[]
  suggested_mapping: Record<string, string>
  sheets: string[]
  sheet_name: string | null
  encoding: string | null
}

/** 字段映射提交体（`PUT /{id}/mapping`；键 = 目标字段名，值 = 源列名）。 */
export interface PutMappingRequest {
  field_mapping_json: Record<string, string>
  parse_options_json?: Record<string, unknown>
}

/** 映射提交结果。 */
export interface MappingResult {
  batch_id: number
  status: ImportBatchStatus
  field_mapping_json: Record<string, string>
}

/** 单条行错误/警告（校验结果 `errors[]`，不含 `raw_data`）。 */
export interface RowError {
  row_no: number
  error_level: ImportErrorLevel
  error_code: string
  error_field: string | null
  error_message: string
}

/** 全量校验结果（`POST /{id}/validate`）。 */
export interface ValidationResult {
  batch_id: number
  status: ImportBatchStatus
  total_rows: number
  error_rows: number
  warning_rows: number
  errors: RowError[]
}

/** 批次列表项（`GET /batches`，分页字段为 `list`）。 */
export interface ImportBatchListItem {
  batch_id: number
  batch_no: string
  biz_type: ImportBizType
  file_name: string
  status: ImportBatchStatus
  total_rows: number
  success_rows: number
  failed_rows: number
  error_code: string | null
  error_message: string | null
  created_at: string
  started_at: string | null
  finished_at: string | null
}

/** 批次详情（列表字段 + 错误统计 + 文件/幂等关联信息）。 */
export interface ImportBatchDetail extends ImportBatchListItem {
  error_rows: number
  warning_rows: number
  file_hash: string
  file_size: number
  attempt_no: number
  duplicate_of_batch_id: number | null
  superseded_by_batch_id: number | null
  template_version: string | null
}

/** 错误明细项（`GET /{id}/errors`，含 `raw_data` 原始行）。 */
export interface ImportErrorRow {
  id: number
  batch_id: number
  row_no: number
  error_level: ImportErrorLevel
  error_code: string
  error_field: string | null
  error_message: string
  raw_data: Record<string, string> | null
}

/** 确认落库请求体（`POST /{id}/confirm`；两字段均可选）。 */
export interface ConfirmRequest {
  skip_warning_rows?: boolean
  expected_total_rows?: number
}

/** 确认落库结果（重放分支返回同一结构）。 */
export interface ConfirmResult {
  batch_id: number
  status: ImportBatchStatus
  total_rows: number
  success_rows: number
  failed_rows: number
  started_at: string | null
  finished_at: string | null
}

/** 模板字段定义（`GET /templates`）。 */
export interface ImportTemplateField {
  name: string
  label: string
  required: boolean
  type: string
  max_length: number | null
  reference: string | null
  aliases: string[]
}

/** 模板（6 类 bizType 全量返回）。 */
export interface ImportTemplate {
  biz_type: ImportBizType
  template_version: string
  fields: ImportTemplateField[]
}

/** 批次列表查询参数。 */
export interface ImportBatchQuery {
  page: number
  pageSize: number
  status?: string
  bizType?: string
  from?: string
  to?: string
}

/** 错误明细分页查询参数。 */
export interface ImportErrorQuery {
  page: number
  pageSize: number
  errorLevel?: string
  rowNo?: number
}

/** 错误导出查询参数（无分页）。 */
export interface ImportErrorExportQuery {
  errorLevel?: string
  rowNo?: number
}
