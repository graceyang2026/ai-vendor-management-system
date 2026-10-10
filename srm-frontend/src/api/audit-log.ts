import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type { AuditLogRecord, ReviewLogQueryParams } from '@/types/audit-log'

/**
 * 审核员（AUDITOR）域审计日志分页查询，本模块两个消费方：
 * - ReviewLogTable（操作与审批日志列表页）：传 entity_type=AUDITOR_ENTITY_TYPES 逗号多值。
 * - ReviewHistoryLogTable（审核详情追溯面板）：只传 entity_id，不传 entity_type。
 * 端点与参数严格按 docs/api-spec.md 第 5 节 + 后端 AuditLogController：
 * GET /audit-logs?entity_type=&entity_id=&page=&page_size=（仅此四个参数被后端消费）。
 *
 * 说明：
 * - entity_type 支持逗号分隔多值（单个值后端按等值、多个值按 IN，docs/api-spec.md §5 裁决 20261011）；
 *   审核员列表页传 SUPPLIER,PERFORMANCE_EVALUATION,LIFECYCLE_REQUEST 三实体，用于把 USER 域
 *   （系统管理员操作日志）排除出审核员视角；缺省不传时后端返回全量。
 * - 管理员日志页与业务员日志页不走本函数：各自见 @/api/admin-log（固定 entity_type=USER）
 *   与 @/api/supplier-log（固定 entity_type=SUPPLIER），互不影响。
 * - action / entity_name 是前端期望的扩展筛选参数，后端未支持前会被忽略，
 *   组件侧对返回结果做本地过滤兜底，保证筛选交互可用。
 */
export function getReviewLogListApi(params: ReviewLogQueryParams): Promise<ApiResponse<PageResult<AuditLogRecord>>> {
  return request.get<PageResult<AuditLogRecord>>('/audit-logs', { ...params })
}
