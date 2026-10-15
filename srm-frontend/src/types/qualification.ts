/**
 * 供应商资质模块类型（唯一权威契约：docs/接口契约文档-供应商与绩效-20261013.md §1.6–§1.8 + 附录 A2；
 * docs/api-spec.md §2「文件上传 FileUploadResponse」「资质 Qualification」对象定义；
 * 最终事实源为后端实存代码（实文核对 2026-10-14）：
 * srm-backend/src/main/java/com/srm/core/dto/file/FileUploadResponse.java（3 字段），
 * srm-backend/src/main/java/com/srm/core/dto/qualification/QualificationResponse.java（13 字段），
 * srm-backend/src/main/java/com/srm/core/dto/qualification/QualificationCreateRequest.java（4 字段）
 * —— 三文件逐字段 @JsonProperty 物理 snake_case，与本类型一一对应）。
 *
 * 枚举码类型不在本文件另立字典：doc_type 两码 / 资质 status 三码的码值与中文文案唯一权威源
 * 均为 @/constants/auditDictionary（后端源：QualificationValidationPatterns.DOC_TYPE_REGEX；
 * 状态裁决 20261012，QUALIFICATION_STATUS_LABEL 经 getStatusLabel(code, SUPPLIER_QUALIFICATION) 分流）。
 */

import type {
  DecisionCode,
  QualificationDocTypeCode,
  QualificationStatusCode,
} from '@/constants/auditDictionary'

export type { DecisionCode, QualificationDocTypeCode, QualificationStatusCode }

/**
 * §1.6 FileUploadResponse（3 字段；后端 FileUploadResponse.java 类注释明示：本 DTO 刻意不含
 * 任何 id/表字段/分数评级字段——文件上传接口不写业务表）。
 */
export interface FileUploadResult {
  /** 形如 /uploads/2026/10/xxx.pdf，后端静态映射 /uploads/** 可直接访问；直接填入 QualificationCreateRequest.file_url */
  file_url: string
  /** 原始文件名，仅作上传成功回显，不参与落盘路径 */
  file_name: string
  /** 文件字节数（与后端上限 10*1024*1024 同一判据，FileUploadConstraints.MAX_FILE_SIZE_BYTES） */
  size: number
}

/**
 * QualificationResponse（契约附录 A2，13 字段全量，物理 snake_case；
 * 后端源：dto/qualification/QualificationResponse.java，@JsonProperty 逐字段实文核对 2026-10-14）。
 */
export interface QualificationItem {
  /** 1 资质记录 ID（§2.3 存续期单独审批路径参数来源） */
  id: number
  /** 2 归属供应商 */
  supplier_id: number
  /** 3 证照类型（BUSINESS_LICENSE/OTHER 两码，存码显中文） */
  doc_type: QualificationDocTypeCode
  /** 4 证照文件 URL（§1.6 上传返回值原样落库回显） */
  file_url: string
  /** 5 单项证照有效期起 yyyy-MM-dd（与供应商合作周期同名列不同义、互不联动） */
  effective_date: string
  /** 6 证照有效期止 yyyy-MM-dd（过期判定与 C 维度算分依据） */
  expiry_date: string
  /** 7 资质三态（裁决 20261012：PENDING/APPROVED/REJECTED，中文经 auditDictionary 资质域分流） */
  status: QualificationStatusCode
  /** 8 乐观锁版本（DDL④，初始 1）——内部字段：§2.3 审批入参 version 的唯一取值链，列表不外显（契约 §1.8 业务约束） */
  version: number
  /** 9 派生位：expiry_date < 今天（同表比较，后端本批已实现——用户口径 20261014：前端正常展示红标）；仅 APPROVED 行参与合规算分 */
  expired: boolean
  /** 10 创建时间 ISO-8601 */
  created_at: string
  /** 11 审核人（§2.2 连带/§2.3 单独审批回写；§1.7 新件恒 null，后端 Jackson 默认序列化 null 值，故 number|null） */
  reviewed_by?: number | null
  /** 12 审核时间 ISO-8601（未审核 null） */
  reviewed_at?: string | null
  /** 13 驳回意见（REJECTED 行展示依据；未审核 null） */
  review_comment?: string | null
}

/**
 * QualificationCreateRequest（契约 §1.7 请求体，4 字段；
 * 后端源：dto/qualification/QualificationCreateRequest.java）。
 * file_url 必须是 §1.6 上传成功返回的 URL（先传后提交，两步编排见 QualificationPanel.vue）。
 */
export interface QualificationCreateRequest {
  doc_type: QualificationDocTypeCode
  file_url: string
  /** 证照生效日 yyyy-MM-dd */
  effective_date: string
  /** 证照到期日 yyyy-MM-dd，必须晚于生效日（后端 @AssertTrue 跨字段复核，契约用词"晚于"） */
  expiry_date: string
}

/**
 * QualificationReviewRequest（契约 §2.3 请求体，字段名口径照 §2.2 同款但**首字段叫 action 不叫 decision**；
 * 后端实文 dto/qualification/QualificationReviewRequest.java：
 * @JsonProperty("action") / @JsonProperty("review_comment") / @JsonProperty("version")）。
 * - action 码值复用 constants/auditDictionary.DECISION 两码（后端 ACTION_REGEX 引用 DECISION_REGEX）；
 * - review_comment 仅 action=REJECT 时必填（40001「驳回时必须填写意见」），回写 supplier_qualification.review_comment；
 * - version 取 §1.8 资质列表每行 QualificationResponse.version（唯一取值链），不匹配 40901；
 * - 范围限定（裁决 20261012）：仅 NORMAL 供应商存续期补传件；准入期件走 §2.2 连带批复。
 *   前置不满足（供应商非 NORMAL 或资质非 PENDING）后端 40302，前端不提供独立"重试"入口。
 */
export interface QualificationReviewRequest {
  action: DecisionCode
  review_comment?: string
  version: number
}
