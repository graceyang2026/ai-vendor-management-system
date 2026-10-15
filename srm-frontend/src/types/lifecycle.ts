/**
 * 生命周期申请（停用 / 恢复 / 淘汰）模块类型（唯一权威契约：
 * docs/接口契约文档-供应商与绩效-20261013.md §2.4–§2.7 + 附录 A3（13 字段）；
 * docs/api-spec.md §3「生命周期申请」对象定义 L292-329（含裁决 20261015 增项 LifecycleRequestTodoItem）；
 * 最终事实源为后端实存代码（实文核对 2026-10-15）：
 * srm-backend/src/main/java/com/srm/core/dto/lifecycle/
 *   LifecycleRequestResponse.java（13 字段，逐字段 @JsonProperty 物理 snake_case）、
 *   LifecycleRequestCreateRequest.java（2 字段）、LifecycleDecisionRequest.java（3 字段）、
 *   LifecycleRequestTodoItem.java（15 字段 = 附录 A3 全 13 字段 + supplier_name + supplier_status）。
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
  /** 7 申请人展示名（关联 sys_user 派生；后端 §2.5/§2.7 口径：人名反查 miss 回**空串**） */
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
   *  §2.6 LifecycleDecisionRequest.version 的合法取值链（裁决 20261013 候选 A）：
   *  取 §2.5 单供应商申请历史 **或** §2.7 跨供应商待办队列返回项的 version，两处同口径；
   *  内部字段不外显列，缺失/不匹配后端 40901 */
  version: number
}

/**
 * LifecycleRequestTodoItem（契约 §2.7 + api-spec §3 L325-328，裁决 20261015 增项）：
 * 跨供应商聚合查询（终审待办队列）**专用响应视图**
 * = LifecycleRequestResponse 全 13 字段（含 version，供 §2.6 决策取值）+ 供应商侧冗余两列。
 * 目的是终审待办列表一次拿到「哪个供应商、当前什么状态」，前端不再逐供应商循环回查 §1.3/§1.4/§2.5
 * （此前的过渡拼装模型 LifecycleRequestRow 已随本端点落地删除）。
 * supplier_status 是**当前合作状态**，与申请目标 type（"申请改成什么"）是两个概念，列表分列展示。
 * 后端源：dto/lifecycle/LifecycleRequestTodoItem.java（15 字段逐字段 @JsonProperty 物理 snake_case）。
 *
 * 可空性口径（实文核对 2026-10-15，LifecycleRequestServiceImpl.toTodoItem()）：
 * 供应商被逻辑删除（@TableLogic 隐身）或存在历史脏 FK 时，批量反查 map miss，后端把这两枚冗余列
 * 折成 **null**（不是空串、不 500、也不裁剪申请单本体）。与"派生人名 miss 回空串"刻意不同——
 * 供应商行缺失是实体消亡，空串会伪装成"供应商存在"，故类型必须是 `| null`，展示层据 null 显式标注缺失。
 * ⚠ 契约缺口（已留痕上报 tech-lead）：docs/api-spec.md §3 L326-327 把这两列写作非空的
 * `supplier_name: string` / `supplier_status: SupplierStatus`，未标可空；本类型按后端实存语义标注，
 * 契约文档由 tech-lead 决定是否补注（前端不擅改契约）。
 */
export interface LifecycleRequestTodoItem extends LifecycleRequestResponse {
  /** 14 关联 supplier.name；供应商已逻辑删除/脏 FK 时为 null（契约 §2.7 + 后端 DTO 字段注释） */
  supplier_name: string | null
  /** 15 关联 supplier.status（当前合作状态，复用供应商域 SupplierStatusCode，不另立码表）；同 null 口径 */
  supplier_status: SupplierStatusCode | null
}

/**
 * §2.7 GET /lifecycle-requests 查询参数（契约 §2.7 请求参数表，**均选填、snake_case query**）：
 * status 缺省不按状态过滤、type 缺省不按类型过滤、supplier_id 可收敛到单供应商；
 * page 默认 1、page_size 默认 20 **上限 100**。status/type 非枚举码值 → 40001（后端 Service 判定：
 * LifecycleRequestServiceImpl.requireValidRequestStatus / requireValidType，未知值绝不静默当成不过滤）；
 * 分页参数非法沿用 §1.3 统一收敛口径（page<1 回落 1、page_size 超上限收敛 100，**不报错**）。
 * 响应 data = PageResult&lt;LifecycleRequestTodoItem&gt;，按 applied_at 倒序（同刻以 id 倒序保证分页稳定，
 * 排序与分页均由后端负责，前端不再本地重排、不再拼装）。
 * 契约无 keyword/供应商名模糊检索参数（已留痕上报，工作台不自行做"假装全量"的本地过滤）。
 */
export interface LifecycleRequestTodoQuery {
  status?: LifecycleRequestStatusCode
  type?: LifecycleRequestTypeCode
  supplier_id?: number
  page?: number
  page_size?: number
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
