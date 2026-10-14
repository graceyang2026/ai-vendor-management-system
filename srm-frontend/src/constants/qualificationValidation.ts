/**
 * 资质上传 / 文件上传入参校验常量（前端唯一集中管理点）。
 *
 * 铁律与 supplierValidation.ts 相同：后端 Bean Validation / Service 校验与前端表单校验必须逐字同源，
 * 两侧不得各写一套。正则与固定中文文案逐字照抄后端唯一常量源（实文核对 2026-10-14）：
 * - srm-backend/src/main/java/com/srm/core/dto/qualification/QualificationValidationPatterns.java
 *   （DOC_TYPE_REGEX / DOC_TYPE_MSG / FILE_URL_MSG / DATE_REGEX；其 DATE_MSG 本体即引用
 *   SupplierValidationPatterns.DATE_MSG，故本文件复用 SUPPLIER_VALIDATION 同名常量，不三处抄写）；
 * - srm-backend/src/main/java/com/srm/core/dto/file/FileUploadConstraints.java（10MB 上限、
 *   multipart part 名 file、三条固定文案）+ FileStorageServiceImpl.ALLOWED_EXTENSIONS
 *   （运行期白名单 Set.of("pdf","jpg","jpeg","png")，与契约 §1.6 请求参数表一致）。
 *
 * 文案缺口注记（后端常量类注释已登记、留痕待 tech-lead 文档治理）：DOC_TYPE_MSG / FILE_URL_MSG
 * 与三条文件上传文案契约 §1.6/§1.7 未收录正式原文，后端按同族风格自拟，前端跟随后端实文保持
 * 单一来源；契约正式文案下发后两侧同批替换。前端先验不替代后端为准，最终否决权在服务端。
 */

import { SUPPLIER_VALIDATION } from '@/constants/supplierValidation'

export const QUALIFICATION_VALIDATION = {
  /** doc_type：只认 BUSINESS_LICENSE / OTHER 两码（后端 DOC_TYPE_REGEX，中文/小写不接受；中文经 auditDictionary 映射） */
  DOC_TYPE_MSG: '资质类型不合法（仅支持 BUSINESS_LICENSE/OTHER）',
  /** file_url：必填，取值必须是 §1.6 文件上传返回的 URL（后端 FILE_URL_MSG） */
  FILE_URL_MSG: '资质文件地址不能为空',
  /** effective_date / expiry_date：yyyy-MM-dd；DATE_MSG 与供应商域逐字同源（后端两域共用一个常量） */
  DATE_REGEX: SUPPLIER_VALIDATION.DATE_REGEX,
  DATE_MSG: SUPPLIER_VALIDATION.DATE_MSG,
} as const

/* ---------------------------------------------------------------- §1.6 文件上传约束 */

/** 单文件上限 10MB = 10*1024*1024 字节；恰好 10MB 合法（后端判据 size > MAX 才拒，契约措辞"不超过"） */
export const FILE_UPLOAD_MAX_SIZE_BYTES = 10 * 1024 * 1024

/** 扩展名白名单（小写比较；后端 FileStorageServiceImpl.ALLOWED_EXTENSIONS 同集合） */
export const FILE_UPLOAD_ALLOWED_EXTENSIONS: string[] = ['pdf', 'jpg', 'jpeg', 'png']

/** el-upload accept 属性值（体验层过滤，选择框默认只显示允许类型；最终校验仍以扩展名+后端为准） */
export const FILE_UPLOAD_ACCEPT = '.pdf,.jpg,.jpeg,.png'

/** 三条固定文案（逐字照抄后端 FileUploadConstraints.MESSAGE_*，前端先验与后端兜底同文案） */
export const FILE_UPLOAD_MESSAGE = {
  MISSING: '请选择要上传的文件',
  TYPE_NOT_ALLOWED: '文件类型不支持，仅支持 pdf/jpg/jpeg/png 格式',
  SIZE_EXCEEDED: '文件大小不能超过10MB',
} as const

/* ---------------------------------------------------------------- 40001 文案→字段反查 */

export type QualificationErrorField = 'doc_type' | 'file_url' | 'effective_date' | 'expiry_date'

/**
 * 40001 逐字段定位表（与 SUPPLIER_FIELD_BY_MESSAGE 同构）：后端 GlobalExceptionHandler
 * 取首个字段错误的固定 message 返回，前端据此把错误挂到对应表单项。
 * DATE_MSG 为跨字段文案（effective/expiry 共用），定位到 expiry_date（"晚于"语义高发端）。
 */
export const QUALIFICATION_FIELD_BY_MESSAGE: Record<string, QualificationErrorField> = {
  [QUALIFICATION_VALIDATION.DOC_TYPE_MSG]: 'doc_type',
  [QUALIFICATION_VALIDATION.FILE_URL_MSG]: 'file_url',
  [QUALIFICATION_VALIDATION.DATE_MSG]: 'expiry_date',
}
