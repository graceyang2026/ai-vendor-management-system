/**
 * 绩效评价（模块三）类型定义 —— 字段名逐字对齐唯一权威契约：
 * docs/接口契约文档-供应商与绩效-20261013.md §3.1–§3.5 + 附录 A4（A4.1 入站 8 事实字段 /
 * A4.2 出站 16 字段 / A4.3 算分结果 9 字段）；docs/api-spec.md §4.2 数据结构与 §4.3 五接口。
 *
 * 防篡改铁律（api-spec §4 / rules.md 4.3）：
 * - 入站只有 PerformanceFactRecord 的 8 个客观事实字段，物理上不存在任何 score/grade/exempt/risk 字段；
 * - PerformanceEvaluationResult 为后端算分引擎（PerformanceScoreCalculator 硬编码公式与权重）产物，
 *   前端一律**只读展示**，不得提交、覆盖或在本地重算（用户 2026-10-15 裁决 B1=甲：不做前端实时算分）。
 *
 * 传码显中文分层：status/mode/grade 码值类型一律取自 src/constants/auditDictionary（后端枚举同源表），
 * 本文件不另建第二套枚举字典；中文文案由字典 *_LABEL_CN 映射，请求/响应物理维持 snake_case。
 */

import type {
  DecisionCode,
  EvaluationModeCode,
  EvaluationStatusCode,
  PerformanceGradeCode,
} from '@/constants/auditDictionary'

/**
 * 评价单三态（api-spec §4.2 `type EvaluationStatus = 'PENDING_REVIEW' | 'APPROVED' | 'RETURNED'`；
 * 码值单源 auditDictionary.EVALUATION_STATUS。与 SupplierStatus/LifecycleRequestStatus/
 * QualificationStatus 存在同码值不同义（PENDING_REVIEW/APPROVED/RETURNED），展示中文必须经
 * getStatusLabel(code, ENTITY_TYPE.PERFORMANCE_EVALUATION) 按域分流，禁止当供应商状态消费。
 */
export type EvaluationStatus = EvaluationStatusCode

/** 取数模式两码（单源 auditDictionary.EVALUATION_MODE：manual 默认 / mock 服务端生成事实）。 */
export type EvaluationMode = EvaluationModeCode

/**
 * PerformanceFactRecord —— 入站 8 字段（契约附录 A4.1；打包在请求体 fact_record JSON）。
 * 校验口径（§3.1 mode=manual，后端 ManualFactInputAdapter 兜底 40001「事实数据校验失败」）：
 * 全部非负；delayed_batches <= total_batches；qc_failed_batches <= qc_total_batches；
 * total_batches=0 / qc_total_batches=0 触发零业务免考（后端 quality_exempt，前端不计算）。
 * 合规维度**不在**本对象内（C 由后端自动读 APPROVED 资质计算，前端连"资质是否过期"都不传，
 * 用户 2026-10-15 裁决：原型 qualification_expired radio 删除）。
 */
export interface PerformanceFactRecord {
  /** 交付总批次（D 维度；=0 触发交付零业务免考） */
  total_batches: number
  /** 逾期批次数（D，<= total_batches） */
  delayed_batches: number
  /** 平均逾期天数（D，BigDecimal，非负） */
  avg_delay_days: number
  /** 质检总批次（Q；=0 → Q=100 + quality_exempt=true） */
  qc_total_batches: number
  /** 质检不合格批数（Q，<= qc_total_batches） */
  qc_failed_batches: number
  /** 重大事故数（Q，非负） */
  major_accidents: number
  /** 价格偏离率 %（P，非负） */
  price_deviation_rate: number
  /** 客诉超时次数（S，非负） */
  complaint_overtime_count: number
}

