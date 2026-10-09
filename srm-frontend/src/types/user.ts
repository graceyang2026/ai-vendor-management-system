/**
 * 用户账号管理（ADMIN）类型定义。
 * 字段严格对齐 docs/api-spec.md 第 6 节 UserResponse/UserCreateRequest/UserUpdateRequest
 * 与后端 com.srm.core.dto.user.*（Jackson SNAKE_CASE 全局策略 + 显式 @JsonProperty）。
 * 红线：接口字段禁止 camelCase；后端无 email 字段，前端不得添加。
 */

import type { RoleType } from '@/types/auth'

/**
 * 用户列表项 = 后端 UserResponse 原文：
 * id / username / real_name / role / enabled / created_at。
 * 任务口径中的 account→username、name→real_name；
 * 状态在后端是 enabled: boolean，展示层派生 'ENABLED' | 'DISABLED' 码
 * （与 audit_log.old_status/new_status 记录口径一致，见 api-spec 第 6 节审计留痕）。
 */
export interface UserItem {
  id: number
  /** 登录账号（工号/账号，后端字段名是 username） */
  username: string
  /** 姓名（后端字段名是 real_name，非 name） */
  real_name: string
  /** 角色码：ADMIN / STAFF / AUDITOR（后端 Role 枚举 name()） */
  role: RoleType | string
  /** 启用状态（后端是 boolean，非字符串 status） */
  enabled: boolean
  /** ISO-8601 时间串（Jackson 默认序列化） */
  created_at?: string
}

/** 状态派生码（仅展示与筛选用，接口传输仍是 enabled boolean）。 */
export const USER_STATUS = {
  ENABLED: 'ENABLED',
  DISABLED: 'DISABLED',
} as const
export type UserStatusCode = (typeof USER_STATUS)[keyof typeof USER_STATUS]

/** 新增请求体 = UserCreateRequest（password 后端 @NotBlank 必填，原型缺此字段）。 */
export interface UserCreatePayload {
  username: string
  password: string
  real_name: string
  role: RoleType | string
}

/**
 * 编辑请求体 = UserUpdateRequest（仅 real_name/role 可改；
 * 出现 username/password 字段后端直接 40001 拒绝，故请求中严禁携带）。
 */
export interface UserUpdatePayload {
  real_name: string
  role: RoleType | string
}

/** 启停请求体 = UserStatusUpdateRequest（PATCH /users/{id}/status）。 */
export interface UserStatusPayload {
  enabled: boolean
}

/** 列表查询参数：后端 GET /users 目前仅消费 page/page_size，关键词与状态筛选在组件侧本地完成。 */
export interface UserQueryParams {
  page?: number
  page_size?: number
}

/** 角色筛选/表单选项（value=后端 Role 码，label=中文；文案与 auditDictionary.ROLE_LABEL_CN 同口径）。 */
export const USER_ROLE_OPTIONS: { value: RoleType; label: string }[] = [
  { value: 'STAFF', label: '业务员 (Staff)' },
  { value: 'AUDITOR', label: '审计员 (Auditor)' },
  { value: 'ADMIN', label: '系统管理员 (Admin)' },
]

/** 状态筛选选项（value=派生码，查询时换算 enabled boolean）。 */
export const USER_STATUS_OPTIONS: { value: UserStatusCode; label: string }[] = [
  { value: USER_STATUS.ENABLED, label: '已启用' },
  { value: USER_STATUS.DISABLED, label: '已停用' },
]

/** 角色 el-tag 颜色（沿用原型：Admin danger / Staff success / Auditor warning）。 */
export function getUserRoleTagType(role: string): 'danger' | 'success' | 'warning' {
  if (role === 'ADMIN') return 'danger'
  if (role === 'STAFF') return 'success'
  return 'warning'
}

/** enabled → 展示码与中文。 */
export function toUserStatusCode(enabled: boolean): UserStatusCode {
  return enabled ? USER_STATUS.ENABLED : USER_STATUS.DISABLED
}
