/**
 * 供应商档案接口封装（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.1–§1.5；
 * 后端实文：srm-core/controller/SupplierController.java，@RequestMapping("/api/v1")）。
 * request 实例 baseURL 已含 /api/v1 前缀（utils/request.ts），故此处一律使用相对路径 /suppliers…。
 * 错误口径：业务 code（40001/40302/40901/40902/40401）随 HTTP 200 返回，由 request 拦截器
 * 抛 ApiError，调用方按 code 分流提示；角色越权 40301 由后端 Security 层拦截（前端隐藏按钮只是体验层）。
 */

import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type {
  SupplierAuditDecisionRequest,
  SupplierCreateRequest,
  SupplierItem,
  SupplierListQuery,
  SupplierSubmitRequest,
  SupplierUpdateRequest,
} from '@/types/supplier'

/** §1.1 POST /suppliers —— 建档（STAFF），创建即 DRAFT 私有草稿 */
export function createSupplierApi(data: SupplierCreateRequest): Promise<ApiResponse<SupplierItem>> {
  return request.post<SupplierItem>('/suppliers', data)
}

/** §1.2 PUT /suppliers/{id} —— 编辑本人 DRAFT/RETURNED 档案（STAFF），Partial 语义 + version 必带 */
export function updateSupplierApi(id: number, data: SupplierUpdateRequest): Promise<ApiResponse<SupplierItem>> {
  return request.put<SupplierItem>(`/suppliers/${id}`, data)
}

/** §1.3 GET /suppliers —— 分页列表（ALL，后端 §0 行级强制过滤，禁止依赖前端过滤） */
export function getSupplierListApi(params: SupplierListQuery): Promise<ApiResponse<PageResult<SupplierItem>>> {
  return request.get<PageResult<SupplierItem>>('/suppliers', params)
}

/** §1.4 GET /suppliers/{id} —— 详情（ALL，§0 隔离；他人私有在途区 40302） */
export function getSupplierDetailApi(id: number): Promise<ApiResponse<SupplierItem>> {
  return request.get<SupplierItem>(`/suppliers/${id}`)
}

/** §1.5 DELETE /suppliers/{id} —— 仅本人 DRAFT 逻辑删除（STAFF），成功 data=null */
export function deleteSupplierApi(id: number): Promise<ApiResponse<null>> {
  return request.delete<null>(`/suppliers/${id}`)
}

/**
 * §2.1 POST /suppliers/{id}/submit —— 提交准入审核（STAFF 仅本人档案）。
 * 状态迁移 DRAFT/RETURNED → PENDING_REVIEW；请求体**仅 version 一字段**（裁决 20261013 候选 B）。
 * 错误口径：40001=无有效期内营业执照（message 固定「请先上传有效期内的营业执照」）、
 * 40301=非 STAFF、40302=状态非 DRAFT/RETURNED 或档案非本人、40401=不存在、40901=version 缺失/不匹配。
 * 成功 data=SupplierResponse（status=PENDING_REVIEW、version 已自增，供 §2.2 审核取值）。
 */
export function submitSupplierApi(id: number, data: SupplierSubmitRequest): Promise<ApiResponse<SupplierItem>> {
  return request.post<SupplierItem>(`/suppliers/${id}/submit`, data)
}

/**
 * §2.2 POST /suppliers/{id}/audit —— 准入审核决策（AUDITOR 专属，其余角色 40301）。
 * 只能对 status=PENDING_REVIEW 的供应商签署，其余 40302；body {version, decision, comment}
 * （decision=APPROVE/REJECT，REJECT 时 comment 必填否则 40001）。
 * APPROVE → 供应商 NORMAL；REJECT → RETURNED；同事务连带批复该供应商全部 PENDING 资质
 * （准入期资质不调 §2.3，裁决 20261012）。成功 data=SupplierResponse（version 自增）。
 */
export function auditSupplierApi(
  id: number,
  data: SupplierAuditDecisionRequest,
): Promise<ApiResponse<SupplierItem>> {
  return request.post<SupplierItem>(`/suppliers/${id}/audit`, data)
}
