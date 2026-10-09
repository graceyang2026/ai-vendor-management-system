/**
 * 审计日志展示层辅助（视图专用）。
 * 职责：透传后端枚举字典（@/constants/auditDictionary），并补充两项视图专属解析：
 * - getEntityLabelOrId：后端不返回目标对象名称时的降级展示；
 * - resolveActionLabel / resolveActionTagType：兼容 ADMIN 模块的占位动作码。
 * 组件统一依赖本文件，不在组件内硬编码中文动作名。
 */

import { ADMIN_ACTION_LABEL_CN, ADMIN_PENDING_ACTION } from '@/types/admin-log'
import type { AdminPendingActionCode } from '@/types/admin-log'
import type { AuditLogRecord } from '@/types/audit-log'
import { getActionLabel, getActionTagType, getEntityTypeLabel } from '@/constants/auditDictionary'
import type { ElTagType } from '@/constants/auditDictionary'

export * from '@/constants/auditDictionary'

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

const ADMIN_ACTION_TAG_TYPE: Record<AdminPendingActionCode, ElTagType> = {
  [ADMIN_PENDING_ACTION.CREATE_USER]: 'success',
  [ADMIN_PENDING_ACTION.ENABLE_USER]: 'success',
  [ADMIN_PENDING_ACTION.UPDATE_ROLE]: 'warning',
  [ADMIN_PENDING_ACTION.DISABLE_USER]: 'danger',
}

/** 动作中文：优先后端 AuditAction 字典，再落到 ADMIN 占位码字典。 */
export function resolveActionLabel(code?: string | null): string {
  if (!code) return '-'
  return ADMIN_ACTION_LABEL_CN[code as AdminPendingActionCode] ?? getActionLabel(code)
}

/** 动作颜色：先查 ADMIN 占位码，再查后端枚举，未识别码统一 info。 */
export function resolveActionTagType(code?: string | null): ElTagType {
  if (!code) return 'info'
  return ADMIN_ACTION_TAG_TYPE[code as AdminPendingActionCode] ?? getActionTagType(code)
}
