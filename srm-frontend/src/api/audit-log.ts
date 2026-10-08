import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { AuditLogRecord, ReviewLogQueryParams } from '@/types/audit-log'

/**
 * 审核员操作与审批日志分页查询。
 * 端点与参数严格按 docs/api-spec.md 第 5 节 + 后端 AuditLogController：
 * GET /audit-logs?entity_type=&entity_id=&page=&page_size=（仅此四个参数被后端消费）。
 *
 * 说明：
 * - entity_type 不传时返回全量（api-spec 明确"ALL 角色可查看，前端按业务范围内过滤展示"）；
 * - action / entity_name 是前端期望的扩展筛选参数，后端未支持前会被忽略，
 *   组件侧对返回结果做本地过滤兜底，保证筛选交互可用。
 */
export function getReviewLogListApi(params: ReviewLogQueryParams): Promise<ApiResponse<PageResult<AuditLogRecord>>> {
  return request.get<PageResult<AuditLogRecord>>('/audit-logs', { ...params })
}
