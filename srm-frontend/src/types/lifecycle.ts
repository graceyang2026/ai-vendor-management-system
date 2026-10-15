/**
 * 生命周期申请（停用 / 恢复 / 淘汰）模块类型（唯一权威契约：
 * docs/接口契约文档-供应商与绩效-20261013.md §2.4–§2.6 + 附录 A3（13 字段）；
 * docs/api-spec.md §3「生命周期申请」对象定义 L292-326；
 * 最终事实源为后端实存代码（实文核对 2026-10-15）：
 * srm-backend/src/main/java/com/srm/core/dto/lifecycle/
 *   LifecycleRequestResponse.java（13 字段，逐字段 @JsonProperty 物理 snake_case）、
 *   LifecycleRequestCreateRequest.java（2 字段）、LifecycleDecisionRequest.java（3 字段）。
 *
 * 铁律（acceptance §1.3 / rules.md 4.2）：停用/恢复/淘汰只能走 §2.4 提申请 → §2.6 终审两步流程，
 * 契约不提供任何直接改 supplier.status 的旁路接口；§2.4 只建 PENDING 申请单、**不动供应商状态**，
 * 前端绝不在本地"乐观改"供应商状态，状态一律以 §1.4 详情重拉为唯一事实源。
 *
 * 枚举码类型不在本文件另立字典：type 三码 / status 三码 / decision 两码的码值与中文文案
 * 唯一权威源均为 @/constants/auditDictionary
 * （后端源：LifecycleValidationPatterns.TYPE_REGEX、申请单三态见 api-spec §3
 * LifecycleRequestStatus、决策两码见 SupplierValidationPatterns.DECISION_REGEX）。
 */

import type {
  DecisionCode,
  LifecycleRequestStatusCode,
  LifecycleRequestTypeCode,
  SupplierStatusCode,
} from '@/constants/auditDictionary'

export type { DecisionCode, LifecycleRequestStatusCode, LifecycleRequestTypeCode }

/**
 * LifecycleRequestResponse（契约附录 A3，**13 字段全量**，物理 snake_case；
 * 后端源：dto/lifecycle/LifecycleRequestResponse.java）。
 * 两枚派生人名（applied_by_name / decided_by_name）本表无物理列，后端关联 sys_user 反查拼装；
 * 未决单（status=PENDING）的 decided_by / decided_by_name / decided_at / decision_comment 恒 null。
 */
export interface LifecycleRequestResponse {
  /** 1 申请单 ID（§2.6 路径参数来源） */
  id: number
  /** 2 归属供应商 */
  supplier_id: number
  /** 3 申请类型（SUSPEND/RESUME/ELIMINATE 三码，存码显中文） */
  type: LifecycleRequestTypeCode
  /** 4 申请理由（必填：停用说原因 / 恢复说整改情况 / 淘汰说依据） */
  reason: string
  /** 5 申请单三态（PENDING 待终审 / APPROVED 已批准 / REJECTED 已驳回，中文经 entity_type 分流） */
  status: LifecycleRequestStatusCode
  /** 6 申请人 ID */
  applied_by: number
  /** 7 申请人展示名（关联 sys_user 派生） */
  applied_by_name: string
  /** 8 申请时间 ISO-8601 */
  applied_at: string
  /** 9 决策人 ID（未决 null，后端 Integer 装箱，Jackson 序列化 null） */
  decided_by?: number | null
  /** 10 决策人展示名（未决 null） */
  decided_by_name?: string | null
  /** 11 决策时间 ISO-8601（未决 null） */
  decided_at?: string | null
  /** 12 决策意见（未决 null；§2.6 comment 回写 decision_comment） */
  decision_comment?: string | null
  /** 13 乐观锁版本（DDL lifecycle_request.version DEFAULT 1）——
   *  §2.6 LifecycleDecisionRequest.version 的**唯一合法取值链**（裁决 20261013 候选 A，
   *  取 §2.5 GET 申请历史列表返回值）；内部字段不外显列，缺失/不匹配后端 40901 */
  version: number
}

/**
 * LifecycleRequestCreateRequest（契约 §2.4 请求体 2 字段；
 * 后端源：dto/lifecycle/LifecycleRequestCreateRequest.java，
 * type @NotBlank + @Pattern(TYPE_REGEX)、reason @NotBlank，失败一律 40001「参数校验失败」）。
 * 本接口**不接收 version**（只建一张 PENDING 申请单，不改供应商行）。
 */
export interface LifecycleRequestCreateRequest {
  type: LifecycleRequestTypeCode
  reason: string
}

/**
 * LifecycleDecisionRequest（契约 §2.6 请求体 3 字段；
 * 后端源：dto/lifecycle/LifecycleDecisionRequest.java，
 * decision @NotBlank + @Pattern(DECISION_REGEX)、comment 选填、version Integer）。
 * decision=APPROVE 才联动供应商状态（SUSPEND→SUSPENDED / RESUME→NORMAL / ELIMINATE→ELIMINATED）；
 * REJECT 只改申请单状态，供应商状态与 version 分毫不动。
 * comment 契约列为"否"必填（与 §2.2/§2.3 的驳回必填不同），本页按契约保持可选。
 */
export interface LifecycleDecisionRequest {
  decision: DecisionCode
  comment?: string
  version: number
}

/**
 * §2.5 申请历史列表的页面装配模型（**非契约对象**，仅终审决策视图内部使用）：
 * §2.5 是按供应商逐个查询的接口（`GET /suppliers/{id}/lifecycle-requests`，无跨供应商聚合端点），
 * 故审核员「待决策区」需把供应商档案快照（名称 + 当前状态）与申请单拼成一行才能呈现
 * 「供应商名/当前状态/目标状态/申请人/原因」（任务卡 F2.1 要求列）。
 * supplier_* 两字段来自 §1.3 SupplierResponse（列表行快照），request 字段来自 §2.5 原样透传，
 * 不做任何本地状态推断。
 */
export interface LifecycleRequestRow {
  supplier_id: number
  supplier_name: string
  supplier_status: SupplierStatusCode
  request: LifecycleRequestResponse
}
