// 分析任务模块 api（M4-B21b）。
//
// 权威来源：`backend-java/analysis/.../api/AnalysisController.java`
// （前缀 `/api/v1/analysis`，`http.ts` 已带 `/api/v1`）。
//
// 已实现端点（契约 §11.5 / §11.6）：
//   - POST /analysis/runs              创建（OPERATOR+，202，必带 Idempotency-Key）
//   - GET  /analysis/runs/{id}         状态与进度（任何已登录成员）
//   - GET  /analysis/runs/{id}/items   结果快照（progress<100 ⇒ 409 ANL-7012）
//   - POST /analysis/runs/{id}/cancel  取消（OPERATOR+，200；无请求体、幂等键非必填）
//
// ★ 列表 `GET /analysis/runs` **尚不存在**（属 B22）。`listAnalysisRuns` 是 M1 骨架遗留，
//   本批**不调用**它（列表页只用本地「最近创建」+ 直接输入 run_id 跳转）。
// ★ 本层只搬运参数与结果，不做任何数量计算（口径铁律 ④）。
import http, { postWithIdempotency, type ReplayableResult } from './http'
import type { PageQuery, PageResult } from '@/types/common'
import type {
  AnalysisItemsQuery,
  AnalysisItemView,
  AnalysisRun,
  AnalysisRunStatusResponse,
  CancelRunResponse,
  CreateAnalysisRunRequest,
  CreateRunResponse,
} from '@/types/analysis'

// TODO: 分析任务列表、详情相关接口。
// ★ 后端尚无 `GET /analysis/runs`（B22），保留既有骨架签名，本批不调用。
export function listAnalysisRuns(query: PageQuery): Promise<PageResult<AnalysisRun>> {
  return http.get<unknown, PageResult<AnalysisRun>>('/analysis/runs', { params: query })
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
