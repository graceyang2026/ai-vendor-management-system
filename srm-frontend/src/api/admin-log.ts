import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { AuditLogRecord } from '@/types/audit-log'
import type { AdminLogQueryParams } from '@/types/admin-log'

/**
 * 系统操作日志分页查询（GET /audit-logs，docs/api-spec.md 第 5 节）。
 * 管理动作的业务对象是账号，固定 entity_type=USER（后端 EntityType 枚举含 USER）；
 * operator_name / action / start_date / end_date 为扩展筛选参数，
 * 后端 AuditLogController 当前只消费 entity_type / entity_id / page / page_size，
 * 其余参数被忽略，组件侧对返回结果做本地过滤兜底。
 */
export function getAdminLogListApi(params: AdminLogQueryParams): Promise<ApiResponse<PageResult<AuditLogRecord>>> {
  const { entity_type: entityType, ...rest } = params
  return request.get<PageResult<AuditLogRecord>>('/audit-logs', {
    ...rest,
    entity_type: entityType ?? 'USER',
  })
}
