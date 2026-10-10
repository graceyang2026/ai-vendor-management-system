/**
 * 审计日志展示层辅助（视图专用）。
 * 职责：透传后端枚举字典（@/constants/auditDictionary），并补充一项视图专属降级解析：
 * - getEntityLabelOrId：后端不返回目标对象名称时的降级展示。
 * 组件统一依赖本文件（本文件 `export *` 出字典的全部解析函数），不在组件内硬编码中文动作名。
 *
 * 动作码文案/颜色的唯一权威源是 @/constants/auditDictionary（后端 AuditAction 全 11 码，
 * 用户域 CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER 均为后端真实码，见
 * srm-backend AuditAction.java + docs/api-spec.md 第 5 节 + 数据库表设计第 8 节）。
 * 曾经为前端自造角色占位码兜底的 ADMIN_ACTION_LABEL_CN / ADMIN_ACTION_TAG_TYPE
 * 两张表连同 resolveActionLabel / resolveActionTagType 已随占位码删除一并移除：
 * 解析顺序收敛为「后端权威字典优先 → 未知码原样回显（颜色 info）」，
 * 统一由 getActionLabel / getActionTagType 承担，杜绝同一动作两处双源漂移。
 */

import type { AuditLogRecord } from '@/types/audit-log'
import { getEntityTypeLabel } from '@/constants/auditDictionary'

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
