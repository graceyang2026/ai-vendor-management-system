import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { AdminLogItem, AdminLogQueryParams } from '@/types/admin-log'

/**
 * 系统操作日志分页查询。
 * 端点对齐 docs/api-spec.md 第 5 节审计日志查询 GET /audit-logs（后端 AuditLogController）；
 * operator_name / action_type / start_date / end_date 为管理日志筛选参数，
 * 后端当前尚未支持该筛选形态时返回结果不含这些过滤，组件侧有兜底演示逻辑。
 */
export function getAdminLogListApi(params: AdminLogQueryParams): Promise<ApiResponse<PageResult<AdminLogItem>>> {
  return request.get<PageResult<AdminLogItem>>('/audit-logs', { ...params })
}
