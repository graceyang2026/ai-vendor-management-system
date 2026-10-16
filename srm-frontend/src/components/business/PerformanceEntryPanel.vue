<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  createEvaluationApi,
  getEvaluationDetailApi,
  listEvaluationsApi,
  updateEvaluationApi,
} from '@/api/performance'
import { getSupplierListApi } from '@/api/supplier'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { ensureSupplierNames, primeSupplierNames, resolveSupplierName } from '@/utils/supplierNameCache'
import {
  ENTITY_TYPE,
  EVALUATION_MODE,
  EVALUATION_STATUS,
  EVALUATION_STATUS_OPTIONS,
  PERFORMANCE_GRADE,
  formatCreatedAt,
  getEvaluationModeLabel,
  getEvaluationModeTagType,
  getGradeTagType,
  getStatusLabel,
  getStatusTagType,
  NULL_FIELD_PLACEHOLDER,
  type EvaluationModeCode,
  type EvaluationStatusCode,
  type EvaluationStatusOption,
} from '@/constants/auditLogView'
import {
  COMPLIANCE_READONLY_NOTICE,
  FACT_TAMPER_ALERT,
  GRADE_CD_WARNING_NOTICE,
  MOCK_RESUBMIT_FACT_NOTICE,
  PERFORMANCE_FACT_CARDS,
  PERFORMANCE_VALIDATION,
  PERFORMANCE_WEIGHT_NOTICE,
} from '@/constants/performanceValidation'
import type {
  PerformanceEvaluationCreateRequest,
  PerformanceEvaluationResponse,
  PerformanceFactRecord,
} from '@/types/performance'
import type { SupplierItem } from '@/types/supplier'

/**
 * 业务员量化绩效评价面板（模块三 F2；UI 参照 docs/mockups/原型展示-采购员v1.1_待评审20260929.html
 * L306-454：perf-list 记录列表 + perf-add 客观事实录入，页内子视图切换，不新增路由/菜单项，
 * 由 SupplierListView 的 perf-list 菜单挂载本组件）。
 *
 * 用户 2026-10-15 已拍板口径（必须遵守）：
 * - B1=甲：不做前端本地实时算分。原型 L391-413「实时算分引擎结果」区与 L557-595 本地 computed
 *   公式一律不实现；本页只放静态权重说明，真实分数仅在提交成功弹窗展示后端响应 result（只读）。
 * - B3=甲：「考评目标供应商」下拉 = 既有 §1.3 GET /suppliers 全量（不前端过滤状态；
 *   ELIMINATED 等非法目标由后端 40401/40001 兜底，前端按 errorCode 映射提示）；显示名称、提交 supplier_id。
 * - B4 口径更新（用户 2026-10-16 指示）：「演示模拟数据 (Mock Mode)」按钮已从前端移除（隐藏入口）。
 *   后端 mock 能力（MockDataMetricAdapter、mode=mock 契约）保留不动，mode=mock 仅可由 API 直调产生。
 *   formMode 恒为 manual；重提态保留防御：原单 mode=mock 时事实区禁用+提示（§3.2 按落库 mode 取适配器）。
 * - 合规维度卡：无输入项（原型 qualification_expired radio 已删，C 由后端自动读 APPROVED 资质计算）。
 * - §3.2 重提：RETURNED 且本人创建的行「修改并重提」→ 载入 §3.5 fact_record 回填表单，
 *   PUT 带 version、不传 mode；后端重提后清空上次复核痕迹（reviewed_*），展示以接口响应为准。
 * - D 级文案按 api-spec §4.1 L361：C/D 仅风险预警打标，需人工走停用申请，不出现"硬阻断"表述。
 *
 * 防篡改铁律（rules.md 4.3）：本页提交体只含 PerformanceFactRecord 事实字段与周期/供应商/mode，
 * 不存在任何写入分数/评级字段的代码；分数与评级全部为后端响应 result 的只读展示。
 * 按钮显隐双维度：角色维度由路由守卫 meta.roles=['STAFF'] + isStaff 承担；
 * 状态/归属维度（重提仅 RETURNED+本人行）参照契约 §3.2，后端 40302 才是安全边界。
 */

