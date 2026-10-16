/**
 * 供应商档案接口封装（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.1–§1.5；
 * 后端实文：srm-core/controller/SupplierController.java，@RequestMapping("/api/v1")）。
 * request 实例 baseURL 已含 /api/v1 前缀（utils/request.ts），故此处一律使用相对路径 /suppliers…。
 * 错误口径：业务 code（40001/40302/40901/40902/40401）随 HTTP 200 返回，由 request 拦截器
 * 抛 ApiError，调用方按 code 分流提示；角色越权 40301 由后端 Security 层拦截（前端隐藏按钮只是体验层）。
 * 例外：GET /suppliers/export 是系统内唯一绕过「HTTP 200 + JSON ApiResponse」约定的端点，
 * 成功返回二进制 xlsx 流、失败仍回 JSON ApiResponse，blob 分流见 utils/request.ts。
 */

import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type {
  SupplierAuditDecisionRequest,
  SupplierCreateRequest,
  SupplierExportQuery,
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

/** 后端 Content-Disposition 缺省时的回退文件名（与 SupplierController 硬编码值逐字一致）。 */
const EXPORT_FALLBACK_FILENAME = 'suppliers_export.xlsx'

/**
 * 浏览器落盘：URL.createObjectURL + <a download> 触发下载，随后 revokeObjectURL 释放内存。
 * 仅供本模块导出使用（DOM 操作集中于此，view 层不直接碰 blob）。
 */
function saveBlobToDisk(blob: Blob, fileName: string): void {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // click() 已同步触发下载任务，此处释放 objectURL 安全（不等待下载完成，避免句柄泄漏）
  window.URL.revokeObjectURL(url)
}

/**
 * GET /suppliers/export —— 按当前列表条件导出供应商档案 xlsx（需求定稿 2026-10-16，只读）。
 * 角色：ADMIN/STAFF/AUDITOR 均可用（与 §1.3 列表 @PreAuthorize 逐字一致），不涉及任何状态变更。
 * 入参：全量口径只传 status/keyword（空值由调用方裁剪、不传），**不传 page/page_size**。
 * 返回：以 blob 通道取回后端 xlsx 流，用 Content-Disposition 文件名（无则回退 suppliers_export.xlsx）
 * 触发浏览器落盘，resolve 最终落盘文件名（供成功提示展示）。
 * 错误：后端把失败/越权仍回成 JSON ApiResponse（40001 随 HTTP 200；40301→HTTP 403、40101→HTTP 401），
 * 由 request 的 blob 分流读回并按既有 ApiError 口径抛出，调用方 catch 即可，无需再判 code。
 * 契约缺口：docs/api-spec.md 暂未收录本端点，基准取后端实文 SupplierController.export（已在会话留痕上报）。
 */
export async function exportSuppliersApi(params: SupplierExportQuery): Promise<string> {
  const { blob, fileName } = await request.downloadBlob('/suppliers/export', params)
  const resolvedName = fileName || EXPORT_FALLBACK_FILENAME
  saveBlobToDisk(blob, resolvedName)
  return resolvedName
}
