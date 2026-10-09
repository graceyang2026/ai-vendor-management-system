/**
 * 审计日志后端枚举字典（唯一依据：srm-backend 源码 + docs/api-spec.md 第 5 节 + 数据库表设计第 8 节）。
 *
 * 分层原则：
 * - 【码值】本文件 XXX 常量对象 = 后端枚举 name()，是与 API 交互的唯一合法取值，禁止中文；
 * - 【文案】*_LABEL_CN 仅用于 UI 展示，绝不参与请求参数与状态判断；
 * - 【颜色】*_TAG_TYPE 供 el-tag 渲染。
 *
 * 后端来源：
 * - EntityType.java（SUPPLIER / PERFORMANCE_EVALUATION / PERFORMANCE_EVALUATION_DRAFT / LIFECYCLE_REQUEST / USER）
 * - AuditAction.java（7 个动作码）
 * - AuditResult.java（SUCCESS / REJECTED）
 * - api-spec 第 2/3/4 节：SupplierStatus / LifecycleRequestStatus / EvaluationStatus
 */

/** audit_log.entity_type —— 后端 EntityType 枚举。 */
export const ENTITY_TYPE = {
  SUPPLIER: 'SUPPLIER',
  PERFORMANCE_EVALUATION: 'PERFORMANCE_EVALUATION',
  PERFORMANCE_EVALUATION_DRAFT: 'PERFORMANCE_EVALUATION_DRAFT',
  LIFECYCLE_REQUEST: 'LIFECYCLE_REQUEST',
  USER: 'USER',
} as const
export type EntityTypeCode = (typeof ENTITY_TYPE)[keyof typeof ENTITY_TYPE]

/** audit_log.action —— 后端 AuditAction 枚举（已定义部分）。 */
export const AUDIT_ACTION = {
  SUBMIT: 'SUBMIT',
  AUDIT_APPROVE: 'AUDIT_APPROVE',
  AUDIT_REJECT: 'AUDIT_REJECT',
  REVIEW_APPROVE: 'REVIEW_APPROVE',
  REVIEW_REJECT: 'REVIEW_REJECT',
  DECISION_APPROVE: 'DECISION_APPROVE',
  DECISION_REJECT: 'DECISION_REJECT',
} as const
export type AuditActionCode = (typeof AUDIT_ACTION)[keyof typeof AUDIT_ACTION]

/**
 * audit_log.result —— 后端 AuditResult 枚举。
 * 注意：docs/SRM 后端数据库表设计.md 第 8 节写的是 SUCCESS/FAILED，与代码 AuditResult.java 不一致，
 * 以代码与 api-spec.md 第 5 节（SUCCESS / REJECTED）为准。
 */
export const AUDIT_RESULT = {
  SUCCESS: 'SUCCESS',
  REJECTED: 'REJECTED',
} as const
export type AuditResultCode = (typeof AUDIT_RESULT)[keyof typeof AUDIT_RESULT]

/** SupplierStatus（api-spec 第 2 节状态机）。 */
export const SUPPLIER_STATUS = {
  DRAFT: 'DRAFT',
  PENDING_REVIEW: 'PENDING_REVIEW',
  NORMAL: 'NORMAL',
  RETURNED: 'RETURNED',
  SUSPENDED: 'SUSPENDED',
  ELIMINATED: 'ELIMINATED',
} as const
export type SupplierStatusCode = (typeof SUPPLIER_STATUS)[keyof typeof SUPPLIER_STATUS]

/** LifecycleRequestStatus（api-spec 第 3 节）。 */
export const LIFECYCLE_REQUEST_STATUS = {
  PENDING: 'PENDING',
  APPROVED: 'APPROVED',
  REJECTED: 'REJECTED',
} as const

/** EvaluationStatus（api-spec 第 4 节）：PENDING_REVIEW 与 SupplierStatus 同码值，文案按域区分。 */
export const EVALUATION_STATUS = {
  PENDING_REVIEW: 'PENDING_REVIEW',
  APPROVED: 'APPROVED',
  RETURNED: 'RETURNED',
} as const

/** el-tag type 取值集合。 */
export type ElTagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/* ------------------------------------------------------------------ 中文文案（仅展示层） */

export const ENTITY_TYPE_LABEL_CN: Record<EntityTypeCode, string> = {
  [ENTITY_TYPE.SUPPLIER]: '供应商档案',
  [ENTITY_TYPE.PERFORMANCE_EVALUATION]: '绩效评价单',
  [ENTITY_TYPE.PERFORMANCE_EVALUATION_DRAFT]: '绩效草稿',
  [ENTITY_TYPE.LIFECYCLE_REQUEST]: '生命周期申请',
  [ENTITY_TYPE.USER]: '用户账号',
}

