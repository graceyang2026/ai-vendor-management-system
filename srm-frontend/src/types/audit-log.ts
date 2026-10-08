/**
 * 审核员（AUDITOR）视角的操作与审批日志类型定义。
 * 接口字段严格使用 snake_case（docs/api-spec.md 第 0 节命名约定 + 第 5 节审计日志），
 * 前端 TS 字段名与 JSON 保持一致，禁止混用 camelCase。
 */

/** 日志列表项（审核员合规审计与过程日志契约）。 */
export interface ReviewLogItem {
  id: string | number
  /** 操作时间 */
  created_at: string
  /** 目标供应商 ID */
  supplier_id: string | number
  /** 目标供应商名称 */
  supplier_name: string
  /** 操作人/审核人，如：LiSi (审核员) */
  operator_name: string
  /** 动作/类型（准入审核通过、准入审核驳回、状态变更审核、恢复合作审核、等级调整审核等），见 REVIEW_ACTION_TYPES */
  action_type: string
  /** 原状态，如：待审核、草稿 */
  old_status: string
  /** 新状态，如：正常/合作中、已驳回 */
  new_status: string
  /** 审核意见/驳回原因/审批说明 */
  comment: string
}

/** 日志查询参数（query string，snake_case；supplier_id 映射后端 entity_id）。 */
export interface ReviewLogQueryParams {
  supplier_name?: string
  action_type?: string
  supplier_id?: string | number
  page: number
  page_size: number
}

/**
 * 审核动作类型筛选选项（与原型"操作与审批日志"筛选下拉一致：
 * 资质审核通过/资质审核驳回/批准停用/批准淘汰/恢复正常合作/绩效复核通过，
 * 语义对应任务契约的准入审核通过、状态变更审核、恢复合作审核、等级调整审核等）。
 */
export const REVIEW_ACTION_TYPES = [
  '资质审核通过',
  '资质审核驳回',
  '批准停用',
  '批准淘汰',
  '恢复正常合作',
  '绩效复核通过',
] as const

export type ReviewTagType = 'success' | 'warning' | 'danger' | 'info'

/**
 * 审核流转状态的 el-tag 颜色映射（与原型 getStatusTagType 一致：
 * 待审核→warning、正常/合作中→success、待修改→danger、停用→info、其余→danger，
 * 并按任务契约补充"已驳回"→danger）。
 */
export const REVIEW_STATUS_TAG_TYPE: Record<string, ReviewTagType> = {
  无: 'info',
  '草稿/待提交': 'info',
  待审核: 'warning',
  '正常/合作中': 'success',
  待修改: 'danger',
  已驳回: 'danger',
  停用: 'info',
  淘汰: 'danger',
  待审计员复核: 'warning',
  已复核通过: 'success',
  已驳回重打分: 'danger',
}

export function getReviewStatusTagType(status: string): ReviewTagType {
  return REVIEW_STATUS_TAG_TYPE[status] ?? 'danger'
}
