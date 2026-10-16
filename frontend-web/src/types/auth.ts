// 认证相关类型（纯类型，不依赖任何其它层）。
//
// ★ 字段形态权威来源：`docs/03-api/API_CONTRACT.md §11.1`（登录成功响应）与
//   `§10.1`（identity 端点）。
// ★ 命名口径（★ 极易做错）：契约中 **token 相关字段是 camelCase**
//   （`accessToken` / `refreshToken` / `tokenType` / `expiresIn`），
//   而 **user / team 内嵌对象是 snake_case**（`user_id` / `display_name` / `team_id`
//   / `team_name` / `default_timezone`）。二者混排，故本文件**逐字段镜像 wire**，
//   **不做任何 camelCase 归一化**（对应后端 `AuthTokenResponse` / `UserSummary` / `TeamSummary`）。
import type { ApiResponse } from './common'

/** 登录请求体（契约 §11.1：`username` 为登录名或邮箱；`password` 明文经 HTTPS 传输）。 */
export interface LoginRequest {
  username: string
  password: string
}

/** 刷新令牌请求体（契约 §10.1：Refresh Token 可来自请求体或 Cookie，二者取先非空者）。 */
export interface RefreshRequest {
  refreshToken?: string
}

/** 登出请求体（契约 §10.1：可选携带 Refresh Token；缺省时服务端读 Cookie）。 */
export interface LogoutRequest {
  refreshToken?: string
}

/** 用户摘要（wire `data.user`，契约 §11.1，snake_case）。 */
export interface AuthUserSummary {
  user_id: number
  username: string
  /** 展示名（契约字段名为 snake_case 的 `display_name`）。 */
  display_name: string
  /** 用户状态（`ACTIVE` / `DISABLED` 等，语义由服务端定义）。 */
  status: string
}

/** 团队摘要（wire `data.team`，契约 §11.1，snake_case）。 */
export interface AuthTeamSummary {
  team_id: number
  team_name: string
  /** 团队默认时区（如 `Asia/Shanghai`）。 */
  default_timezone: string
}

/**
 * 令牌签发结果（`POST /auth/login` 与 `POST /auth/refresh` 的 `data`）。
 *
 * ★ 逐字段镜像契约 §11.1：token 段 camelCase、user/team 段 snake_case。
 * `refresh` 为**轮换**语义：返回新的 access + 新的 refresh，旧 refresh 立即失效。
 */
export interface LoginResult {
  accessToken: string
  refreshToken: string
  tokenType: string
  /** Access Token 有效期（秒），D-01 约定 7200（2h）。 */
  expiresIn: number
  user: AuthUserSummary
  team: AuthTeamSummary
  role: string
}

/** 刷新响应与登录响应同形（契约 §10.1 `refresh` 返回同一 `AuthTokenResponse`）。 */
export type RefreshResult = LoginResult

/**
 * 当前登录态（`GET /auth/me` 的 `data`）。
 *
 * `permissions`：`USER_ROLES.md §2.2` 权限矩阵 14 行的布尔位，key 为**小写下划线**能力名
 * （如 `master_data_manage` / `view_audit`），value 由服务端**矩阵直查**得出。
 * 前端**仅用于按钮显隐**（体验层）——**服务端仍是唯一安全边界**（`USER_ROLES §4`）。
 */
export interface MeResult {
  user: AuthUserSummary
  team: AuthTeamSummary
  role: string
  permissions: Record<string, boolean>
}

export type LoginApiResponse = ApiResponse<LoginResult>
export type MeApiResponse = ApiResponse<MeResult>
