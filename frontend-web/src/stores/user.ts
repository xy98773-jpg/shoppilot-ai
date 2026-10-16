// 用户与令牌状态（Pinia）——`stores → api → types`，符合 PROJECT_STRUCTURE §6.1 依赖方向。
//
// ★ 口径（M2-T12 修正，源自 `API_CONTRACT §11.1`）：
//   - store **直接持有 wire 形状**（snake_case）：`user.user_id` / `user.display_name`；
//     **不再**做 camelCase 归一化（旧版 `userId` / `displayName` / `roles[]` 与 wire 错位）。
//   - `role` 是**单数**（wire `data.role`），不是数组。
//   - Access Token 持久化到 localStorage（键名与 `api/http.ts` 一致，避免 api 层静态依赖本 store）；
//     **Refresh Token 仅驻内存**（不落盘；刷新页面后由服务端下发的 HttpOnly Cookie 兜底）。
//   - `permissions` **不持久化**，每次会话由 `GET /auth/me` 重新水合（服务端唯一事实来源）。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { TOKEN_STORAGE_KEY } from '@/api/http'
import { login as loginApi, logout as logoutApi, me as meApi } from '@/api/auth'
import type { AuthUserSummary, LoginRequest } from '@/types/auth'
import { useTeamStore } from './team'

export const useUserStore = defineStore('user', () => {
  /** Access Token（持久化到 localStorage）。 */
  const token = ref<string>(localStorage.getItem(TOKEN_STORAGE_KEY) || '')
  /** Refresh Token（仅内存；用于显式登出/刷新时随请求体提交）。 */
  const refreshToken = ref<string>('')
  /** 当前用户摘要（wire 形状，snake_case）。 */
  const user = ref<AuthUserSummary | null>(null)
  /** 权限位（`USER_ROLES §2.2` 矩阵 14 行；仅用于按钮显隐，非安全边界）。 */
  const permissions = ref<Record<string, boolean>>({})
  /** 是否已完成 `/auth/me` 水合（避免重复请求）。 */
  const hydrated = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const userId = computed(() => user.value?.user_id ?? null)
  const displayName = computed(() => user.value?.display_name ?? '')

  function setToken(value: string): void {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_STORAGE_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  }

  function setRefreshToken(value: string): void {
    refreshToken.value = value
  }

  /**
   * 应用服务端下发的会话（登录 / 刷新 / `/auth/me` 共用同一形状）。
   * @param nextUser  当前用户摘要
   * @param nextRole  当前团队内角色名（单数）
   * @param nextPerms 权限位（可缺省，如登录响应不含权限位）
   */
  function applySession(
    nextUser: AuthUserSummary | null,
    nextRole: string,
    nextPerms?: Record<string, boolean>,
  ): void {
    user.value = nextUser
    if (nextPerms) {
      permissions.value = nextPerms
    }
    useTeamStore().setRole(nextRole)
  }

  /**
   * 会话水合：凭 Access Token 调 `GET /auth/me`，重建 user / team / role / permissions。
   * 用于「刷新页面后」与「登录成功后」两条路径（保证权限位只有一个来源）。
   */
  async function hydrate(): Promise<void> {
    const result = await meApi()
    user.value = result.user
    permissions.value = result.permissions ?? {}
    useTeamStore().setSession(result.team, result.role)
    hydrated.value = true
  }

  /** 登录：调用契约端点 → 写入令牌 → 水合会话（含权限位与团队）。 */
  async function signIn(payload: LoginRequest): Promise<void> {
    const result = await loginApi(payload)
    setToken(result.accessToken)
    setRefreshToken(result.refreshToken)
    // 先落一份登录响应中的 user/team/role（保证即使 /me 临时失败也有基础展示数据）
    applySession(result.user, result.role)
    useTeamStore().setSession(result.team, result.role)
    try {
      await hydrate()
    } catch {
      // `/me` 失败不阻断登录（401 由 http.ts 统一回登）；基础会话已由登录响应写入。
      hydrated.value = false
    }
  }

  /** 清空本地会话状态（不调服务端；用于 401 回登或本地重置）。 */
  function reset(): void {
    token.value = ''
    refreshToken.value = ''
    user.value = null
    permissions.value = {}
    hydrated.value = false
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    useTeamStore().clear()
  }

  /** 登出：先尽力通知服务端失效 Refresh Token，无论成败都清空本地状态。 */
  async function signOut(): Promise<void> {
    try {
      await logoutApi(refreshToken.value ? { refreshToken: refreshToken.value } : {})
    } catch {
      // 幂等：服务端重复登出不报错；网络异常亦不阻断本地登出。
    } finally {
      reset()
    }
  }

  return {
    token,
    refreshToken,
    user,
    permissions,
    hydrated,
    isLoggedIn,
    userId,
    displayName,
    setToken,
    setRefreshToken,
    applySession,
    hydrate,
    signIn,
    reset,
    signOut,
  }
})
