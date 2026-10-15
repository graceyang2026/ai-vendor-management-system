/**
 * 绩效评价（模块三）表单标签、校验提示与静态说明常量（前端集中管理点，组件不硬编码中文）。
 *
 * 文案权威源：
 * - FACT_INVALID_MSG：契约文档 §3.1/§3.2 错误矩阵 40001 固定 message「事实数据校验失败」
 *   （后端实回时优先透传，本常量仅作本地先验/后端缺文案时的同字兜底）；
 * - 权重与评级阈值：docs/api-spec.md §4.1（后端 PerformanceScoreCalculator 硬编码，
 *   用户 2026-10-15 裁决 B1=甲：前端**不做本地实时算分**，录入页只放静态权重说明）；
 * - C/D 联动文案：docs/api-spec.md §4.1 L361（评级 C/D 仅"风险预警打标"，需人工走停用申请；
 *   禁止出现原型旧稿"下单与结算强阻断/硬阻断"表述——用户 2026-10-15 裁决）；
 * - MOCK_FACT_NOTICE：用户 2026-10-15 裁决 B4=甲（Mock 按钮=真实提交语义，事实区置灰并提示
 *   "事实数据将由服务端演示生成"）。
 * 枚举码值/中文（status/grade/mode）不在本文件 —— 单一权威源仍是 constants/auditDictionary.ts。
 */

import type { PerformanceFactRecord } from '@/types/performance'

/** 契约 40001（§3.1/§3.2）固定文案。 */
export const PERFORMANCE_FACT_INVALID_MSG = '事实数据校验失败'

/** 录入/重提表单本地先验文案（服务端 ManualFactInputAdapter 同语义兜底 40001）。 */
export const PERFORMANCE_VALIDATION = {
  SUPPLIER_REQUIRED_MSG: '请选择考评目标供应商',
  PERIOD_REQUIRED_MSG: '请选择考评周期（起止日期）',
  PERIOD_ORDER_MSG: '考评周期止（period_end）须晚于周期起（period_start）',
  FACT_REQUIRED_MSG: '真实录入模式下 8 项客观事实数据全部必填',
  FACT_NON_NEGATIVE_MSG: '事实数据不得为负数',
  /** A4.1：qc_failed_batches <= qc_total_batches（后端 ManualFactInputAdapter 同源校验） */
  QC_SUBSET_MSG: '质检不合格批数不得大于质检总批次数',
  /** A4.1：delayed_batches <= total_batches */
  DELIVERY_SUBSET_MSG: '逾期交货批次数不得大于总交货批次数',
} as const

/* ---------------------------------------------------------------- 事实字段元数据（契约附录 A4.1，8 字段） */

export interface PerformanceFactField {
  key: keyof PerformanceFactRecord
  /** label 带英文物理字段名（原型 perf-add 口径：中文 (snake_case)，与契约字段逐字对齐） */
  label: string
  /** int=整数输入；decimal=小数输入（precision 控制位宽，对齐原型 step/precision） */
  kind: 'int' | 'decimal'
  precision?: number
  hint?: string
}

export interface PerformanceFactCard {
  /** 卡片标题（维度 + 权重；权重权威源 api-spec §4.1） */
  title: string
  fields: PerformanceFactField[]
}

/**
 * 录入页四张 fact-card 的前三张（质量/交付/价格服务）字段元数据；
 * 第四张"合规维度"卡无输入字段（契约：C 由后端自动读 APPROVED 资质计算），
 * 渲染 COMPLIANCE_READONLY_NOTICE 只读说明（用户 2026-10-15 裁决：删除原型 qualification_expired radio）。
 */
export const PERFORMANCE_FACT_CARDS: PerformanceFactCard[] = [
  {
    title: '1. 质量维度 (Quality - 权重 30%)',
    fields: [
      { key: 'qc_total_batches', label: '质检总批次数 (qc_total_batches)', kind: 'int' },
      { key: 'qc_failed_batches', label: '质检不合格批数 (qc_failed_batches)', kind: 'int' },
      { key: 'major_accidents', label: '重大质量事故次数 (major_accidents)', kind: 'int' },
    ],
  },
  {
    title: '2. 交付维度 (Delivery - 权重 25%)',
    fields: [
      { key: 'total_batches', label: '总交货批次数 (total_batches)', kind: 'int' },
      { key: 'delayed_batches', label: '逾期交货批次数 (delayed_batches)', kind: 'int' },
      { key: 'avg_delay_days', label: '平均逾期天数 (avg_delay_days)', kind: 'decimal', precision: 1 },
    ],
  },
  {
    title: '3. 价格与服务维度 (Price 20% / Service 15%)',
    fields: [
      {
        key: 'price_deviation_rate',
        label: '价格偏离度 % (price_deviation_rate)',
        kind: 'decimal',
        precision: 1,
        hint: '例如: 5.0 表示高出市场均价 5%',
      },
      { key: 'complaint_overtime_count', label: '客诉超时响应次数 (complaint_overtime_count)', kind: 'int' },
    ],
  },
]

/** 合规维度卡只读说明（契约 A4.3 score_compliance 行 + api-spec §4.1 C 公式：后端自动读 APPROVED 资质）。 */
export const COMPLIANCE_READONLY_NOTICE =
  '合规分由后端根据已批准（APPROVED）资质自动计算，过期将自动预警；前端不提交任何合规数据。'

/* ---------------------------------------------------------------- 静态说明文案（B1=甲：不实时算分，仅说明权重） */

