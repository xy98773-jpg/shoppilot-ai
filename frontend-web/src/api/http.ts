// axios 实例与拦截器（前端唯一允许 import axios 的文件）。
//
// 红线约束（PROJECT_STRUCTURE §6.1）：
// - 本文件为 api 层，不得静态 import stores（避免循环依赖）。
// - Token 注入直接从 localStorage 读取，不依赖 stores/user。
// - 错误码统一按 ApiResponse 形态处理（契约 API_CONTRACT §2）。
//
// 真正的鉴权与业务规则均在服务端，前端仅做体验层处理。
//
// ★ M2-Foundation 契约收口：服务端已按 API_CONTRACT §1.3 返回**语义化 HTTP 状态码**
//   （400/401/403/404/409/422/500…），因此业务失败不再走 2xx 分支——
//   响应包络的解析必须同时覆盖 success 与 error 两条路径。

import axios, {
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { REQUEST_ID_HEADER, SUCCESS_CODE, type ApiErrorDetail, type ApiResponse } from '@/types/common'

/** localStorage 中持久化 token 的键名（与 stores/user 保持一致）。 */
export const TOKEN_STORAGE_KEY = 'shoppilot_token'

/** 从 localStorage 读取当前令牌。 */
export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_STORAGE_KEY)
}

/** 清除 localStorage 中的令牌。 */
export function clearStoredToken(): void {
  localStorage.removeItem(TOKEN_STORAGE_KEY)
}

/** 前端可识别的结构化 API 错误（携带服务端错误码与字段级细节）。 */
export interface ApiError extends Error {
  code?: string
  details?: ApiErrorDetail[]
  requestId?: string
  httpStatus?: number
}

/** 生成请求唯一标识 X-Request-Id（无加密随机源时退化为时间戳+随机数）。 */
function generateRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

/** 判断响应体是否符合 ApiResponse 形态（code 为字符串是收口后的判据）。 */
function isApiEnvelope(body: unknown): body is ApiResponse {
  return !!body && typeof (body as ApiResponse).code === 'string'
}

/** 把服务端包络转换为前端结构化错误。 */
function toApiError(body: ApiResponse, httpStatus?: number): ApiError {
  const err = new Error(body.message || `请求失败 (${body.code})`) as ApiError
  err.code = body.code
  err.details = body.details
  err.requestId = body.requestId
  err.httpStatus = httpStatus
  return err
}

/**
 * API 版本前缀（契约 `API_CONTRACT §8.1`：所有业务端点形如 `/api/v1/...`）。
 *
 * ★ M2-T12 修正：M1 骨架原为 `baseURL: '/api'`，与各 `api/<module>.ts` 中的资源相对路径
 *   （如 `/auth/login`）拼接后得到 `/api/auth/login`，**缺少 `/v1` 段**，与后端
 *   `@RequestMapping("/api/v1/...")` 不匹配（必 404）。此处把版本段收敛到唯一位置。
 */
export const API_BASE_URL = '/api/v1'

/**
 * 内部标记头：请求需要回传「是否命中幂等重放」（`M3-B10`，{@code API_CONTRACT §6.3}）。
 *
 * ★ 只有 `postWithIdempotency` 会设置它；不设置它的**所有既有调用方**行为完全不变
 *   （仍直接拿解包后的 `data`），因此本能力是**纯增量**，不改动既有错误码处理与 401 逻辑。
 */
const REPLAY_PROBE_HEADER = 'X-Shoppilot-Replay-Probe'

/** 带幂等键写请求的返回：解包后的业务数据 + 服务端是否标记为重放。 */
export interface ReplayableResult<T> {
  data: T
  replayed: boolean
}

const http: AxiosInstance = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器：注入 Token 与 X-Request-Id
http.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    const token = getStoredToken()
    if (token) {
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    // 透传上游 X-Request-Id，否则生成新的
    const requestId =
      (config.headers.get(REQUEST_ID_HEADER) as string) || generateRequestId()
    config.headers.set(REQUEST_ID_HEADER, requestId)
    return config
  },
  (error) => Promise.reject(error),
)

// 响应拦截器：按 ApiResponse 形态统一处理（契约 §2）
http.interceptors.response.use(
  (response: AxiosResponse<unknown>) => {
    const body = response.data
    if (isApiEnvelope(body)) {
      if (body.code !== SUCCESS_CODE) {
        // 理论兜底：成功状态码下仍返回非 OK（如 IMP-4006/IMP-4014 的 200 语义码）
        return Promise.reject(toApiError(body, response.status))
      }
      // 幂等重放探测（M3-B10）：仅当请求显式带探测头时改变返回形态，
      // 其它请求仍直接拿 data —— 既有调用方与错误码分支零影响。
      if (response.config.headers.get(REPLAY_PROBE_HEADER) === '1') {
        return {
          data: body.data,
          replayed: String(response.headers['idempotent-replayed'] ?? '') === 'true',
        } as never
      }
      // 成功：直接返回 data，减少调用方解包成本
      return body.data as never
    }
    // 非 ApiResponse 形态（例如二进制/文件流）原样返回
    return response as never
  },
  (error) => {
    const status: number | undefined = error?.response?.status
    const body = error?.response?.data

    if (status === 401) {
      // 未授权：清除 token 并跳转登录页（前端体验层处理，真正鉴权在服务端）
      clearStoredToken()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }

    // 服务端错误同样是统一包络（契约 §2.3）：转成携带 code/details 的结构化错误
    if (isApiEnvelope(body)) {
      return Promise.reject(toApiError(body, status))
    }
    return Promise.reject(error)
  },
)

/**
 * 带幂等键的 POST（写操作统一入口，`API_CONTRACT §6.3`）。
 *
 * ★ 幂等键由调用方生成并**在一次用户意图内复用**（重试不换键），见 `utils/idempotency.ts`。
 * ★ 返回 `{ data, replayed }`：`replayed` 取自响应头 `Idempotent-Replayed: true`，
 *   为 `true` 时前端应提示「该操作已提交过」，无该头按新执行处理。
 */
export function postWithIdempotency<T>(
  url: string,
  body: unknown,
  idempotencyKey: string,
): Promise<ReplayableResult<T>> {
  return http.post<unknown, ReplayableResult<T>>(url, body, {
    headers: {
      'Idempotency-Key': idempotencyKey,
      [REPLAY_PROBE_HEADER]: '1',
    },
  })
}

export default http
