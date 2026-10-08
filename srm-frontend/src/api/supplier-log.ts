import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { AuditLogRecord } from '@/types/audit-log'
import type { SupplierLogQueryParams } from '@/types/supplier-log'

/**
 * 业务员操作与审批日志分页查询（GET /audit-logs，docs/api-spec.md 第 5 节）。
 * 业务员日志视角固定 entity_type=SUPPLIER（api-spec 明确的后端支持参数）；
 * 具体供应商追溯时追加 entity_id；action / entity_name 为扩展筛选参数，后端未支持前被忽略。
 */
export function getSupplierLogListApi(params: SupplierLogQueryParams): Promise<ApiResponse<PageResult<AuditLogRecord>>> {
  const { entity_type: entityType, ...rest } = params
  return request.get<PageResult<AuditLogRecord>>('/audit-logs', {
    ...rest,
    entity_type: entityType ?? 'SUPPLIER',
  })
}