/** 录入页静态权重说明（权威源 docs/api-spec.md §4.1 权重表 + 评级映射；文案为固定说明，不参与计算）。 */
export const PERFORMANCE_WEIGHT_NOTICE = {
  formula: '权重：质量 Q 30% | 交付 D 25% | 价格 P 20% | 服务 S 15% | 合规 C 10%',
  gradeMapping: '评级映射：A ≥ 90 | 80 ≤ B < 90 | 70 ≤ C < 80 | D < 70',
  readonlyNote: '得分与评级由后端算分引擎硬编码计算，前端不提交、不覆盖、不在本地实时计算。',
} as const

/** B4=甲：mode=mock 切换后事实输入区禁用提示（提交仍走真实 POST，携带 "mode": "mock"）。 */
export const MOCK_FACT_NOTICE = '已切换为演示模式（mode=mock）：事实输入区已禁用，事实数据将由服务端演示生成。'

/** 防篡改顶部提示（原型 perf-add L314 口径 + rules.md 4.3 铁律）。 */
export const FACT_TAMPER_ALERT =
  '防篡改铁律：业务员仅录入客观履约事实明细，得分与评级由后端算分引擎硬编码计算，前端禁止提交或覆盖分数/评级。'

/**
 * C/D 级预警提示（提交成功弹窗与复核留痕展示共用；口径 docs/api-spec.md §4.1 L361：
 * C 维持 NORMAL 仅 risk_warning=true；D 只打标并要求人工走停用申请流程，评价接口不自动变更状态。
 * 依用户 2026-10-15 裁决：不得出现"下单与结算强阻断/硬阻断"文案）。
 */
export const GRADE_CD_WARNING_NOTICE =
  '评级为 C/D：系统仅打「风险预警」标记（risk_warning），不会自动变更供应商状态；如需中止合作，请由业务员提交停用申请并经审核员终审。'

/* ---------------------------------------------------------------- 审核员复核列表列头（原型 perf-review-list L230 口径） */

/** 五维分值列表头（权重标注；与 PERFORMANCE_WEIGHT_NOTICE.formula 同源口径）。 */
export const DIMENSION_SCORES_COLUMN_LABEL =
  '5维度计算分值 (质量30% | 交付25% | 价格20% | 服务15% | 合规10%)'

/* ---------------------------------------------------------------- F3-rev 详情弹窗与复核对话框文案 */

/** 驳回意见必填（契约 §3.3 错误矩阵 40001 固定文案；后端实回时优先透传本语义同字兜底）。 */
export const REVIEW_COMMENT_REQUIRED_MSG = '驳回时必须填写意见'

/** 复核对话框说明（防篡改铁律：复核对象是后端算分结果，人工无权改分）。 */
export const REVIEW_DECISION_ALERT =
  '复核仅针对业务员提交的客观事实与后端算分结果：通过即归档（APPROVED 永久不可撤回），驳回则退回原业务员修正事实数据（RETURNED）后重提。得分与评级由后端算分引擎产出，任何角色均不可人工改动。'

/** 布尔标记位只读回显标签（result.quality_exempt / result.risk_warning）。 */
export const PERFORMANCE_BOOL_LABEL = { yes: '是', no: '否' } as const

/** 按字段名取值展示项：与录入卡 label 同源（契约附录 A4.1 八字段，顺序质量→交付→价格服务）。 */
const factFieldsFlat = PERFORMANCE_FACT_CARDS.flatMap((card) => card.fields)

export interface PerformanceDetailItem {
  key: string
  label: string
}

export const PERFORMANCE_FACT_DETAIL_ITEMS: PerformanceDetailItem[] = factFieldsFlat.map((field) => ({
  key: field.key,
  label: field.label,
}))

/** result 五维分值展示项（契约附录 A4.3，权重标注权威源 api-spec §4.1；全只读）。 */
export const PERFORMANCE_RESULT_SCORE_ITEMS: PerformanceDetailItem[] = [
  { key: 'score_quality', label: '质量得分 (score_quality, 权重 30%)' },
  { key: 'score_delivery', label: '交付得分 (score_delivery, 权重 25%)' },
  { key: 'score_price', label: '价格得分 (score_price, 权重 20%)' },
  { key: 'score_service', label: '服务得分 (score_service, 权重 15%)' },
  { key: 'score_compliance', label: '合规得分 (score_compliance, 权重 10%)' },
]

/** result 其余四字段标签（综合得分 / 评级 / 免考 / 风险预警；全只读）。 */
export const PERFORMANCE_RESULT_EXTRA_LABELS = {
  total_score: '综合得分 (total_score)',
  grade: '自动评级 (grade)',
  quality_exempt: '当期免考 (quality_exempt)',
  risk_warning: '风险预警 (risk_warning)',
} as const

/**
 * 重提一张原本 mode=mock 的评价单时的说明（后端 §3.2 不入参 mode、按落库 mode 取适配器：
 * mock 单重提时提交体里的 fact_record 会被服务端演示数据覆盖）。
 * 用户 2026-10-15 裁决 B4=甲的延伸：前端据实禁用事实区并写明事实仍由服务端生成，避免"改了却没生效"的误导。
 */
export const MOCK_RESUBMIT_FACT_NOTICE =
  '该评价单取数模式为演示生成（mode=mock）：§3.2 重提不入参 mode，事实数据仍由服务端演示数据生成，下方事实输入区只读回显服务端返回值、不可编辑。'
