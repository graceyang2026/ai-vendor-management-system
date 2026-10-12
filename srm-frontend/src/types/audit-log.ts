/**
 * 审核员（AUDITOR）视角的操作与审批日志类型定义。
 *
 * 字段契约严格对齐后端 docs/api-spec.md 第 5 节 AuditLogResponse
 * （实现：srm-backend AuditLogResponse.java + audit_log 表 schema.sql 第 7 节），
 * 全链路 snake_case；action / entity_type / old_status / new_status 传后端枚举码（见
 * @/constants/auditDictionary），中文文案只在展示层映射。
 */

import type { AuditActionCode, EntityTypeCode } from '@/constants/auditDictionary'

/**
 * 审计日志记录（后端 AuditLogResponse 唯一形态，三个日志模块共用）。
 * snake_case 与 JSON 字段名一一对应，禁止 camelCase。
 */
export interface AuditLogRecord {
  id: string | number
  /** 业务对象类型码，见 ENTITY_TYPE */
  entity_type: EntityTypeCode | string
  /** 业务对象 ID（供应商 ID / 申请单 ID / 用户 ID） */
  entity_id: string | number | null
  operator_id: string | number | null
  /** audit_log 表不存姓名，后端经 sys_user.real_name 反查填充 */
  operator_name: string
  /** 操作人角色码：ADMIN / STAFF / AUDITOR */
  operator_role: string | null
  /** 动作码，见 AUDIT_ACTION（不是 action_type，也不是中文） */
  action: AuditActionCode | string
  old_status: string | null
  new_status: string | null
  /** 操作结果码：SUCCESS / REJECTED */
  result: string | null
  /** 审核意见 / 驳回原因 / 审批说明 */
  comment: string | null
  /** ISO-8601（后端 LocalDateTime），展示用 formatCreatedAt 转换 */
  created_at: string
  /**
   * 目标对象名称（如供应商名、账号名）。后端 AuditLogResponse 当前不返回，
   * 属前端请求的扩展字段（见 docs/backend-interface-design.md 待补项）；
   * 缺失时列表按 entity_type + entity_id 展示，兜底示例数据可携带。
   */
  entity_name?: string
}

/** 审核员视角日志项（与通用记录同构，保留语义化别名供审计控制台使用）。 */
export type ReviewLogItem = AuditLogRecord

/**
 * 日志查询参数：仅保留后端 AuditLogController 已支持的 entity_type / entity_id / page / page_size。
 * action 为扩展筛选参数（后端补齐前会被忽略），取值必须是动作码。
 */
export interface ReviewLogQueryParams {
  entity_type?: EntityTypeCode | string
  entity_id?: string | number
  /** 扩展参数：动作码筛选 */
  action?: AuditActionCode | string
  /** 扩展参数：目标对象名称模糊筛选（后端需联表或前端反查，当前被忽略） */
  entity_name?: string
  /** 扩展参数：操作人姓名模糊筛选（后端 operator_name 由 sys_user 反查，当前不支持作筛选条件） */
  operator_name?: string
  /** 扩展参数：起始日期 YYYY-MM-DD（后端当前仅有 entity_type/entity_id/page/page_size） */
  start_date?: string
  /** 扩展参数：结束日期 YYYY-MM-DD */
  end_date?: string
  page: number
  page_size: number
}

/**
 * 审核员（AUDITOR）控制台涉及的业务对象类型 —— 业务域四实体，审核员日志页 entity_type 的唯一取值源：
 * - 准入审核 = SUPPLIER
 * - 绩效复核 = PERFORMANCE_EVALUATION
 * - 停用/恢复/淘汰终审 = LIFECYCLE_REQUEST
 * - 存续期资质单独审批 = SUPPLIER_QUALIFICATION（`PUT /qualifications/{id}/review` 的留痕）
 *
 * 口径依据：docs/api-spec.md §5「entity_type 参数取值规则」—— 用户裁决 20261012 选 A，
 * 审核员视角由三实体扩为四实体（存续期资质单独审批的留痕须对审核员可见），旧"三实体"口径作废；
 * USER 域（CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER，按权限矩阵仅 ADMIN 触发）
 * 始终排除在审核员视角之外，只在管理员日志页展示。
 * 消费方一律 `AUDITOR_ENTITY_TYPES.join(',')` 拼逗号多值（后端按 IN 过滤），扩值即全局生效，
 * 禁止在任何组件/请求封装里散点硬编码 entity_type 串值。
 */
export const AUDITOR_ENTITY_TYPES = [
  'SUPPLIER',
  'LIFECYCLE_REQUEST',
  'PERFORMANCE_EVALUATION',
  'SUPPLIER_QUALIFICATION',
] as const satisfies readonly EntityTypeCode[]
