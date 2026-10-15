// axios 实例与拦截器（前端唯一允许 import axios 的文件）。
//
// 红线约束（PROJECT_STRUCTURE §6.1）：
// - 本文件为 api 层，不得静态 import stores（避免循环依赖）。
// - Token 注入直接从 localStorage 读取，不依赖 stores/user。
// - 错误码统一按 ApiResponse 形态处理。
//
// 真正的鉴权与业务规则均在服务端，前端仅做体验层处理。

import axios, {
  type AxiosInstance,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import type { ApiResponse } from '@/types/common'

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

/** 生成请求唯一标识 X-Request-Id（无加密随机源时退化为时间戳+随机数）。 */
function generateRequestId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const http: AxiosInstance = axios.create({
  baseURL: '/api',
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
    const requestId = (config.headers.get('X-Request-Id') as string) || generateRequestId()
    config.headers.set('X-Request-Id', requestId)
    return config
  },
  (error) => Promise.reject(error),
)

// 响应拦截器：按 ApiResponse 形态统一处理错误码
http.interceptors.response.use(
  (response: AxiosResponse<ApiResponse>) => {
    const body = response.data
    // 仅当响应体符合 ApiResponse 形态时做 code 校验
    if (body && typeof body.code === 'number') {
      if (body.code !== 0 && body.code !== 200) {
        // 非成功 code：抛出错误交由调用方处理（或在此统一提示）
        const err = new Error(body.message || `请求失败 (code=${body.code})`) as Error & {
          code?: number
        }
        err.code = body.code
        return Promise.reject(err)
      }
      // 业务成功：直接返回 data，减少调用方解包成本
      return response.data.data as never
    }
    // 非 ApiResponse 形态（例如二进制/文件流）原样返回
    return response as never
  },
  (error) => {
    const status = error?.response?.status
    if (status === 401) {
      // 未授权：清除 token 并跳转登录页（前端体验层处理，真正鉴权在服务端）
      clearStoredToken()
      if (window.location.pathname !== '/login') {
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  },
)

export default http
