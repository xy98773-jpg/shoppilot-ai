// 路由守卫（仅体验层）。
// 红线：真正的登录鉴权、团队隔离与角色判定在服务端（`TEAM-2001` / `TEAM-2003`）。
// 前端守卫只做体验层拦截：
// - 已持有令牌但会话未水合 → 先调 `GET /auth/me` 重建 user/team/role/permissions（刷新页面场景）；
// - 未登录访问需鉴权页面 → 跳 /login（携带 redirect）；
// - 已登录访问 /login → 跳首页；
// - 角色不足（体验层，按 `meta.roles`） → 跳 /403 页面。
import type { Router } from 'vue-router'
import router from './index'
import { useUserStore } from '@/stores/user'
import { useTeamStore } from '@/stores/team'

function installGuards(r: Router): void {
  r.beforeEach(async (to) => {
    const requiresAuth = to.meta.requiresAuth !== false
    const userStore = useUserStore()

    // 0) 会话水合：本地有 Access Token 但尚未拉取过 /auth/me → 先水合，
    //    避免"有 token 但 user/team/role/permissions 为空"导致顶栏空白与角色误判。
    if (userStore.isLoggedIn && !userStore.hydrated) {
      try {
        await userStore.hydrate()
      } catch {
        // 水合失败（含 401：http.ts 已清 token 并回登）→ 本地兜底清态
        userStore.reset()
      }
    }

    const authed = userStore.isLoggedIn

    // 1) 未登录且目标需要鉴权 → 跳转登录页
    if (requiresAuth && !authed) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }

    // 2) 已登录访问登录页 → 跳首页
    if (to.name === 'login' && authed) {
      return { path: '/' }
    }

    // 3) 前端角色占位拦截（体验层，非真正鉴权）
    const allowedRoles = (to.meta.roles as string[] | undefined) ?? undefined
    if (allowedRoles && allowedRoles.length > 0) {
      const role = useTeamStore().role
      // 角色未加载时放行，交由服务端最终判定（避免误拦）。
      if (role && !allowedRoles.includes(role)) {
        return { path: '/403' }
      }
    }

    return true
  })

  r.afterEach((to) => {
    const title = (to.meta.title as string | undefined) ?? 'ShopPilot AI'
    document.title = `${title} · ShopPilot AI`
  })
}

installGuards(router)

export default router
