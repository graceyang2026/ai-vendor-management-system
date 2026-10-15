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
  QualificationReviewRequest,
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

/**
 * §2.3 PUT /qualifications/{id}/review —— 存续期资质单独审批（AUDITOR 专属，其余 40301）。
 * 路径参数 id=§1.8 列表返回的资质记录 ID；请求体三字段 {action, review_comment, version}
 * —— **首字段名是 action 不是 decision**（用户裁决 20261012 定稿，口径照 §2.2 同款但换名）。
 * 前置（不符 40302）：供应商必须 NORMAL、资质必须 PENDING；action=REJECT 且 review_comment 空 → 40001。
 * APPROVE 置 APPROVED 并同类型旧件自动退位；REJECT 置 REJECTED 且旧件保持生效位，
 * 供应商 NORMAL 基础合作状态不受影响。并发置生效位竞态由 DB uk_supplier_approved_doc 兜底 → 40902。
 * 成功 data=QualificationResponse（status/reviewed_by/reviewed_at/review_comment 回写，version 自增）。
 */
export function reviewQualificationApi(
  id: number,
  data: QualificationReviewRequest,
): Promise<ApiResponse<QualificationItem>> {
  return request.put<QualificationItem>(`/qualifications/${id}/review`, data)
}