type PerfSubView = 'list' | 'add'

const userStore = useUserStore()
const isStaff = computed(() => userStore.role === 'STAFF')

const subView = ref<PerfSubView>('list')

/* ---------------------------------------------------------------- 列表（§3.4，对齐原型 perf-list） */

const listLoading = ref(false)
const rows = ref<PerformanceEvaluationResponse[]>([])
const total = ref(0)
const query = reactive<{
  status: EvaluationStatusCode | ''
  page: number
  page_size: number
}>({
  status: '',
  page: 1,
  page_size: 20,
})

const statusFilterOptions: EvaluationStatusOption[] = EVALUATION_STATUS_OPTIONS

async function fetchList() {
  listLoading.value = true
  try {
    const params = { page: query.page, page_size: query.page_size } as {
      page: number
      page_size: number
      status?: EvaluationStatusCode
    }
    if (query.status) params.status = query.status
    const res = await listEvaluationsApi(params)
    rows.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
    // 契约出参只有 supplier_id（附录 A4.2）：名称经缓存解析，缺失 ID 懒查 §1.4 详情补齐（不新增后端接口）
    void ensureSupplierNames(rows.value.map((r) => r.supplier_id))
  } catch (err) {
    showRequestError(err)
  } finally {
    listLoading.value = false
  }
}

function handleStatusChange() {
  query.page = 1
  void fetchList()
}

function handlePageChange(page: number) {
  query.page = page
  void fetchList()
}

function handleSizeChange(size: number) {
  query.page_size = size
  query.page = 1
  void fetchList()
}

onMounted(() => {
  void fetchList()
})

function showRequestError(err: unknown) {
  if (err instanceof ApiError) {
    ElMessage.error(err.message)
  } else {
    ElMessage.error('网络异常，请稍后重试')
  }
}

/**
 * 重提入口门控（契约 §3.2：仅 status=RETURNED 且创建本人可改重提）。
 * 双维度：状态 × 归属（created_by === 当前登录 user_id）；角色维度由路由守卫 + isStaff 承担。
 * 后端 requireStatusAllowed + requireSelf 才是安全边界（40302），此处仅体验层收紧。
 */
function canResubmit(row: PerformanceEvaluationResponse): boolean {
  return (
    row.status === EVALUATION_STATUS.RETURNED &&
    row.created_by === userStore.userInfo?.user_id
  )
}

/* ---------------------------------------------------------------- 录入/重提表单（§3.1/§3.2，对齐原型 perf-add） */

/** 事实字段 UI 模型（非契约对象）：清空输入为 undefined，提交前收敛为 PerformanceFactRecord 的 number */
type FactFormModel = { [K in keyof PerformanceFactRecord]: number | undefined }

const EMPTY_FACTS: FactFormModel = {
  total_batches: undefined,
  delayed_batches: undefined,
  avg_delay_days: undefined,
  qc_total_batches: undefined,
  qc_failed_batches: undefined,
  major_accidents: undefined,
  price_deviation_rate: undefined,
  complaint_overtime_count: undefined,
}

const submitting = ref(false)
/** 取数模式：恒为 manual（Mock 按钮已按用户 2026-10-16 指示移除入口） */
const formMode = ref<EvaluationModeCode>(EVALUATION_MODE.MANUAL)

const isResubmit = ref(false)
/** 重提态沿用同一条记录（§3.2 不新建）：id + version 取自 §3.5 详情响应，非表单字段 */
const editingEvaluation = ref<{ id: number; version: number; mode: EvaluationModeCode } | null>(null)

const formModel = reactive<{
  supplier_id: number | undefined
  periodRange: [string, string] | null
  facts: FactFormModel
}>({
  supplier_id: undefined,
  periodRange: null,
  facts: { ...EMPTY_FACTS },
})