/**
 * PerformanceEvaluationResult —— 后端算分产物 9 字段（契约附录 A4.3，DB 平铺 9 列、响应嵌套 result）。
 * 全只读语义：来源 docs/api-spec.md §4.1 硬编码公式（Q30/D25/P20/S15/C10；A>=90/80<=B<90/70<=C<80/D<70）。
 * 前端不得写入、不得本地重算；grade 码值单源 auditDictionary.PERFORMANCE_GRADE。
 * 评级联动（api-spec §4.1 L361）：A/B 不触发拦截；C/D 仅 risk_warning 打标，需人工走停用申请，
 * 评价接口本身不会自动变更供应商状态。
 */
export interface PerformanceEvaluationResult {
  score_quality: number
  score_delivery: number
  score_price: number
  score_service: number
  score_compliance: number
  total_score: number
  grade: PerformanceGradeCode
  /** 历史列名，语义为"当期无业务整体免考"（只读展示标签） */
  quality_exempt: boolean
  /** 资质过期或评级 C/D 时 true（只打标，不改供应商状态；只读展示） */
  risk_warning: boolean
}

/**
 * PerformanceEvaluationResponse —— 出站 16 字段（契约附录 A4.2；§3.1/§3.2/§3.3/§3.5 成功 data 同构）。
 * mode 为裁决 20261013 方案 a 的**只读回显**（区分真实录入/演示生成，写接口仅 §3.1 create 接收）；
 * reviewed_by/reviewed_at/review_comment 为 §3.3 复核回写、可空（重提 §3.2 后由后端清空上次复核痕迹，
 * 用户 2026-10-15 口径：前端展示一律以接口响应为准，不本地推断）。
 * 契约无 rating/period/code=20000 等模板样例字段（评级名是 grade，周期是 period_start+period_end）。
 */
export interface PerformanceEvaluationResponse {
  id: number
  supplier_id: number
  /** 考核周期起 yyyy-MM-dd（契约无周期编码列，季度口径= start+end 表达） */
  period_start: string
  /** 考核周期止 yyyy-MM-dd，晚于 start */
  period_end: string
  mode: EvaluationMode
  fact_record: PerformanceFactRecord
  result: PerformanceEvaluationResult
  status: EvaluationStatus
  /** 乐观锁（§3.2 重提与 §3.3 复核入参的唯一取值链） */
  version: number
  created_by: number
  created_by_name: string
  created_at: string
  updated_at: string
  reviewed_by?: number | null
  reviewed_at?: string | null
  review_comment?: string | null
}

/**
 * PerformanceEvaluationCreateRequest（契约 §3.1；本接口**不接收 version**，新建无旧版本）。
 * mode=manual：fact_record 必填；mode=mock：fact_record 传了也被服务端忽略（防借 mock 夹带分数），
 * 前端 mock 态不提交 fact_record（用户 B4=甲：演示按钮=真实提交语义，POST 携带 "mode": "mock"）。
 */
export interface PerformanceEvaluationCreateRequest {
  supplier_id: number
  period_start: string
  period_end: string
  mode: EvaluationMode
  fact_record?: PerformanceFactRecord
}

/**
 * PerformanceEvaluationUpdateRequest（契约 §3.2 退回后重提：沿用同一条记录，仅 RETURNED+本人可改）。
 * 刻意**不含 mode**——不得借重提切换取数模式；fact_record 全量覆盖；version 必带（不匹配 40901）。
 */
export interface PerformanceEvaluationUpdateRequest {
  fact_record: PerformanceFactRecord
  version: number
}

/**
 * PerformanceReviewDecisionRequest（契约 §3.3 复核入参三字段）。
 * 契约字段名是 decision/comment（**非**模板样例的 action/review_comments）；
 * decision 码值单源 auditDictionary.DECISION（APPROVE 归档 / REJECT 退回 RETURNED）；
 * comment 仅 decision=REJECT 时必填（后端 40001「驳回时必须填写意见」）。
 */
export interface PerformanceReviewDecisionRequest {
  version: number
  decision: DecisionCode
  comment?: string
}

/** §3.4 列表查询参数（query：supplier_id?/status?/page/page_size；响应 data 复用 api.ts PageResult）。 */
export interface EvaluationListQuery {
  supplier_id?: number
  status?: EvaluationStatus
  page: number
  page_size: number
}
