/**
 * 系统操作日志（管理员 ADMIN）类型定义。
 * 复用后端统一契约 AuditLogRecord（docs/api-spec.md 第 5 节）：管理动作的业务对象是用户账号，
 * 故 entity_type='USER'、entity_id=目标账号 ID、comment=详细变动说明；
 * 原型自定义的 action_type / target_object / detail 三个字段后端不存在，已按真实契约收敛。
 */

import type { AuditLogRecord, ReviewLogQueryParams } from '@/types/audit-log'

/** 管理日志项：与通用记录同构（entity_type=USER）。 */
export type AdminLogItem = AuditLogRecord

/** 查询参数（同一后端端点，entity_type 固定 USER）。 */
export type AdminLogQueryParams = ReviewLogQueryParams

/** 待后端补充的用户管理动作占位码（后端 AuditAction 目前只有 7 个审批类码值）。 */
export const ADMIN_PENDING_ACTION = {
  CREATE_USER: 'CREATE_USER',
  UPDATE_ROLE: 'UPDATE_ROLE',
  ENABLE_USER: 'ENABLE_USER',
  DISABLE_USER: 'DISABLE_USER',
} as const
export type AdminPendingActionCode = (typeof ADMIN_PENDING_ACTION)[keyof typeof ADMIN_PENDING_ACTION]

/**
 * 管理日志动作筛选（value=占位码，label=中文）。
 * 红线：这 4 个码后端尚未定义，接入真实筛选前必须先与后端在 AuditAction 中对齐命名。
 */
export const ADMIN_ACTION_OPTIONS: { value: AdminPendingActionCode; label: string }[] = [
  { value: ADMIN_PENDING_ACTION.CREATE_USER, label: '新增用户' },
  { value: ADMIN_PENDING_ACTION.UPDATE_ROLE, label: '调整用户角色' },
  { value: ADMIN_PENDING_ACTION.DISABLE_USER, label: '停用账号' },
  { value: ADMIN_PENDING_ACTION.ENABLE_USER, label: '启用账号' },
]

/** 占位码中文（真实审批码仍由 @/constants/auditDictionary 解析）。 */
export const ADMIN_ACTION_LABEL_CN: Record<AdminPendingActionCode, string> = {
  [ADMIN_PENDING_ACTION.CREATE_USER]: '新增用户',
  [ADMIN_PENDING_ACTION.UPDATE_ROLE]: '调整用户角色',
  [ADMIN_PENDING_ACTION.ENABLE_USER]: '启用账号',
  [ADMIN_PENDING_ACTION.DISABLE_USER]: '停用账号',
}