/** 逐字段错误展示位（el-form-item :error 绑定，同 SupplierListView 惯例） */
const fieldErrors = reactive<Record<string, string>>({})

function clearFieldErrors() {
  for (const key of Object.keys(fieldErrors)) delete fieldErrors[key]
}

function setFieldError(key: string, message: string) {
  fieldErrors[key] = message
}

/* 目标供应商下拉（B3=甲：§1.3 全量，不前端过滤状态；page_size 按 §1.3 收敛口径 100，循环翻页取全） */
const supplierOptions = ref<SupplierItem[]>([])
const supplierOptionsLoading = ref(false)
const SUPPLIER_PAGE_SIZE = 100
const SUPPLIER_PAGE_CAP = 50

async function ensureSupplierOptions() {
  if (supplierOptions.value.length > 0 || supplierOptionsLoading.value) return
  supplierOptionsLoading.value = true
  try {
    const collected: SupplierItem[] = []
    for (let page = 1; page <= SUPPLIER_PAGE_CAP; page += 1) {
      const res = await getSupplierListApi({ page, page_size: SUPPLIER_PAGE_SIZE })
      const list = res.data?.list ?? []
      collected.push(...list)
      const pageCount = res.data?.page ?? page
      const pageSize = res.data?.page_size ?? SUPPLIER_PAGE_SIZE
      const totalCount = res.data?.total ?? collected.length
      if (list.length === 0 || pageCount * pageSize >= totalCount) break
    }
    supplierOptions.value = collected
    // 顺带灌名称缓存：列表列命中免再懒查详情（同一 §1.3 数据源）
    primeSupplierNames(collected)
  } catch (err) {
    showRequestError(err)
  } finally {
    supplierOptionsLoading.value = false
  }
}

function openCreate() {
  isResubmit.value = false
  editingEvaluation.value = null
  formMode.value = EVALUATION_MODE.MANUAL
  formModel.supplier_id = undefined
  formModel.periodRange = null
  Object.assign(formModel.facts, EMPTY_FACTS)
  clearFieldErrors()
  subView.value = 'add'
  void ensureSupplierOptions()
}

/** 「修改并重提」：调 §3.5 详情载入 fact_record 回填（用户口径；不取列表行缓存以拿服务端最新 version） */
async function openResubmit(row: PerformanceEvaluationResponse) {
  try {
    const res = await getEvaluationDetailApi(row.id)
    const detail = res.data
    if (!detail) return
    isResubmit.value = true
    editingEvaluation.value = { id: detail.id, version: detail.version, mode: detail.mode }
    formMode.value = detail.mode
    formModel.supplier_id = detail.supplier_id
    formModel.periodRange = [detail.period_start, detail.period_end]
    Object.assign(formModel.facts, detail.fact_record)
    clearFieldErrors()
    subView.value = 'add'
    void ensureSupplierOptions()
  } catch (err) {
    showRequestError(err)
  }
}

function backToList() {
  subView.value = 'list'
  void fetchList()
}

/**
 * 防御逻辑（用户 2026-10-16 指示保留）：重提态且原单 mode=mock 时禁用事实区。
 * 后端 §3.2 按落库 mode 取适配器，提交体里的 fact_record 会被服务端演示数据覆盖，
 * 若不禁用将造成"改了事实却没生效"的误导 UX。
 */
const factAreaDisabled = computed(
  () => editingEvaluation.value?.mode === EVALUATION_MODE.MOCK,
)

const factAreaNotice = MOCK_RESUBMIT_FACT_NOTICE

