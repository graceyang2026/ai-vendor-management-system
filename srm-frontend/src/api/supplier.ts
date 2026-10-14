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
  SupplierCreateRequest,
  SupplierItem,
  SupplierListQuery,
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
