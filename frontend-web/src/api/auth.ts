// 认证模块（identity）——契约 `API_CONTRACT §10.1`。
//
// ★ 红线：api 层不 import stores；令牌注入由 http.ts 拦截器统一完成（PROJECT_STRUCTURE §6.1）。
// ★ 本模块只做「调用 + 转发包络」，不做任何鉴权判定（服务端唯一安全边界）。
import http from './http'
import type {
  LoginRequest,
  LoginResult,
  LogoutRequest,
  MeResult,
  RefreshRequest,
  RefreshResult,
} from '@/types/auth'

/**
 * 登录（匿名）。
 * 成功 200；凭证错误 401 `AUTH-1002`；账号禁用 403 `AUTH-1003`；参数校验 400 `COM-0001`。
 */
export function login(payload: LoginRequest): Promise<LoginResult> {
  return http.post<unknown, LoginResult>('/auth/login', payload)
}

/**
 * 刷新令牌（匿名，幂等）。
 * Refresh Token 可来自请求体或 Cookie（服务端取先非空者）；成功即**轮换**（旧 Refresh 立即失效）。
 */
export function refresh(payload: RefreshRequest = {}): Promise<RefreshResult> {
  return http.post<unknown, RefreshResult>('/auth/refresh', payload)
}

/**
 * 登出（需认证，幂等）。使 Refresh Token 失效。
 */
export function logout(payload: LogoutRequest = {}): Promise<void> {
  return http.post<unknown, void>('/auth/logout', payload)
}

/**
 * 当前登录态（需认证）：用户 + 团队 + 角色 + 权限位。
 *
 * ★ 前端的**会话水合入口**：刷新页面后凭 localStorage 中的 Access Token 调此端点，
 * 重建 user / team / role / permissions（避免把权限位持久化到本地）。
 */
export function me(): Promise<MeResult> {
  return http.get<unknown, MeResult>('/auth/me')
}
