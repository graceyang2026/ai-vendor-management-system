/**
 * 认证相关类型（docs/api-spec.md 第 1 节 + 底座功能契约文档）。
 * 字段名与 JSON 保持一致（snake_case），不做大小写转换。
 */

/** 角色枚举（项目红线枚举）：原型 admin/staff/audit 分别映射到 ADMIN/STAFF/AUDITOR。 */
export type RoleType = 'ADMIN' | 'STAFF' | 'AUDITOR'

/** 登录用户信息。 */
export interface UserInfo {
  user_id: number
  username: string
  real_name: string
  role: RoleType
}

/** 登录请求参数。 */
export interface LoginParams {
  username: string
  password: string
  role_type: RoleType
}

/** 登录响应 data。 */
export interface LoginResult {
  access_token: string
  user_info: UserInfo
}
