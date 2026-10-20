// 分析任务模块 api（M4-B21b）。
//
// 权威来源：`backend-java/analysis/.../api/AnalysisController.java`
// （前缀 `/api/v1/analysis`，`http.ts` 已带 `/api/v1`）。
//
// 已实现端点（契约 §11.5 / §11.6）：
//   - GET  /analysis/runs              列表（任何已登录成员，分页 + 状态/日期筛选）
//   - POST /analysis/runs              创建（OPERATOR+，202，必带 Idempotency-Key）
//   - GET  /analysis/runs/{id}         状态与进度（任何已登录成员）
//   - GET  /analysis/runs/{id}/items   结果快照（progress<100 ⇒ 409 ANL-7012）
//   - POST /analysis/runs/{id}/cancel  取消（OPERATOR+，200；无请求体、幂等键非必填）
//
// ★ 列表 `GET /analysis/runs` 由 B22.1 交付；`list` 元素与 `GET /runs/{id}` 同构。
// ★ 本层只搬运参数与结果，不做任何数量计算（口径铁律 ④）。
import http, { postWithIdempotency, type ReplayableResult } from './http'
import type { PageResult } from '@/types/common'
import type {
  AnalysisItemsQuery,
  AnalysisItemView,
  AnalysisRunListItem,
  AnalysisRunsQuery,
  AnalysisRunStatusResponse,
  CancelRunResponse,
  CreateAnalysisRunRequest,
  CreateRunResponse,
} from '@/types/analysis'

/**
 * 分析任务列表（`GET /analysis/runs`，任何已登录成员）。
 *
 * ★ 分页 + 可选 `status` / `date_from` / `date_to`（按 `created_at` 过滤）+
 *   `sort`（白名单，默认不传即服务端 `created_at,desc`）。
 */
export function listAnalysisRuns(
  query: AnalysisRunsQuery,
): Promise<PageResult<AnalysisRunListItem>> {
  return http.get<unknown, PageResult<AnalysisRunListItem>>('/analysis/runs', { params: query })
}

/**
 * 创建分析任务（`POST /analysis/runs`，202）。
 *
 * ★ 必须带 `Idempotency-Key`（缺失/空白 → 400 `COM-0010`）；键由前端
 *   `createIdempotencyIntent()` 生成，**一次表单会话固定，失败重试复用**。
 * ★ 返回 `{ data, replayed }`：命中重放说明同键同体已提交过，前端应提示而非当作新任务。
 */
export function createAnalysisRun(
  payload: CreateAnalysisRunRequest,
  idempotencyKey: string,
): Promise<ReplayableResult<CreateRunResponse>> {
  return postWithIdempotency<CreateRunResponse>('/analysis/runs', payload, idempotencyKey)
}

/** 查询运行状态与进度（`GET /analysis/runs/{id}`；不存在/跨团队 → ANL-7005）。 */
export function getAnalysisRun(runId: number | string): Promise<AnalysisRunStatusResponse> {
  return http.get<unknown, AnalysisRunStatusResponse>(`/analysis/runs/${runId}`)
}

/**
 * 查询结果明细（`GET /analysis/runs/{id}/items`）。
 *
 * ★ `progress < 100` 时服务端返回 409 `ANL-7012`（`data` 带 run_id/status/progress），
 *   前端应提示「继续轮询」而非当作失败。
 */
export function listAnalysisRunItems(
  runId: number | string,
  query: AnalysisItemsQuery,
): Promise<PageResult<AnalysisItemView>> {
  return http.get<unknown, PageResult<AnalysisItemView>>(`/analysis/runs/${runId}/items`, {
    params: query,
  })
}

/**
 * 取消运行（`POST /analysis/runs/{id}/cancel`，200）。
 *
 * ★ 无请求体、幂等键非必填（`§6.2` 必填清单不含 cancel）；幂等由「状态已是 CANCELLED」保证。
 * ★ 其它终态不可取消 → `ANL-7011`（前端应刷新状态）。
 */
export function cancelAnalysisRun(runId: number | string): Promise<CancelRunResponse> {
  return http.post<unknown, CancelRunResponse>(`/analysis/runs/${runId}/cancel`)
}
