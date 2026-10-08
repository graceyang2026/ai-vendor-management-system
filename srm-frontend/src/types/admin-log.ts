/**
 * 系统操作日志（管理员）类型定义。
 * 接口字段严格使用 snake_case（docs/api-spec.md 第 0 节命名约定），前端 TS 字段名与 JSON 保持一致。
 */

/** 日志列表项（管理后台"系统操作日志"模块契约）。 */
export interface AdminLogItem {
  id: string | number
  /** 操作时间 */
  created_at: string
  /** 操作人，如：超级管理员 (Admin) */
  operator_name: string
  /** 操作类型，如：新增用户 / 调整用户角色 / 停用账号 / 启用账号 */
  action_type: string
  /** 操作目标账号/对象 */
  target_object: string
  /** 详细变动记录与参数说明 */
  detail: string
}

/** 日志查询参数（query string，snake_case）。 */
export interface AdminLogQueryParams {
  operator_name?: string
  action_type?: string
  /** 起始日期 YYYY-MM-DD */
  start_date?: string
  /** 结束日期 YYYY-MM-DD */
  end_date?: string
  page: number
  page_size: number
}

/** 管理员操作动作类型选项（与原型下拉一致）。 */
export const ADMIN_ACTION_TYPES = ['新增用户', '调整用户角色', '停用账号', '启用账号'] as const
