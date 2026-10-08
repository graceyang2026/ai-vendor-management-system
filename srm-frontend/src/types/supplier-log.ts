/**
 * 业务员（STAFF）视角的操作与审批日志类型定义。
 * 接口字段严格使用 snake_case（docs/api-spec.md 第 0 节命名约定 + 第 5 节审计日志），
 * 前端 TS 字段名与 JSON 保持一致，禁止混用 camelCase。
 */

/** 日志列表项（供应商业务操作与审批日志契约）。 */
export interface SupplierLogItem {
  id: string | number
  /** 操作时间 */
  created_at: string
  /** 目标供应商 ID */
  supplier_id: string | number
  /** 目标供应商名称 */
  supplier_name: string
  /** 操作人，如：Yangwei (业务员) */
  operator_name: string
  /** 操作类型，见 SUPPLIER_ACTION_TYPES */
  action_type: string
  /** 原状态，如：草稿/待提交、待审核、正常/合作中 */
  old_status: string
  /** 新状态 */
  new_status: string
  /** 意见/结果/详细说明 */
  comment: string
}

/** 日志查询参数（query string，snake_case；supplier_id 映射后端 entity_id）。 */
export interface SupplierLogQueryParams {
  supplier_name?: string
  action_type?: string
  supplier_id?: string | number
  page: number
  page_size: number
}

/** 供应商业务操作动作类型选项（与原型筛选下拉一致）。 */
export const SUPPLIER_ACTION_TYPES = [
  '新增档案',
  '修改档案',
  '提交准入',
  '逻辑删除草稿',
  '申请变更状态',
  '申请恢复合作',
  '绩效客观事实录入',
] as const

export type SupplierTagType = 'success' | 'warning' | 'danger' | 'info'

/**
 * 供应商生命周期状态的 el-tag 颜色映射（与原型 getStatusTagType 一致，
 * 状态展示词对齐 api-spec 第 2 节状态机的中文语义）。
 */
export const SUPPLIER_STATUS_TAG_TYPE: Record<string, SupplierTagType> = {
  无: 'info',
  '草稿/待提交': 'info',
  待审核: 'warning',
  '正常/合作中': 'success',
  待修改: 'danger',
  停用: 'info',
  淘汰: 'danger',
}

export function getSupplierStatusTagType(status: string): SupplierTagType {
  return SUPPLIER_STATUS_TAG_TYPE[status] ?? 'danger'
}
