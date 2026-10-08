import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { SupplierLogItem, SupplierLogQueryParams } from '@/types/supplier-log'

/**
 * 供应商操作与审批日志分页查询。
 * 端点对齐 docs/api-spec.md 第 5 节审计日志查询 GET /audit-logs（后端 AuditLogController）：
 * entity_type 固定 SUPPLIER（业务员维度日志视角），supplier_id 映射既有参数 entity_id；
 * supplier_name / action_type 为扩展筛选参数，后端补齐前由组件侧兜底演示逻辑保证可交互。
 */
export function getSupplierLogListApi(params: SupplierLogQueryParams): Promise<ApiResponse<PageResult<SupplierLogItem>>> {
  const { supplier_id: supplierId, ...rest } = params
  return request.get<PageResult<SupplierLogItem>>('/audit-logs', {
    entity_type: 'SUPPLIER',
    ...(supplierId !== undefined ? { entity_id: supplierId } : {}),
    ...rest,
  })
}
