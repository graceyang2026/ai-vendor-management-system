/**
 * 绩效评价接口封装（模块三 5 接口；契约唯一权威：
 * docs/接口契约文档-供应商与绩效-20261013.md §3.1–§3.5 + docs/api-spec.md §4.3 接口表）。
 * request 实例 baseURL 已含 /api/v1 前缀（utils/request.ts），故此处一律使用相对路径。
 *
 * 错误口径（HTTP 200 + 响应体 code，拦截器抛 ApiError，调用方按 code 分流；文案优先透传后端）：
 * - 40001 事实数据校验失败 / 驳回缺意见（§3.1/§3.2/§3.3）
 * - 40301 角色不符（§3.1/§3.3 专属角色，Security 层）
 * - 40302 状态/归属不允许（§3.2 非 RETURNED 或非本人；§3.3 非法状态复核/已被并发复审）
 * - 40401 资源不存在（绩效域实回文案分两子场景：发起时被评供应商不存在=「供应商不存在」；
 *   评价单不存在=「评价记录不存在」——契约 §0 绩效域对齐注记，用户 2026-10-15 拍板 B5）
 * - 40901 乐观锁不匹配（§3.2/§3.3，全局统一文案「数据已被修改，请刷新后重试」）
 * - 40902 该供应商同期已有在途评价单（§3.1，含 DB uk_supplier_active_period 兜底）
 */

import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type {
  EvaluationListQuery,
  PerformanceEvaluationCreateRequest,
  PerformanceEvaluationResponse,
  PerformanceEvaluationUpdateRequest,
  PerformanceReviewDecisionRequest,
} from '@/types/performance'

/**
 * §3.1 POST /performance/evaluations —— 发起绩效评价（STAFF，一步算分落 PENDING_REVIEW，无草稿态）。
 * mode=manual 必带 fact_record；mode=mock 服务端生成事实（前端传入的 fact_record 被忽略而非报错）。
 */
export function createEvaluationApi(
  data: PerformanceEvaluationCreateRequest,
): Promise<ApiResponse<PerformanceEvaluationResponse>> {
  return request.post<PerformanceEvaluationResponse>('/performance/evaluations', data)
}

/**
 * §3.2 PUT /performance/evaluations/{id} —— 退回后修改事实重提（STAFF 仅创建本人，RETURNED 专用）。
 * 沿用同一条记录重新触发算分回 PENDING_REVIEW；入参不传 mode（不得借重提切换取数模式）。
 */
export function updateEvaluationApi(
  id: number,
  data: PerformanceEvaluationUpdateRequest,
): Promise<ApiResponse<PerformanceEvaluationResponse>> {
  return request.put<PerformanceEvaluationResponse>(`/performance/evaluations/${id}`, data)
}

/**
 * §3.3 POST /performance/evaluations/{id}/review —— 复核归档/退回（AUDITOR 专属，STAFF 自审 40301）。
 * 入参 {version, decision, comment}（契约字段名 decision/comment）；
 * APPROVE→APPROVED 归档永久，REJECT→RETURNED；成功回显含 reviewed_* 回写与新 version。
 */
export function reviewEvaluationApi(
  id: number,
  data: PerformanceReviewDecisionRequest,
): Promise<ApiResponse<PerformanceEvaluationResponse>> {
  return request.post<PerformanceEvaluationResponse>(`/performance/evaluations/${id}/review`, data)
}

/** §3.4 GET /performance/evaluations —— 列表分页（ALL，§0 隔离：STAFF 仅见本人创建的单，后端强制过滤）。 */
export function listEvaluationsApi(
  params: EvaluationListQuery,
): Promise<ApiResponse<PageResult<PerformanceEvaluationResponse>>> {
  return request.get<PageResult<PerformanceEvaluationResponse>>('/performance/evaluations', params)
}

/** §3.5 GET /performance/evaluations/{id} —— 详情（ALL，§0 隔离；他人私有单 40302，只读弹窗数据源）。 */
export function getEvaluationDetailApi(id: number): Promise<ApiResponse<PerformanceEvaluationResponse>> {
  return request.get<PerformanceEvaluationResponse>(`/performance/evaluations/${id}`)
}
