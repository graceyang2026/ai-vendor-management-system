/**
 * 生命周期申请接口封装（契约：docs/接口契约文档-供应商与绩效-20261013.md §2.4–§2.6；
 * 后端实文：srm-backend/src/main/java/com/srm/core/controller/LifecycleRequestController.java，
 * @RequestMapping("/api/v1")）：
 * - §2.4 POST   /suppliers/{id}/lifecycle-requests   （STAFF，建 PENDING 申请单，绝不动供应商状态）
 * - §2.5 GET    /suppliers/{id}/lifecycle-requests   （ALL 只读，返回裸数组，按申请时间倒序，不写审计）
 * - §2.6 POST   /lifecycle-requests/{id}/decision     （AUDITOR 终审，APPROVE 联动供应商状态）
 *
 * request 实例 baseURL 已含 /api/v1 前缀（utils/request.ts），故此处一律使用相对路径，
 * 与 api/supplier.ts / api/qualification.ts 同风格。
 * 错误口径（用户拍板 20261014）：业务 code 由 request 拦截器抛 ApiError，调用方按 code 分流：
 * 40001 参数校验失败 / 40301 角色不符 / 40302 状态-类型门槛不符 / 40401 资源不存在 /
 * 40901 申请单 version 缺失或不匹配（须重拉 §2.5 取新 version）/ 40902 同类型已有在途申请。
 */

import request from '@/utils/request'
import type { ApiResponse } from '@/types/api'
import type {
  LifecycleDecisionRequest,
  LifecycleRequestCreateRequest,
  LifecycleRequestResponse,
} from '@/types/lifecycle'

/**
 * §2.4 POST /suppliers/{id}/lifecycle-requests —— 业务员提停用/恢复/淘汰申请（仅 STAFF）。
 * body {type, reason}（reason 必填非空）；本接口**只新建一张 PENDING 申请单**，
 * 供应商状态与 version 保持不变（两步流程第一步，acceptance §1.3），前端成功后不得改本地状态。
 * 类型-状态门槛与终态禁建由后端判定（不符 40302）；同类型已有 PENDING 在途 → 40902。
 * 成功 data=LifecycleRequestResponse（status=PENDING、version=1）。
 */
export function createLifecycleRequestApi(
  supplierId: number,
  data: LifecycleRequestCreateRequest,
): Promise<ApiResponse<LifecycleRequestResponse>> {
  return request.post<LifecycleRequestResponse>(`/suppliers/${supplierId}/lifecycle-requests`, data)
}

/**
 * §2.5 GET /suppliers/{id}/lifecycle-requests —— 该供应商全部申请历史（含 PENDING/APPROVED/REJECTED）。
 * 返回裸数组 LifecycleRequestResponse[]（非 PageResult，契约 §2.5 无分页参数），按申请时间倒序；
 * **本接口是 §2.6 入参 version 的唯一取值来源**，前端不得自造/复用供应商 version 替代。
 */
export function getLifecycleRequestListApi(
  supplierId: number,
): Promise<ApiResponse<LifecycleRequestResponse[]>> {
  return request.get<LifecycleRequestResponse[]>(`/suppliers/${supplierId}/lifecycle-requests`)
}

/**
 * §2.6 POST /lifecycle-requests/{id}/decision —— 审核员终审决策（仅 AUDITOR）。
 * body {decision, comment, version}：decision=APPROVE/REJECT 两码（后端 DECISION_REGEX），
 * comment 契约列为选填；version **取 §2.5 返回的申请单 version**（非供应商 version），
 * 缺失/不匹配 40901、申请单已决或供应商被并发淘汰 40302、申请单不存在 40401。
 * APPROVE 联动供应商状态（SUSPEND→SUSPENDED / RESUME→NORMAL / ELIMINATE→ELIMINATED）；
 * REJECT 只改申请单。成功 data=LifecycleRequestResponse（decided_* 回写）——
 * 供应商新 version 必须另行重拉 §1.4 详情获取，禁止前端本地乐观改状态。
 */
export function decideLifecycleRequestApi(
  requestId: number,
  data: LifecycleDecisionRequest,
): Promise<ApiResponse<LifecycleRequestResponse>> {
  return request.post<LifecycleRequestResponse>(`/lifecycle-requests/${requestId}/decision`, data)
}
