import request from '@/utils/request'
import type { ApiResponse } from '@/types/api'
import type { LoginParams, LoginResult, RoleType, UserInfo } from '@/types/auth'

/**
 * 后端当前实际返回的扁平结构（docs/api-spec.md 第 1 节：token/user_id/username/real_name/role），
 * 与底座契约（access_token/user_info）做兼容适配，统一转换为 LoginResult。
 */
interface RawLoginData {
  token: string
  user_id: number
  username: string
  real_name: string
  role: RoleType
}

function normalizeLoginData(data: RawLoginData | LoginResult): LoginResult {
  if ('access_token' in data && 'user_info' in data) {
    return data
  }
  const raw = data as RawLoginData
  const userInfo: UserInfo = {
    user_id: raw.user_id,
    username: raw.username,
    real_name: raw.real_name,
    role: raw.role,
  }
  return { access_token: raw.token, user_info: userInfo }
}

/** 登录（POST /auth/login）。 */
export function loginApi(params: LoginParams): Promise<ApiResponse<LoginResult>> {
  return request.post<RawLoginData | LoginResult>('/auth/login', params).then((res) => ({
    ...res,
    data: res.data ? normalizeLoginData(res.data) : null,
  }))
}

/** 退出登录（POST /auth/logout）。 */
export function logoutApi(): Promise<ApiResponse<void>> {
  return request.post<void>('/auth/logout')
}