export const ACTION_LABEL_CN: Record<AuditActionCode, string> = {
  [AUDIT_ACTION.SUBMIT]: '提交送审',
  [AUDIT_ACTION.AUDIT_APPROVE]: '准入审核通过',
  [AUDIT_ACTION.AUDIT_REJECT]: '准入审核驳回',
  [AUDIT_ACTION.REVIEW_APPROVE]: '绩效复核通过',
  [AUDIT_ACTION.REVIEW_REJECT]: '绩效复核驳回',
  [AUDIT_ACTION.DECISION_APPROVE]: '状态变更批准',
  [AUDIT_ACTION.DECISION_REJECT]: '状态变更驳回',
}

export const RESULT_LABEL_CN: Record<AuditResultCode, string> = {
  [AUDIT_RESULT.SUCCESS]: '成功',
  [AUDIT_RESULT.REJECTED]: '被驳回',
}

/** 角色码 → 中文（Role 枚举：ADMIN/STAFF/AUDITOR）。 */
export const ROLE_LABEL_CN: Record<string, string> = {
  ADMIN: '系统管理员',
  STAFF: '业务员',
  AUDITOR: '审计员',
}

/**
 * old_status / new_status 中文文案：三套状态枚举存在同码值不同语义
 * （APPROVED 在生命周期域是"已批准"、在绩效域是"已复核通过"），
 * 故按域拆表，由 getStatusLabel(code, entity_type) 依据 entity_type 选口径，供应商域为主兜底。
 */
const SUPPLIER_STATUS_LABEL: Record<SupplierStatusCode, string> = {
  [SUPPLIER_STATUS.DRAFT]: '草稿/待提交',
  [SUPPLIER_STATUS.PENDING_REVIEW]: '待审核',
  [SUPPLIER_STATUS.NORMAL]: '正常/合作中',
  [SUPPLIER_STATUS.RETURNED]: '待修改/已驳回',
  [SUPPLIER_STATUS.SUSPENDED]: '停用',
  [SUPPLIER_STATUS.ELIMINATED]: '淘汰',
}

const LIFECYCLE_STATUS_LABEL: Record<string, string> = {
  [LIFECYCLE_REQUEST_STATUS.PENDING]: '待终审',
  [LIFECYCLE_REQUEST_STATUS.APPROVED]: '已批准',
  [LIFECYCLE_REQUEST_STATUS.REJECTED]: '已驳回',
}

const EVALUATION_STATUS_LABEL: Record<string, string> = {
  // EvaluationStatus = PENDING_REVIEW | APPROVED | RETURNED（PENDING_REVIEW 与供应商域同码不同义，按 entity_type 区分）
  PENDING_REVIEW: '待复核',
  APPROVED: '已复核通过',
  RETURNED: '已驳回重打分',
}

/** 非业务流程状态码：草稿单提交态、账号启用/停用、空值占位、角色码（调整用户角色场景）。 */
const STATUS_LABEL_COMMON: Record<string, string> = {
  SUBMITTED: '已提交',
  ENABLED: '启用',
  DISABLED: '停用',
  NONE: '无',
  // 调整用户角色场景：audit_log.old_status / new_status 落角色码
  ADMIN: '系统管理员',
  STAFF: '业务员',
  AUDITOR: '审计员',
}

/* ------------------------------------------------------------------ el-tag 颜色 */

const ACTION_TAG_TYPE: Record<AuditActionCode, ElTagType> = {
  [AUDIT_ACTION.SUBMIT]: 'primary',
  [AUDIT_ACTION.AUDIT_APPROVE]: 'success',
  [AUDIT_ACTION.AUDIT_REJECT]: 'danger',
  [AUDIT_ACTION.REVIEW_APPROVE]: 'success',
  [AUDIT_ACTION.REVIEW_REJECT]: 'danger',
  [AUDIT_ACTION.DECISION_APPROVE]: 'warning',
  [AUDIT_ACTION.DECISION_REJECT]: 'info',
}

/**
 * 状态色映射（沿用原型语义，键换成后端枚举码）：
 * 草稿/停用→info、待审核→warning、正常合作→success、待修改/淘汰→danger。
 * 跨域同码值（APPROVED/RETURNED/PENDING_REVIEW）颜色语义一致，故合并为一张表。
 */
const STATUS_TAG_TYPE: Record<string, ElTagType> = {
  [SUPPLIER_STATUS.DRAFT]: 'info',
  [SUPPLIER_STATUS.PENDING_REVIEW]: 'warning',
  [SUPPLIER_STATUS.NORMAL]: 'success',
  [SUPPLIER_STATUS.RETURNED]: 'danger',
  [SUPPLIER_STATUS.SUSPENDED]: 'info',
  [SUPPLIER_STATUS.ELIMINATED]: 'danger',
  [LIFECYCLE_REQUEST_STATUS.PENDING]: 'warning',
  [LIFECYCLE_REQUEST_STATUS.APPROVED]: 'success',
  [LIFECYCLE_REQUEST_STATUS.REJECTED]: 'danger',
  SUBMITTED: 'primary',
  ENABLED: 'success',
  DISABLED: 'info',
  NONE: 'info',
  ADMIN: 'danger',
  STAFF: 'success',
  AUDITOR: 'warning',
}

