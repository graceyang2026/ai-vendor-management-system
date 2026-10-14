/**
 * 供应商档案类型（唯一权威契约：docs/接口契约文档-供应商与绩效-20261013.md §1.1–§1.5 + 附录 A1；
 * docs/api-spec.md §2 对象定义；最终以 srm-backend 实存代码为事实源：
 * srm-backend/src/main/java/com/srm/core/dto/supplier/{SupplierResponse,SupplierCreateRequest,
 * SupplierUpdateRequest}.java —— 三文件逐字段 @JsonProperty 物理 snake_case，与本类型一一对应）。
 *
 * 派生字段归二期（用户裁决 20261014）：business_license_url / latest_performance_grade / risk_warning
 * 后端本批恒回 null（SupplierResponse.java 类注释明示），前端展示层必须 null 容错，类型标 nullable 并注明回填期。
 */

import type { SupplierStatusCode, SupplierTypeCode } from '@/constants/auditDictionary'

export type { SupplierStatusCode, SupplierTypeCode }

/** 归档绩效评级码（docs/api-spec.md §2 SupplierResponse.latest_performance_grade：'A'|'B'|'C'|'D'）。 */
export type PerformanceGradeCode = 'A' | 'B' | 'C' | 'D'

/**
 * SupplierResponse（附录 A1，20 字段全量，物理 snake_case；
 * 后端源：dto/supplier/SupplierResponse.java，@JsonProperty 逐字段实文核对 2026-10-14）。
 */
export interface SupplierItem {
  /** 1 主键 */
  id: number
  /** 2 供应商名称（2~64 字符） */
  name: string
  /** 3 统一社会信用代码（18 位，活跃行唯一） */
  tax_no: string
  /** 4 供应商类型（SupplierType 三码之一，存码显中文；后端本批以 TYPE_REGEX 三码为权威，无独立 Java 枚举类） */
  type: SupplierTypeCode
  /** 5 联系人 */
  contact_name: string
  /** 6 联系电话 */
  contact_phone: string
  /** 7 联系邮箱（选填） */
  contact_email?: string | null
  /** 8 地址（选填） */
  address?: string | null
  /** 9 备注（选填） */
  remark?: string | null
  /** 10 营业执照 URL —— 派生字段归二期（用户裁决 20261014），后端本批恒 null，二期回填 */
  business_license_url?: string | null
  /** 11 合作生效日 yyyy-MM-dd（企业合作契约周期起，与资质同名字段不同义） */
  effective_date: string
  /** 12 合作到期日 yyyy-MM-dd（MVP 内到期不触发任何自动动作） */
  expiry_date: string
  /** 13 状态（SupplierStatus 六态，中文经 auditDictionary 映射） */
  status: SupplierStatusCode
  /** 14 乐观锁版本（§1.2 PUT / §2.1 submit 必带回传） */
  version: number
  /** 15 建档人 ID */
  created_by: number
  /** 16 建档人姓名（关联 sys_user） */
  created_by_name: string
  /** 17 创建时间 ISO-8601 */
  created_at: string
  /** 18 更新时间 ISO-8601 */
  updated_at: string
  /** 19 最近一次归档评价评级 —— 派生字段归二期（用户裁决 20261014），后端本批恒 null，二期回填 */
  latest_performance_grade?: PerformanceGradeCode | null
  /** 20 风险预警 —— 派生字段归二期（用户裁决 20261014），后端本批恒 null（Boolean 而非 boolean，
   *  保住"未计算"与 false 的语义区分——后端 SupplierResponse.java 实文），展示层仅 true 才渲染预警标签 */
  risk_warning?: boolean | null
}

/**
 * SupplierCreateRequest（docs/api-spec.md §2 + 契约 §1.1，11 字段；
 * 后端源：dto/supplier/SupplierCreateRequest.java）。
 */
export interface SupplierCreateRequest {
  name: string
  tax_no: string
  type: SupplierTypeCode
  contact_name: string
  contact_phone: string
  contact_email?: string
  address?: string
  remark?: string
  effective_date: string
  expiry_date: string
}

/**
 * SupplierUpdateRequest = Partial&lt;SupplierCreateRequest&gt; + version（契约 §1.2：只传要改的字段；
 * 后端源：dto/supplier/SupplierUpdateRequest.java —— version 必带且 >=1，缺失 40001、不匹配 40901）。
 */
export type SupplierUpdateRequest = Partial<SupplierCreateRequest> & { version: number }

/** 列表查询参数（契约 §1.3 query：status/keyword/page/page_size；响应 data 复用 api.ts 的 PageResult&lt;SupplierItem&gt;）。 */
export interface SupplierListQuery {
  status?: SupplierStatusCode
  keyword?: string
  page: number
  page_size: number
}

/**
 * 新增/编辑弹窗表单 UI 模型（非契约对象，仅本页表单态）：
 * 选填字段以空串占位（提交时构建请求体，空串字段不提交，见 SupplierListView.buildCreatePayload）；
 * version 不入表单——编辑提交时取自详情/列表行快照（SupplierItem.version）。
 */
export interface SupplierFormModel {
  name: string
  tax_no: string
  /** 未选择时的空态；提交前必须收敛为 SupplierTypeCode 三码之一 */
  type: SupplierTypeCode | ''
  contact_name: string
  contact_phone: string
  contact_email: string
  address: string
  remark: string
  effective_date: string
  expiry_date: string
}