function buildFactRecordOrNull(): PerformanceFactRecord | null {
  const record: Partial<PerformanceFactRecord> = {}
  for (const card of PERFORMANCE_FACT_CARDS) {
    for (const field of card.fields) {
      const value = formModel.facts[field.key]
      if (value === undefined || value === null) {
        setFieldError(field.key, PERFORMANCE_VALIDATION.FACT_REQUIRED_MSG)
        return null
      }
      if (value < 0) {
        setFieldError(field.key, PERFORMANCE_VALIDATION.FACT_NON_NEGATIVE_MSG)
        return null
      }
      record[field.key] = value
    }
  }
  // 契约 A4.1 子集校验（前端同源先验，后端 ManualFactInputAdapter 兜底 40001「事实数据校验失败」）
  const qc = record as Pick<PerformanceFactRecord, 'qc_total_batches' | 'qc_failed_batches'>
  if (qc.qc_failed_batches !== undefined && qc.qc_total_batches !== undefined && qc.qc_failed_batches > qc.qc_total_batches) {
    setFieldError('qc_failed_batches', PERFORMANCE_VALIDATION.QC_SUBSET_MSG)
    return null
  }
  const dl = record as Pick<PerformanceFactRecord, 'total_batches' | 'delayed_batches'>
  if (dl.delayed_batches !== undefined && dl.total_batches !== undefined && dl.delayed_batches > dl.total_batches) {
    setFieldError('delayed_batches', PERFORMANCE_VALIDATION.DELIVERY_SUBSET_MSG)
    return null
  }
  return record as PerformanceFactRecord
}

function validateCommonFields(): boolean {
  clearFieldErrors()
  let ok = true
  if (formModel.supplier_id === undefined || formModel.supplier_id === null) {
    setFieldError('supplier_id', PERFORMANCE_VALIDATION.SUPPLIER_REQUIRED_MSG)
    ok = false
  }
  const range = formModel.periodRange
  if (!range || range.length !== 2 || !range[0] || !range[1]) {
    setFieldError('period', PERFORMANCE_VALIDATION.PERIOD_REQUIRED_MSG)
    ok = false
  } else if (range[1] <= range[0]) {
    // 契约 §3.1：period_end 须晚于 period_start（后端 40001 同语义兜底）
    setFieldError('period', PERFORMANCE_VALIDATION.PERIOD_ORDER_MSG)
    ok = false
  }
  return ok
}

async function handleSubmit() {
  if (submitting.value) return
  if (!validateCommonFields()) return
  if (isResubmit.value) {
    await submitResubmit()
    return
  }
  const payload: PerformanceEvaluationCreateRequest = {
    supplier_id: formModel.supplier_id as number,
    period_start: (formModel.periodRange as [string, string])[0],
    period_end: (formModel.periodRange as [string, string])[1],
    mode: formMode.value,
  }
  // mode=manual 必带 fact_record（Mock 按钮已移除，新建态 mode 恒为 manual）
  const factRecord = buildFactRecordOrNull()
  if (!factRecord) return
  payload.fact_record = factRecord
  submitting.value = true
  try {
    const res = await createEvaluationApi(payload)
    if (res.data) {
      showResultDialog(res.data)
      backToList()
    } else {
      ElMessage.success('提交成功（响应无 data，状态以列表刷新为准）')
      backToList()
    }
  } catch (err) {
    handleSubmitError(err)
  } finally {
    submitting.value = false
  }
}

/** §3.2 重提：只传 fact_record + version，**不传 mode**（不得借重提切换取数模式） */
async function submitResubmit() {
  const target = editingEvaluation.value
  if (!target) return
  const factRecord = buildFactRecordOrNull()
  if (!factRecord) return
  submitting.value = true
  try {
    const res = await updateEvaluationApi(target.id, { fact_record: factRecord, version: target.version })
    if (res.data) {
      showResultDialog(res.data)
      backToList()
    } else {
      ElMessage.success('重提成功，状态以列表刷新为准')
      backToList()
    }
  } catch (err) {
    handleSubmitError(err, true)
  } finally {
    submitting.value = false
  }
}

/**
 * 错误码分流（errorCode 映射，文案优先透传后端）：
 * 40001 事实数据校验失败/参数校验失败 → 透传；40401 供应商不存在/评价记录不存在 → 透传；
 * 40902 该供应商同期已有在途评价单（§3.1 固定文案）→ 透传；
 * 40302/40901（重提时状态已变/非本人/version 落后）→ 透传并回列表刷新读服务端真值。
 */
