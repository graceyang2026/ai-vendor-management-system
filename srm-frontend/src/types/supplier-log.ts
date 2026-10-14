/**
 * 业务员（STAFF）视角的操作与审批日志类型定义。
 * 复用后端统一契约 AuditLogRecord（docs/api-spec.md 第 5 节），字段 snake_case；
 * 取值一律用后端枚举码，中文仅在展示层经 @/constants/auditDictionary 映射。
 */

import type { AuditLogRecord, ReviewLogQueryParams } from '@/types/audit-log'

/** 业务员日志项：与通用记录同构（entity_type=SUPPLIER 为主）。 */
export type SupplierLogItem = AuditLogRecord

/** 查询参数与审核员同构（同一后端端点）。 */
export type SupplierLogQueryParams = ReviewLogQueryParams

/**
 * 供应商生命周期状态码 → 展示（原型中文口径由字典提供，此处仅保留列表页筛选项顺序）。
 * 取值见 api-spec 第 2 节 SupplierStatus。
 */
export const SUPPLIER_STATUS_CODES = [
  'DRAFT',
  'PENDING_REVIEW',
  'NORMAL',
  'RETURNED',
  'SUSPENDED',
  'ELIMINATED',
] as const

/**
 * 用户管理扩展审计码归二期（用户裁决 20261014）：原型业务员动作"新增档案 / 修改档案 / 逻辑删除草稿 /
 * 申请变更状态 / 申请恢复合作 / 绩效客观事实录入"中除"提交准入"= SUBMIT 外均无独立审计码，
 * MVP 维持后端 AuditAction 11 码（业务 7 + 用户域 4，权威源 AuditAction.java + docs/api-spec.md §0/§5），
 * 前端不发明占位码、不按不存在的码筛选；扩展码（曾建议 CREATE / UPDATE / DELETE_DRAFT / APPLY_LIFECYCLE）归二期落地。
 */
