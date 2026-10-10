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

/**
 * 管理页动作筛选码。后端 AuditAction 已扩至 11 码，其中用户域 CREATE_USER / UPDATE_USER /
 * ENABLE_USER / DISABLE_USER 为真实码（docs/api-spec.md 第 5 节 + 数据库表设计第 8 节），
 * 本组常量作为码值别名保留以兼容既有页面引用；
 * 例外：UPDATE_ROLE 仍是前端占位码（后端"资料/角色调整"统一落 UPDATE_USER，
 * 见 docs/用户管理接口规格增补稿 §3.4），命名待与后端确认后收敛，本次不自改。
 */
export const ADMIN_PENDING_ACTION = {
  CREATE_USER: 'CREATE_USER',
  UPDATE_ROLE: 'UPDATE_ROLE',
  ENABLE_USER: 'ENABLE_USER',
  DISABLE_USER: 'DISABLE_USER',
} as const
export type AdminPendingActionCode = (typeof ADMIN_PENDING_ACTION)[keyof typeof ADMIN_PENDING_ACTION]

/** 管理日志动作筛选（value=动作码，label=中文；UPDATE_ROLE 为占位码，见上方说明）。 */
export const ADMIN_ACTION_OPTIONS: { value: AdminPendingActionCode; label: string }[] = [
  { value: ADMIN_PENDING_ACTION.CREATE_USER, label: '新增用户' },
  { value: ADMIN_PENDING_ACTION.UPDATE_ROLE, label: '调整用户角色' },
  { value: ADMIN_PENDING_ACTION.DISABLE_USER, label: '停用账号' },
  { value: ADMIN_PENDING_ACTION.ENABLE_USER, label: '启用账号' },
]

/**
 * 占位码中文（真实审批码仍由 @/constants/auditDictionary 解析）。
 * 权威源说明：后端已定义的码（含用户域 CREATE_USER/UPDATE_USER/ENABLE_USER/DISABLE_USER）
 * 一律以 auditDictionary.ACTION_LABEL_CN 为准，本表只兜后端未定义的占位码（现仅 UPDATE_ROLE）；
 * 同名的三条文案与字典口径一致，不得在此另改说法（解析顺序见 @/constants/auditLogView）。
 */
export const ADMIN_ACTION_LABEL_CN: Record<AdminPendingActionCode, string> = {
  [ADMIN_PENDING_ACTION.CREATE_USER]: '新增用户',
  [ADMIN_PENDING_ACTION.UPDATE_ROLE]: '调整用户角色',
  [ADMIN_PENDING_ACTION.ENABLE_USER]: '启用账号',
  [ADMIN_PENDING_ACTION.DISABLE_USER]: '停用账号',
}
