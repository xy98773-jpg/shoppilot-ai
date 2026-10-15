// 路由守卫（仅体验层）。
// 红线：真正的登录鉴权与权限判定在服务端。前端守卫只做体验层拦截：
// - 未登录访问需鉴权页面 → 跳 /login
// - 已登录访问 /login → 跳首页
// - 角色不足（体验层） → 跳 403 提示占位
import type { Router } from 'vue-router'
import router from './index'
import { useUserStore } from '@/stores/user'
import { useTeamStore } from '@/stores/team'

function installGuards(r: Router): void {
  r.beforeEach((to) => {
    const requiresAuth = to.meta.requiresAuth !== false
    const userStore = useUserStore()
    const loggedIn = userStore.isLoggedIn

    // 1) 未登录且目标需要鉴权 → 跳转登录页
    if (requiresAuth && !loggedIn) {
      return { path: '/login', query: { redirect: to.fullPath } }
    }

    // 2) 已登录访问登录页 → 跳首页
    if (to.name === 'login' && loggedIn) {
      return { path: '/' }
    }

    // 3) 前端角色占位拦截（体验层，非真正鉴权）
    const allowedRoles = (to.meta.roles as string[] | undefined) ?? undefined
    if (allowedRoles && allowedRoles.length > 0) {
      const teamStore = useTeamStore()
      const currentRole = teamStore.role || userStore.userInfo?.roles?.[0] || ''
      // 体验层：仅当明确配置了角色且当前角色不在允许列表时拦截。
      // 注意：若用户尚未加载团队角色，放行交由服务端最终判定。
      if (currentRole && !allowedRoles.includes(currentRole)) {
        // TODO: 跳转统一的 403 页面（当前用占位提示）
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
