// 认证模块（骨架占位）。
import http from './http'
import type { LoginRequest, LoginResult } from '@/types/auth'

// TODO: 接入真实登录接口。前端仅负责调用，真正的鉴权与权限分配在服务端。
export function login(payload: LoginRequest): Promise<LoginResult> {
  return http.post<unknown, LoginResult>('/auth/login', payload)
}

export function logout(): Promise<void> {
  return http.post<unknown, void>('/auth/logout')
}

export function getCurrentUser(): Promise<unknown> {
  return http.get<unknown, unknown>('/auth/me')
}
