// 用户与令牌状态（Pinia）。
// 令牌持久化到 localStorage，键名与 api/http.ts 保持一致，避免 api 层静态依赖本 store。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { TOKEN_STORAGE_KEY } from '@/api/http'

export interface UserInfo {
  userId: string
  username: string
  displayName: string
  /** 角色列表（体验层展示用，真正的权限判定在服务端）。 */
  roles: string[]
}

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem(TOKEN_STORAGE_KEY) || '')
  const userInfo = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)

  function setToken(value: string): void {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_STORAGE_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  }

  function setUserInfo(info: UserInfo): void {
    userInfo.value = info
  }

  function logout(): void {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  return { token, userInfo, isLoggedIn, setToken, setUserInfo, logout }
})
