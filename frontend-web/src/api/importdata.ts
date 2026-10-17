// 数据导入模块 api（M3-B10）。
//
// 权威来源：`backend-java/importdata/.../api/ImportBatchController.java`
// 与 `ImportTemplateController.java`（前缀 `/api/v1/import`，`http.ts` 已带 `/api/v1`）。
//
// ★ 红线：
//   - 不臆造字段与端点，路径/参数名逐字对齐控制器；
//   - `confirm` 必须带 `Idempotency-Key`（缺键 → 400 COM-0001），由 `postWithIdempotency` 统一透传；
//   - 业务语义（状态机、校验规则、错误码）一律由服务端决定，本层只做参数搬运。
import http, { postWithIdempotency, type ReplayableResult } from './http'
import type { PageResult } from '@/types/common'
import type { AxiosResponse } from 'axios'
import type {
  ConfirmRequest,
  ConfirmResult,
  ImportBatchDetail,
  ImportBatchListItem,
  ImportBatchQuery,
  ImportBatchUpload,
  ImportErrorExportQuery,
  ImportErrorQuery,
  ImportErrorRow,
  ImportPreview,
  ImportTemplate,
  MappingResult,
  PutMappingRequest,
  ValidationResult,
} from '@/types/importdata'

/** 上传文件并创建批次（`POST /import/batches`，multipart）→ 201。 */
export function uploadBatch(
  file: File,
  bizType: string,
  templateVersion?: string,
): Promise<ImportBatchUpload> {
  const form = new FormData()
  form.append('file', file)
  form.append('bizType', bizType)
  if (templateVersion) {
    form.append('templateVersion', templateVersion)
  }
  // ★ 实例默认 `Content-Type: application/json` 会让 axios 把 FormData 当 JSON 处理；
  //   置 undefined 使其被移除，交由浏览器写入带 boundary 的 multipart 头（联调实测）。
  return http.post<unknown, ImportBatchUpload>('/import/batches', form, {
    headers: { 'Content-Type': undefined },
  })
}

/**
 * 解析与预览（`GET /import/batches/{id}/preview`）。
 *
 * @param limit 预览行数（后端默认 20、硬上限 100）
 */
export function previewBatch(id: number, limit?: number): Promise<ImportPreview> {
  return http.get<unknown, ImportPreview>(`/import/batches/${id}/preview`, {
    params: limit === undefined ? {} : { limit },
  })
}

/** 提交字段映射（`PUT /import/batches/{id}/mapping`，PARSED → MAPPED）。 */
export function putMapping(id: number, payload: PutMappingRequest): Promise<MappingResult> {
  return http.put<unknown, MappingResult>(`/import/batches/${id}/mapping`, payload)
}

/** 全量校验（`POST /import/batches/{id}/validate`，MAPPED → VALIDATED）。 */
export function validateBatch(id: number): Promise<ValidationResult> {
  return http.post<unknown, ValidationResult>(`/import/batches/${id}/validate`)
}

/**
 * 确认落库（`POST /import/batches/{id}/confirm`，必须带 `Idempotency-Key`）。
 *
 * @param idempotencyKey 一次用户意图一个键；重试**复用同一键**
 * @param payload        可选（`skip_warning_rows` / `expected_total_rows`）
 */
export function confirmBatch(
  id: number,
  idempotencyKey: string,
  payload?: ConfirmRequest,
): Promise<ReplayableResult<ConfirmResult>> {
  return postWithIdempotency<ConfirmResult>(
    `/import/batches/${id}/confirm`,
    payload ?? {},
    idempotencyKey,
  )
}

/** 批次列表（`GET /import/batches`，分页字段为 `list`）。 */
export function listBatches(query: ImportBatchQuery): Promise<PageResult<ImportBatchListItem>> {
  return http.get<unknown, PageResult<ImportBatchListItem>>('/import/batches', { params: query })
}

/** 批次详情（`GET /import/batches/{id}`）。 */
export function getBatch(id: number): Promise<ImportBatchDetail> {
  return http.get<unknown, ImportBatchDetail>(`/import/batches/${id}`)
}

/** 错误/警告明细分页（`GET /import/batches/{id}/errors`）。 */
export function listBatchErrors(
  id: number,
  query: ImportErrorQuery,
): Promise<PageResult<ImportErrorRow>> {
  return http.get<unknown, PageResult<ImportErrorRow>>(`/import/batches/${id}/errors`, {
    params: query,
  })
}

/**
 * 错误报告 CSV 导出（`GET /import/batches/{id}/errors/export`）。
 *
 * ★ 该端点是**文件流**（非 ApiResponse 包络），`http.ts` 拦截器对非包络响应原样返回
 *   `AxiosResponse`；此处返回 Blob，由调用方用对象 URL 触发下载（携带 Authorization 头，
 *   不能用 `<a href>` 直链——那样不会带 Bearer Token）。
 */
export async function exportBatchErrors(
  id: number,
  query: ImportErrorExportQuery,
): Promise<Blob> {
  const response = await http.get<Blob, AxiosResponse<Blob>>(
    `/import/batches/${id}/errors/export`,
    { params: query, responseType: 'blob' },
  )
  return response.data
}

/** 模板清单（`GET /import/templates`，6 类 bizType 全量返回）。 */
export function listTemplates(): Promise<ImportTemplate[]> {
  return http.get<unknown, ImportTemplate[]>('/import/templates')
}
