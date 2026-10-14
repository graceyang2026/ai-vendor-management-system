/**
 * 供应商资质区接口封装（契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6–§1.8；
 * 后端实文：srm-backend/src/main/java/com/srm/core/controller/
 * - FileController.java（@RequestMapping("/api/v1/files")，POST /upload，multipart，STAFF）
 * - QualificationController.java（@RequestMapping("/api/v1")，POST/GET /suppliers/{id}/qualifications，
 *   §1.7 STAFF 本人；§1.8 ADMIN/STAFF/AUDITOR 三角色可读，行级可见性由 Service 裁定）。
 * request 实例 baseURL 已含 /api/v1 前缀（utils/request.ts），故此处一律使用相对路径，
 * 与 api/supplier.ts 同风格。
 * 错误口径（用户拍板 20261014）：HTTP 200 + 响应体业务 code（40001/40302/40401…），
 * request 拦截器抛 ApiError，调用方按 code 分流提示，message 直显后端契约文案。
 */

import request from '@/utils/request'
import type { ApiResponse } from '@/types/api'
import type {
  FileUploadResult,
  QualificationCreateRequest,
  QualificationItem,
} from '@/types/qualification'

/**
 * §1.6 POST /files/upload —— multipart/form-data，part 名固定为 file
 * （后端 FileUploadConstraints.PART_NAME；类型不符/超限 40001）。
 * 成功 data={file_url,file_name,size}；file_url 供 §1.7 请求体随带，本接口不写业务表。
 */
export function uploadFileApi(file: File): Promise<ApiResponse<FileUploadResult>> {
  const formData = new FormData()
  formData.append('file', file)
  return request.postForm<FileUploadResult>('/files/upload', formData)
}

/**
 * §1.7 POST /suppliers/{id}/qualifications —— 上传资质（STAFF 本人；供应商状态允许集
 * DRAFT/RETURNED/NORMAL，越权 40302、不存在 40401）。新记录一律初始 status=PENDING、version=1
 * （裁决 20261012：上传不等于生效），返回 QualificationResponse（附录 A2 13 字段）。
 */
export function createQualificationApi(
  supplierId: number,
  data: QualificationCreateRequest,
): Promise<ApiResponse<QualificationItem>> {
  return request.post<QualificationItem>(`/suppliers/${supplierId}/qualifications`, data)
}

/**
 * §1.8 GET /suppliers/{id}/qualifications —— 资质列表（三角色可读；返回裸数组 QualificationResponse[]，
 * 非 PageResult）。可见性组合口径（裁决 20261014，后端 Service 已实现）：本人 STAFF 见
 * APPROVED+PENDING+REJECTED；非本人 STAFF 仅 APPROVED+PENDING；AUDITOR/ADMIN 全量。
 * 前端不做二次过滤，后端返回什么展示什么；每行携带的 version 是 §2.3 审批入参的唯一取值链。
 */
export function getQualificationListApi(supplierId: number): Promise<ApiResponse<QualificationItem[]>> {
  return request.get<QualificationItem[]>(`/suppliers/${supplierId}/qualifications`)
}
