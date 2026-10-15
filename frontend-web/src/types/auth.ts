// 认证相关类型（占位，纯类型）。
import type { ApiResponse } from './common'

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  userId: string
  /** 过期时间（时间戳，毫秒）。 */
  expiresAt: number
}

export type LoginApiResponse = ApiResponse<LoginResult>