function handleSubmitError(err: unknown, isResubmitCall = false) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  if (isResubmitCall && (err.code === ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT || err.code === ERROR_CODE.STATUS_NOT_ALLOWED)) {
    ElMessage.error(err.message)
    backToList()
    return
  }
  showRequestError(err)
}

/** 提交成功弹窗：只读展示后端响应 result（B1=甲：真实分数只来自后端，前端零计算） */
function showResultDialog(data: PerformanceEvaluationResponse) {
  const result = data.result
  const gradeCOrD = result.grade === PERFORMANCE_GRADE.C || result.grade === PERFORMANCE_GRADE.D
  const lines = [
    `评价单 #${data.id} 已提交，当前状态：${getStatusLabel(data.status, ENTITY_TYPE.PERFORMANCE_EVALUATION)}（version=${data.version}）`,
    `综合得分：${result.total_score} 分　自动评级：${result.grade} 级`,
    `质量 ${result.score_quality} | 交付 ${result.score_delivery} | 价格 ${result.score_price} | 服务 ${result.score_service} | 合规 ${result.score_compliance}`,
  ]
  if (result.quality_exempt) {
    lines.push('当期无业务发生，整体免考（quality_exempt 由后端标记）')
  }
  if (gradeCOrD) {
    lines.push(GRADE_CD_WARNING_NOTICE)
  }
  void ElMessageBox.alert(lines.join('\n'), '后端算分引擎结果（只读回显）', {
    type: gradeCOrD ? 'warning' : 'success',
    confirmButtonText: '知道了',
  }).catch(() => undefined)
}

function supplierDisplayName(id?: number | null): string {
  return resolveSupplierName(id)
}
</script>

