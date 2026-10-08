import { defineStore } from 'pinia'
import { loginApi, logoutApi } from '@/api/auth'
import type { LoginParams, RoleType, UserInfo } from '@/types/auth'

/** localStorage 存储键。 */
const TOKEN_KEY = 'srm_token'
const USER_INFO_KEY = 'srm_user_info'

function readStoredUserInfo(): UserInfo | null {
  const raw = localStorage.getItem(USER_INFO_KEY)
  if (!raw) {
    return null
  }
  try {
    return JSON.parse(raw) as UserInfo
  } catch {
    return null
  }
}

export const useUserStore = defineStore('user', {
  state: () => {
    const userInfo = readStoredUserInfo()
    return {
      token: localStorage.getItem(TOKEN_KEY) || '',
      userInfo,
      role: (userInfo?.role ?? '') as RoleType | '',
    }
  },
  getters: {
    isLoggedIn: (state) => !!state.token,
  },
  actions: {
    /** 登录：请求后端，成功后把 token/用户信息持久化到 localStorage 并存入 Pinia。 */
    async login(params: LoginParams) {
      const res = await loginApi(params)
      const data = res.data
      if (!data) {
        throw new Error(res.message || '登录失败')
      }
      this.token = data.access_token
      this.userInfo = data.user_info
      this.role = data.user_info.role
      localStorage.setItem(TOKEN_KEY, data.access_token)
      localStorage.setItem(USER_INFO_KEY, JSON.stringify(data.user_info))
    },
    /** 退出登录：通知后端（失败不阻断本地清理），并清空登录态。 */
    async logout() {
      try {
        await logoutApi()
      } catch {
        // 后端登出失败不影响本地登录态清理
      }
      this.reset()
    },
    /** 清空本地登录态（供 token 失效 40101 时调用）。 */
    reset() {
      this.token = ''
      this.userInfo = null
      this.role = ''
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_INFO_KEY)
    },
  },
})
