/**
 * 审计日志展示层辅助（视图专用）。
 * 职责：透传后端枚举字典（@/constants/auditDictionary），并补充两项视图专属解析：
 * - getEntityLabelOrId：后端不返回目标对象名称时的降级展示；
 * - resolveActionLabel / resolveActionTagType：兼容 ADMIN 模块的占位动作码。
 * 组件统一依赖本文件，不在组件内硬编码中文动作名。
 *
 * 动作码文案/颜色的唯一权威源是 @/constants/auditDictionary（后端 AuditAction 全 11 码；
 * 用户域 CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER 后端已定义，
 * 见 srm-backend AuditAction.java + docs/api-spec.md 第 5 节 + 数据库表设计第 8 节）。
 * 本文件的 ADMIN_* 两张表只服务于后端尚未定义的占位码（实际仅剩 UPDATE_ROLE：
 * 后端资料/角色变动统一用 UPDATE_USER 落审计，见 docs/用户管理接口规格增补稿 §3.4），
 * 解析顺序为"字典命中优先 → 占位表兜底"，避免同一动作两处双源漂移。
 */

import { ADMIN_ACTION_LABEL_CN, ADMIN_PENDING_ACTION } from '@/types/admin-log'
import type { AdminPendingActionCode } from '@/types/admin-log'
import type { AuditLogRecord } from '@/types/audit-log'
import { AUDIT_ACTION, getActionLabel, getActionTagType, getEntityTypeLabel } from '@/constants/auditDictionary'
import type { ElTagType } from '@/constants/auditDictionary'

export * from '@/constants/auditDictionary'

/** 后端 AuditAction 已定义码集合：这些码一律走 auditDictionary，占位表只兜字典之外的码。 */
const BACKEND_ACTION_CODES: ReadonlySet<string> = new Set<string>(Object.values(AUDIT_ACTION))

/**
 * 目标对象展示名：后端 AuditLogResponse 不返回名称，
 * 有扩展字段 entity_name 时用之，否则退化为"对象类型 + ID"，避免出现空白列。
 */
export function getEntityLabelOrId(
  record: Pick<AuditLogRecord, 'entity_type' | 'entity_id' | 'entity_name'>,
): string {
  if (record.entity_name) return record.entity_name
  return `${getEntityTypeLabel(record.entity_type)} #${record.entity_id ?? '-'}`
}

/**
 * 占位码颜色（Partial：仅字典未收录的码生效）。
 * 用户域四码的颜色权威源已上移至 auditDictionary.ACTION_TAG_TYPE，本表保留原值仅为兜底参考。
 */
const ADMIN_ACTION_TAG_TYPE: Partial<Record<AdminPendingActionCode, ElTagType>> = {
  [ADMIN_PENDING_ACTION.CREATE_USER]: 'success',
  [ADMIN_PENDING_ACTION.ENABLE_USER]: 'success',
  [ADMIN_PENDING_ACTION.UPDATE_ROLE]: 'warning',
  [ADMIN_PENDING_ACTION.DISABLE_USER]: 'danger',
}

/** 动作中文：优先后端 AuditAction 字典（权威源），字典未收录的占位码才落到 ADMIN 表。 */
export function resolveActionLabel(code?: string | null): string {
  if (!code) return '-'
  if (BACKEND_ACTION_CODES.has(code)) return getActionLabel(code)
  return ADMIN_ACTION_LABEL_CN[code as AdminPendingActionCode] ?? code
}

/** 动作颜色：先查后端字典，再查 ADMIN 占位码，未识别码统一 info。 */
export function resolveActionTagType(code?: string | null): ElTagType {
  if (!code) return 'info'
  if (BACKEND_ACTION_CODES.has(code)) return getActionTagType(code)
  return ADMIN_ACTION_TAG_TYPE[code as AdminPendingActionCode] ?? 'info'
}