/* ------------------------------------------------------------------ 解析函数（未知码不报错、不显红） */

export function getEntityTypeLabel(code?: string | null): string {
  if (!code) return '-'
  return ENTITY_TYPE_LABEL_CN[code as EntityTypeCode] ?? code
}

/** 动作中文：已知码走字典；未知码原样回显（便于后端新增码时前端不崩）。 */
export function getActionLabel(code?: string | null): string {
  if (!code) return '-'
  return ACTION_LABEL_CN[code as AuditActionCode] ?? code
}

export function getActionTagType(code?: string | null): ElTagType {
  if (!code) return 'info'
  return ACTION_TAG_TYPE[code as AuditActionCode] ?? 'info'
}

/**
 * 状态中文：按 entity_type 选域口径（生命周期申请/绩效评价单的码值与供应商域重名但语义不同），
 * 未传 entity_type 时按 供应商域 → 生命周期 → 绩效 → 共用表 顺序兜底，最终原样回显未知码。
 */
export function getStatusLabel(code?: string | null, entityType?: string | null): string {
  if (!code) return '-'
  if (entityType === ENTITY_TYPE.LIFECYCLE_REQUEST) {
    return LIFECYCLE_STATUS_LABEL[code] ?? STATUS_LABEL_COMMON[code] ?? code
  }
  if (entityType === ENTITY_TYPE.PERFORMANCE_EVALUATION || entityType === ENTITY_TYPE.PERFORMANCE_EVALUATION_DRAFT) {
    return (
      EVALUATION_STATUS_LABEL[code] ?? SUPPLIER_STATUS_LABEL[code as SupplierStatusCode] ?? STATUS_LABEL_COMMON[code] ?? code
    )
  }
  return (
    SUPPLIER_STATUS_LABEL[code as SupplierStatusCode] ??
    LIFECYCLE_STATUS_LABEL[code] ??
    EVALUATION_STATUS_LABEL[code] ??
    STATUS_LABEL_COMMON[code] ??
    code
  )
}

/** 未知状态统一给 info 中性色，避免旧实现兜底 danger 导致满屏红。 */
export function getStatusTagType(code?: string | null): ElTagType {
  if (!code) return 'info'
  return STATUS_TAG_TYPE[code] ?? 'info'
}

export function getResultLabel(code?: string | null): string {
  if (!code) return '-'
  return RESULT_LABEL_CN[code as AuditResultCode] ?? code
}

/** 后端 LocalDateTime 默认序列化为 ISO-8601（2026-10-08T12:00:00），展示转空格分隔。 */
export function formatCreatedAt(value?: string | null): string {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

/** 操作人展示：real_name + 角色中文，如 张三（业务员）。 */
export function formatOperator(name?: string | null, role?: string | null): string {
  const display = name || '-'
  if (!role) return display
  return `${display}（${ROLE_LABEL_CN[role] ?? role}）`
}

/* ------------------------------------------------------------------ 下拉选项（value=码，label=中文） */

export interface ActionOption {
  value: AuditActionCode
  label: string
}

function toActionOptions(codes: AuditActionCode[]): ActionOption[] {
  return codes.map((value) => ({ value, label: ACTION_LABEL_CN[value] }))
}

/** 审核员（AUDITOR）维度动作筛选：准入审核 + 终审决策 + 绩效复核。 */
export const AUDITOR_ACTION_OPTIONS: ActionOption[] = toActionOptions([
  AUDIT_ACTION.AUDIT_APPROVE,
  AUDIT_ACTION.AUDIT_REJECT,
  AUDIT_ACTION.DECISION_APPROVE,
  AUDIT_ACTION.DECISION_REJECT,
  AUDIT_ACTION.REVIEW_APPROVE,
  AUDIT_ACTION.REVIEW_REJECT,
])

/** 后端 AuditAction 全量动作筛选（业务员日志页展示己方供应商上的审批动作时使用）。 */
export const ALL_ACTION_OPTIONS: ActionOption[] = toActionOptions(Object.values(AUDIT_ACTION))

/** 业务员（STAFF）自身可触发的动作：后端当前仅定义 SUBMIT，档案 CRUD 等动作待枚举扩展。 */
export const STAFF_ACTION_OPTIONS: ActionOption[] = toActionOptions([AUDIT_ACTION.SUBMIT])