<template>
  <div>
    <!-- ======================= 1. 绩效评价记录列表（契约 §3.4；原型 perf-list） ======================= -->
    <template v-if="subView === 'list'">
      <div class="page-header">
        <span class="page-title">供应商日常绩效评价记录 (历史留存可追溯)</span>
        <div class="header-actions">
          <el-button type="primary" plain :loading="listLoading" @click="fetchList">刷新列表</el-button>
          <!-- 角色维度：发起仅 STAFF（§3.1 非 STAFF 后端 40301 兜底，前端隐藏只是体验层） -->
          <el-button v-if="isStaff" type="primary" @click="openCreate">+ 发起量化绩效评价</el-button>
        </div>
      </div>

      <el-alert :title="FACT_TAMPER_ALERT" type="warning" show-icon :closable="false" class="top-alert" />

      <el-form :inline="true" class="filter-form">
        <el-form-item label="复核状态">
          <!-- 传码显中文：value=EvaluationStatus 码（§3.4 status 入参），label=绩效域字典中文；清空=不过滤 -->
          <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 160px" @change="handleStatusChange">
            <el-option v-for="opt in statusFilterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
      </el-form>

      <el-table v-loading="listLoading" :data="rows" border style="width: 100%">
        <el-table-column label="供应商名称" min-width="180" show-overflow-tooltip>
          <template #default="scope">{{ supplierDisplayName(scope.row.supplier_id) }}</template>
        </el-table-column>
        <el-table-column label="考评周期" width="200">
          <!-- 契约无周期编码列：period_start ~ period_end 表达（用户口径：daterange） -->
          <template #default="scope">{{ scope.row.period_start }} ~ {{ scope.row.period_end }}</template>
        </el-table-column>
        <el-table-column label="综合得分" width="110">
          <template #default="scope">
            <span class="score-text">{{ scope.row.result?.total_score ?? NULL_FIELD_PLACEHOLDER }}</span>
          </template>
        </el-table-column>
        <el-table-column label="自动评级" width="100">
          <template #default="scope">
            <el-tag v-if="scope.row.result" :type="getGradeTagType(scope.row.result.grade)">
              {{ scope.row.result.grade }} 级
            </el-tag>
            <span v-else>{{ NULL_FIELD_PLACEHOLDER }}</span>
          </template>
        </el-table-column>
        <el-table-column label="取数模式" width="110">
          <template #default="scope">
            <!-- mode 出参只读回显（裁决 20261013 方案 a）：真实录入/演示生成 -->
            <el-tag :type="getEvaluationModeTagType(scope.row.mode)" size="small">
              {{ getEvaluationModeLabel(scope.row.mode) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="复核状态" width="130">
          <template #default="scope">
            <!-- 同码值多域：绩效三态中文必须经 ENTITY_TYPE.PERFORMANCE_EVALUATION 分流 -->
            <el-tag size="small" :type="getStatusTagType(scope.row.status)">
              {{ getStatusLabel(scope.row.status, ENTITY_TYPE.PERFORMANCE_EVALUATION) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交人 / 提交时间" width="170">
          <template #default="scope">
            <div>{{ scope.row.created_by_name || NULL_FIELD_PLACEHOLDER }}</div>
            <div class="cell-sub">{{ formatCreatedAt(scope.row.created_at) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope">
            <!-- 状态×归属双维度门控（§3.2 仅 RETURNED+本人，后端 40302 兜底） -->
            <el-button
              v-if="isStaff && canResubmit(scope.row)"
              size="small"
              type="primary"
              link
              @click="openResubmit(scope.row)"
            >
              修改并重提
            </el-button>
            <span v-else class="muted-text">—</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        style="margin-top: 16px; justify-content: flex-end"
        :current-page="query.page"
        :page-size="query.page_size"
        :page-sizes="[10, 20, 50, 100]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
      <el-empty v-if="!listLoading && rows.length === 0" description="暂无绩效评价记录" :image-size="60" />
    </template>

    <!-- ======================= 2. 客观事实录入 / 退回重提（§3.1/§3.2；原型 perf-add） ======================= -->
    <template v-else>
      <div class="page-header">
        <span class="page-title">
          {{ isResubmit ? '修改客观事实并重提（沿用同一评价单，§3.2）' : '发起供应商绩效评价 (客观事实录入)' }}
        </span>
        <el-button @click="backToList">返回绩效列表</el-button>
      </div>

      <div class="form-container">
        <el-alert :title="FACT_TAMPER_ALERT" type="warning" show-icon :closable="false" class="top-alert" />
        <el-alert
          v-if="isResubmit"
          type="info"
          show-icon
          :closable="false"
          class="top-alert"
          title="重提沿用同一考核周期一张单，重新触发后端算分并回到待复核；取数模式不可切换（不传 mode），上次复核痕迹由后端清理，展示以接口响应为准。"
        />
        <el-alert
          v-if="factAreaDisabled"
          :title="factAreaNotice"
          type="warning"
          show-icon
          :closable="false"
          class="top-alert"
        />

        <el-form label-width="220px">
          <el-form-item label="考评目标供应商" required :error="fieldErrors.supplier_id">
            <!-- B3=甲：数据源=§1.3 列表接口全量（不前端过滤状态；非法目标由后端 40401/40001 兜底）；显示名称、提交 supplier_id -->
            <el-select
              v-model="formModel.supplier_id"
              filterable
              :disabled="isResubmit || supplierOptionsLoading"
              :loading="supplierOptionsLoading"
              placeholder="选择被评供应商（§1.3 全量档案）"
              style="width: 100%"
            >
              <el-option v-for="s in supplierOptions" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
          </el-form-item>

          <el-form-item label="考评周期" required :error="fieldErrors.period">
            <!-- 契约无季度编码列：daterange 直出 period_start/period_end（用户口径） -->
            <el-date-picker
              v-model="formModel.periodRange"
              type="daterange"
              value-format="YYYY-MM-DD"
              range-separator="至"
              start-placeholder="周期起"
              end-placeholder="周期止"
              :disabled="isResubmit"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item v-if="isResubmit && editingEvaluation" label="原取数模式">
            <!-- 重提不回传 mode；此处只读回显该单原模式 -->
            <el-tag :type="getEvaluationModeTagType(editingEvaluation.mode)">
              {{ getEvaluationModeLabel(editingEvaluation.mode) }}
            </el-tag>
          </el-form-item>

          <!-- 客观履约事实输入区（契约附录 A4.1 的 8 字段；重提态原单 mode=mock 时禁用） -->
          <div
            v-for="card in PERFORMANCE_FACT_CARDS"
            :key="card.title"
            class="fact-card"
            :class="{ 'fact-card-disabled': factAreaDisabled }"
          >
            <div class="fact-title">{{ card.title }}</div>
            <el-form-item
              v-for="field in card.fields"
              :key="field.key"
              :label="field.label"
              :error="fieldErrors[field.key]"
            >
              <el-input-number
                v-model="formModel.facts[field.key]"
                :min="0"
                :precision="field.kind === 'int' ? 0 : (field.precision ?? 2)"
                :step="field.kind === 'int' ? 1 : 0.1"
                :disabled="factAreaDisabled || submitting"
                style="width: 200px"
              />
              <span v-if="field.hint" class="field-hint">{{ field.hint }}</span>
            </el-form-item>
          </div>

          <!-- 合规维度卡：只读说明（用户裁决：契约 C 由后端自动读 APPROVED 资质计算，radio 已删） -->
          <div class="fact-card" :class="{ 'fact-card-disabled': factAreaDisabled }">
            <div class="fact-title">4. 合规维度 (Compliance - 权重 10%)</div>
            <el-alert :title="COMPLIANCE_READONLY_NOTICE" type="info" show-icon :closable="false" />
          </div>

          <!-- 静态权重说明（B1=甲：不实现原型「实时算分引擎结果」区与本地 computed 公式） -->
          <div class="weight-box">
            <div class="weight-title">计分与评级规则说明（后端硬编码，前端不计算）</div>
            <div>{{ PERFORMANCE_WEIGHT_NOTICE.formula }}</div>
            <div>{{ PERFORMANCE_WEIGHT_NOTICE.gradeMapping }}</div>
            <div class="weight-sub">{{ PERFORMANCE_WEIGHT_NOTICE.readonlyNote }}</div>
          </div>

          <el-form-item style="margin-top: 24px">
            <el-button type="primary" size="large" :loading="submitting" @click="handleSubmit">
              {{ isResubmit ? '提交修正事实并重提' : '提交客观事实单' }}
            </el-button>
            <el-button size="large" :disabled="submitting" @click="backToList">取消</el-button>
          </el-form-item>
        </el-form>
      </div>
    </template>
  </div>
</template>

<style scoped>
/* 子组件不吃父视图 scoped 样式（scoped 属性只命中本组件元素），页头按既有范式自带一份 */
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #ebeef5;
  padding-bottom: 16px;
  margin-bottom: 20px;
}
.page-title {
  font-size: 18px;
  font-weight: bold;
  color: #303133;
}
.top-alert {
  margin-bottom: 16px;
}
.filter-form {
  margin-bottom: 10px;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.score-text {
  font-weight: bold;
  color: #409eff;
}
.cell-sub {
  font-size: 12px;
  color: #909399;
}
.muted-text {
  color: #909399;
  font-size: 13px;
}
.form-container {
  padding: 4px 0;
}
.fact-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 16px 16px 4px;
  margin-bottom: 16px;
  background: #fafcff;
}
.fact-card-disabled {
  opacity: 0.6;
  background: #f5f5f5;
}
.fact-title {
  font-weight: bold;
  color: #2b3b4e;
  margin-bottom: 12px;
}
.field-hint {
  font-size: 12px;
  color: #909399;
  margin-left: 10px;
}
.weight-box {
  border: 1px dashed #d3d6db;
  border-radius: 6px;
  padding: 14px 16px;
  margin-bottom: 8px;
  color: #606266;
  font-size: 13px;
  line-height: 1.8;
  background: #f4f8fe;
}
.weight-title {
  font-weight: bold;
  color: #2b3b4e;
  font-size: 14px;
}
.weight-sub {
  color: #909399;
}
</style>
