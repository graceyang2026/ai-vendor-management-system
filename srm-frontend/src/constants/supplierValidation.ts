/**
 * 供应商入参校验常量（前端唯一集中管理点）。
 *
 * 铁律（docs/api-spec.md §2 校验表 L158-172）：后端 Bean Validation 与前端表单正则必须逐字同源，
 * 两侧不得各写一套。本文件的正则与固定中文 message 逐字照抄后端唯一常量源
 * srm-backend/src/main/java/com/srm/core/dto/supplier/SupplierValidationPatterns.java
 * （NAME_REGEX / TAX_NO_REGEX / TYPE_REGEX / PHONE_REGEX / EMAIL_REGEX / DATE_REGEX 与 7 条 *_MSG，
 * 实文核对日期：2026-10-14）。后端该文件变更时，前端必须同步本文件，禁止各页面另写正则/文案。
 */

import type { SupplierFormModel } from '@/types/supplier'

export const SUPPLIER_VALIDATION = {
  /** name / contact_name：首尾非空白，含中间共 2~64 字符（后端 NAME_REGEX） */
  NAME_REGEX: /^\S.{0,62}\S$/,
  /** tax_no：GB 32100 十八位，数字+大写字母，不含 I/O/Z/S/V（后端 TAX_NO_REGEX） */
  TAX_NO_REGEX: /^[0-9A-HJ-NPQRTUWXY]{18}$/,
  /** type：只认 SupplierType 三码，中文文案不接受（后端 TYPE_REGEX，存码显中文裁决 20261012） */
  TYPE_REGEX: /^(MANUFACTURING|SERVICE_OUTSOURCING|DISTRIBUTOR)$/,
  /** contact_phone：中国大陆 11 位手机号（后端 PHONE_REGEX） */
  PHONE_REGEX: /^1[3-9]\d{9}$/,
  /** contact_email：选填，填则必须合法；空串视为未填写放行（后端 EMAIL_REGEX） */
  EMAIL_REGEX: /^$|^[^@\s]+@[^@\s]+\.[^@\s]+$/,
  /** effective_date / expiry_date：yyyy-MM-dd 语法校验（后端 DATE_REGEX） */
  DATE_REGEX: /^\d{4}-\d{2}-\d{2}$/,

  // ==== 固定中文 message（逐字照抄后端 SupplierValidationPatterns / 契约校验表，禁止改写） ====
  NAME_MSG: '供应商名称格式不合法（不得为空格且长度需为2~64字符）',
  TAX_NO_MSG: '统一社会信用代码格式不合法（必须为18位有效字符）',
  TYPE_MSG: '供应商类型不合法',
  CONTACT_NAME_MSG: '联系人姓名格式不合法（不得为空格且长度需为2~64字符）',
  CONTACT_PHONE_MSG: '联系电话格式不合法（必须为11位有效手机号）',
  CONTACT_EMAIL_MSG: '联系邮箱格式不合法',
  DATE_MSG: '日期格式不合法或失效日期早于生效日期',
} as const

/**
 * 乐观锁冲突（40901）提示文案：docs/api-spec.md / 契约文档 §2.2 错误码矩阵固定 message，
 * 用户裁决 20261014 口径 —— 前端按契约文案展示并重新拉取详情取新 version。
 * 注记：后端本批实文（SupplierServiceImpl.MSG_VERSION_CONFLICT 与 GlobalExceptionHandler）为
 * 「数据已被修改，请刷新后重试」（较契约少"他人"二字），措辞差异已在会话留痕上报 tech-lead，
 * 前端不擅自跟改，以裁决的契约文案为准。
 */
export const SUPPLIER_LOCK_CONFLICT_MSG = '数据已被他人修改，请刷新后重试'

/**
 * 40001 逐字段定位表：后端 GlobalExceptionHandler 取"首个字段错误"的固定 message 返回，
 * 前端据 message 反查表单字段、把错误挂到对应 el-form-item 上展示。
 * DATE_MSG 为跨字段文案（effective_date/expiry_date 共用），后端 @AssertTrue 不区分具体列，
 * 定位到 expiry_date（"失效日期早于生效日期"语义高发端）；前端已先行本地校验，该兜底仅防漂移。
 */
export const SUPPLIER_FIELD_BY_MESSAGE: Record<string, keyof SupplierFormModel> = {
  [SUPPLIER_VALIDATION.NAME_MSG]: 'name',
  [SUPPLIER_VALIDATION.TAX_NO_MSG]: 'tax_no',
  [SUPPLIER_VALIDATION.TYPE_MSG]: 'type',
  [SUPPLIER_VALIDATION.CONTACT_NAME_MSG]: 'contact_name',
  [SUPPLIER_VALIDATION.CONTACT_PHONE_MSG]: 'contact_phone',
  [SUPPLIER_VALIDATION.CONTACT_EMAIL_MSG]: 'contact_email',
  [SUPPLIER_VALIDATION.DATE_MSG]: 'expiry_date',
}

/**
 * 模块二（状态机流转 §2.1~§2.6）40001 固定文案表。
 * 权威源（实文核对 2026-10-15）：后端 dto/supplier/SupplierValidationPatterns.java 是模块二校验
 * 常量的唯一来源（类注释明示"模块二沿用本类"），§2.3 QualificationValidationPatterns 与
 * §2.4/§2.6 LifecycleValidationPatterns 均以常量引用方式复用 PARAM_INVALID_MSG / REJECT_COMMENT_MSG /
 * DECISION_REGEX，前端同构复用本文件，禁止每个弹窗各写一套串值。
 * - PARAM_INVALID_MSG：枚举码值非法 / 必填缺失这类**格式**失败的泛义文案（§2.4 reason、§2.6 decision）；
 * - REJECT_COMMENT_MSG：§2.2 准入审核与 §2.3 资质审批"驳回意见条件必填"的服务端文案（Service 层判定）；
 * - VERSION_INVALID_MSG：version 为 0/负数时的入参层文案（三枚 DecisionRequest/ReviewRequest 的 @Min(1)）；
 * - LICENSE_REQUIRED_MSG：§2.1 送审硬门槛（后端 SupplierServiceImpl.MSG_LICENSE_REQUIRED），
 *   送审确认框前置提示用，真判定仍在后端（无有效执照回 40001）。
 */
export const MODULE2_VALIDATION = {
  PARAM_INVALID_MSG: '参数校验失败',
  REJECT_COMMENT_MSG: '驳回时必须填写意见',
  VERSION_INVALID_MSG: '版本号不合法（必须为正整数）',
  LICENSE_REQUIRED_MSG: '请先上传有效期内的营业执照',
} as const
