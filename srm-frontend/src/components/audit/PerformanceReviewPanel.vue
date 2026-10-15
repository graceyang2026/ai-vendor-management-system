<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getEvaluationDetailApi, listEvaluationsApi, reviewEvaluationApi } from '@/api/performance'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { ensureSupplierNames, resolveSupplierName } from '@/utils/supplierNameCache'
import {
  DECISION,
  ENTITY_TYPE,
  EVALUATION_STATUS,
  EVALUATION_STATUS_OPTIONS,
  NULL_FIELD_PLACEHOLDER,
  PERFORMANCE_REVIEW_DECISION_OPTIONS,
  formatCreatedAt,
  getEvaluationModeLabel,
  getEvaluationModeTagType,
  getGradeTagType,
  getStatusLabel,
  getStatusTagType,
  type DecisionCode,
  type EvaluationStatusCode,
  type EvaluationStatusOption,
} from '@/constants/auditLogView'
import {
  DIMENSION_SCORES_COLUMN_LABEL,
  GRADE_CD_WARNING_NOTICE,
  PERFORMANCE_BOOL_LABEL,
  PERFORMANCE_FACT_DETAIL_ITEMS,
  PERFORMANCE_RESULT_EXTRA_LABELS,
  PERFORMANCE_RESULT_SCORE_ITEMS,
  REVIEW_COMMENT_REQUIRED_MSG,
  REVIEW_DECISION_ALERT,
} from '@/constants/performanceValidation'
import type {
  PerformanceEvaluationResponse,
  PerformanceEvaluationResult,
  PerformanceFactRecord,
  PerformanceReviewDecisionRequest,
} from '@/types/performance'

/**
 * 审核员绩效评价复核面板（模块三 F3-rev；UI 参照 docs/mockups/原型展示-审核员v1.1_待评审20260917.html
 * L221-252 perf-review-list，由 ReviewListView 的 perf-review-list 菜单挂载本组件，不新增路由/菜单项）。
 *
 * 数据源：§3.4 列表（AUDITOR 全量可见；默认 status=PENDING_REVIEW 待复核队列 + 状态筛选 + 分页）、
 * §3.5 详情（每行「查看详情」只读弹窗）、§3.3 复核（decision/comment/version）。
 *
 * 用户 2026-10-15 已拍板口径：
 * - B1=甲：五维分值/综合得分/评级全部是后端算分结果**只读展示**，本页不做任何本地计算，
 *   也不提供任何改分入口（防篡改铁律，rules.md 4.3）。
 * - B4=甲：mode 出参只读回显「真实录入 / 演示生成」。
 * - F3-rev 新增要求：**每一行**固定「查看详情」（参照本页「查看资质档案」弹窗范式），
 *   弹窗 = 基本信息（供应商/周期/mode/状态/version）+ fact_record 8 字段 + result 9 字段
 *   + reviewed_by/reviewed_at/review_comment 复核留痕，全只读 el-descriptions。
 * - 操作列双维度：仅 PENDING_REVIEW 行给「复核通过 / 驳回打分」，已归档/已退回行显示"已复核完成"；
 *   角色维度由路由守卫 meta.roles=['AUDITOR'] 承担，后端 40301/40302 才是安全边界。
 * - D 级文案（api-spec §4.1 L361）：C/D 仅风险预警打标、需人工走停用申请，不出现"硬阻断"表述。
 *
 * 供应商名：契约出参只有 supplier_id（附录 A4.2），名称经 utils/supplierNameCache 懒查 §1.4 详情补齐
 * （AUDITOR 全量可读；查不到回退 `#<id>` 占位），**不新增后端接口**。
 */

const listLoading = ref(false)
const rows = ref<PerformanceEvaluationResponse[]>([])
const total = ref(0)
const query = reactive<{
  status: EvaluationStatusCode | ''
  page: number
  page_size: number
}>({
  // 原型口径：本页默认是"待复核队列"，其余状态经筛选查看
  status: EVALUATION_STATUS.PENDING_REVIEW,
  page: 1,
  page_size: 20,
})

const statusFilterOptions: EvaluationStatusOption[] = EVALUATION_STATUS_OPTIONS
const decisionOptions = PERFORMANCE_REVIEW_DECISION_OPTIONS

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

/* ---------------------------------------------------------------- 只读展示辅助（零计算：直读后端 result 字段） */

function supplierDisplayName(id?: number | null): string {
  return resolveSupplierName(id)
}

function statusLabel(code?: string | null): string {
  // 同码值多域：绩效三态中文必须按 entity_type 分流，禁止当供应商状态消费
  return getStatusLabel(code, ENTITY_TYPE.PERFORMANCE_EVALUATION)
}

/** 五维分值列（原型表头带权重标注；纯展示拼接，不做任何运算）。 */
function formatDimensionScores(result?: PerformanceEvaluationResult | null): string {
  if (!result) return NULL_FIELD_PLACEHOLDER
  return [
    result.score_quality,
    result.score_delivery,
    result.score_price,
    result.score_service,
    result.score_compliance,
  ].join(' | ')
}

function recordValue(record: PerformanceFactRecord | undefined, key: string): string {
  if (!record) return NULL_FIELD_PLACEHOLDER
  const value = record[key as keyof PerformanceFactRecord]
  return value === undefined || value === null ? NULL_FIELD_PLACEHOLDER : String(value)
}

function resultValue(result: PerformanceEvaluationResult | undefined, key: string): string {
  if (!result) return NULL_FIELD_PLACEHOLDER
  const value = result[key as keyof PerformanceEvaluationResult]
  return value === undefined || value === null ? NULL_FIELD_PLACEHOLDER : String(value)
}

function boolLabel(value?: boolean | null): string {
  if (value === undefined || value === null) return NULL_FIELD_PLACEHOLDER
  return value ? PERFORMANCE_BOOL_LABEL.yes : PERFORMANCE_BOOL_LABEL.no
}

/** 是否可复核（状态维度；契约 §3.3 仅 PENDING_REVIEW，其余 40302。角色维度见组件头注释）。 */
function canReview(row: PerformanceEvaluationResponse): boolean {
  return row.status === EVALUATION_STATUS.PENDING_REVIEW
}

/* ---------------------------------------------------------------- 查看详情（§3.5 只读弹窗） */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<PerformanceEvaluationResponse | null>(null)

async function openDetail(row: PerformanceEvaluationResponse) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await getEvaluationDetailApi(row.id)
    detail.value = res.data ?? null
    if (detail.value) {
      void ensureSupplierNames([detail.value.supplier_id])
    }
  } catch (err) {
    showRequestError(err)
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const detailRiskWarning = computed(() => detail.value?.result?.risk_warning === true)

/* ---------------------------------------------------------------- 复核（§3.3 弹窗：decision + comment + version） */

const reviewVisible = ref(false)
const reviewSubmitting = ref(false)
const reviewTarget = ref<PerformanceEvaluationResponse | null>(null)
const reviewForm = reactive<{ decision: DecisionCode; comment: string }>({
  decision: DECISION.APPROVE,
  comment: '',
})
const reviewCommentError = ref('')

const isRejectDecision = computed(() => reviewForm.decision === DECISION.REJECT)

/** 原型两个按钮分别预置 decision，仍进同一对话框（传码只有 APPROVE/REJECT 两码）。 */
function openReview(row: PerformanceEvaluationResponse, decision: DecisionCode) {
  reviewTarget.value = row
  reviewForm.decision = decision
  reviewForm.comment = ''
  reviewCommentError.value = ''
  reviewVisible.value = true
  void ensureSupplierNames([row.supplier_id])
}

function closeReview() {
  reviewVisible.value = false
  reviewSubmitting.value = false
}

async function submitReview() {
  const row = reviewTarget.value
  if (!row || reviewSubmitting.value) return
  const comment = reviewForm.comment.trim()
  // 契约 §3.3：decision=REJECT 时 comment 必填（后端 40001「驳回时必须填写意见」，前端同源先验）
  if (isRejectDecision.value && !comment) {
    reviewCommentError.value = REVIEW_COMMENT_REQUIRED_MSG
    return
  }
  reviewCommentError.value = ''
  const payload: PerformanceReviewDecisionRequest = {
    // version 取列表行现值（§3.4 出参即 §3.3 合法取值链）；不匹配 40901 → 刷新换新 version
    version: row.version,
    decision: reviewForm.decision,
  }
  if (comment) payload.comment = comment
  reviewSubmitting.value = true
  try {
    const res = await reviewEvaluationApi(row.id, payload)
    const latest = res.data
    const suffix = latest
      ? `当前状态：${statusLabel(latest.status)}（version=${latest.version}，复核留痕以接口响应为准）`
      : '状态以列表刷新为准'
    ElMessage.success(`复核结论已提交，${suffix}`)
    closeReview()
    await fetchList()
  } catch (err) {
    handleReviewError(err)
  } finally {
    reviewSubmitting.value = false
  }
}

/**
 * 错误分流（文案一律优先透传后端 message）：
 * 40901 version 落后 / 40302 已被并发复审或状态已变 → 提示后刷新列表换新 version；
 * 40401 评价记录不存在 → 同样刷新（单据已注销）；40301 角色不符、40001 驳回缺意见 → 直显。
 */
function handleReviewError(err: unknown) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  const serverChanged =
    err.code === ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT ||
    err.code === ERROR_CODE.STATUS_NOT_ALLOWED ||
    err.code === ERROR_CODE.NOT_FOUND
  ElMessage.error(err.message)
  if (serverChanged) {
    closeReview()
    void fetchList()
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <span class="page-title">业务员季度绩效打分复核</span>
      <div class="header-actions">
        <el-button type="primary" plain :loading="listLoading" @click="fetchList">刷新列表</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      show-icon
      :closable="false"
      class="top-alert"
      title="复核对象是业务员提交的客观事实与后端算分结果：得分与评级由算分引擎硬编码产出，本页不提供任何人工改分入口。"
    />

    <el-form :inline="true" class="filter-form">
      <el-form-item label="复核状态">
        <!-- 传码显中文：value=EvaluationStatus 码（§3.4 status 入参），label=绩效域字典中文 -->
        <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 160px" @change="handleStatusChange">
          <el-option v-for="opt in statusFilterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
    </el-form>

    <!-- 复核列表（契约 §3.4；原型 perf-review-list 列序） -->
    <el-table v-loading="listLoading" :data="rows" border stripe style="width: 100%">
      <el-table-column label="供应商名称" min-width="180" show-overflow-tooltip>
        <template #default="scope">{{ supplierDisplayName(scope.row.supplier_id) }}</template>
      </el-table-column>
      <el-table-column label="考评周期" width="200">
        <template #default="scope">{{ scope.row.period_start }} ~ {{ scope.row.period_end }}</template>
      </el-table-column>
      <el-table-column :label="DIMENSION_SCORES_COLUMN_LABEL" min-width="240">
        <template #default="scope">{{ formatDimensionScores(scope.row.result) }}</template>
      </el-table-column>
      <el-table-column label="系统算分" width="110">
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
          <!-- B4=甲：mode 出参只读回显（真实录入 / 演示生成） -->
          <el-tag :type="getEvaluationModeTagType(scope.row.mode)" size="small">
            {{ getEvaluationModeLabel(scope.row.mode) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="复核状态" width="130">
        <template #default="scope">
          <el-tag size="small" :type="getStatusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="复核操作" width="250" fixed="right">
        <template #default="scope">
          <!-- F3-rev 用户要求：每行固定「查看详情」（所有状态都有，§3.5 只读弹窗） -->
          <el-button size="small" link type="primary" @click="openDetail(scope.row)">查看详情</el-button>
          <!-- 状态×角色双维度：仅 PENDING_REVIEW 行给复核入口；后端 40301/40302 兜底 -->
          <template v-if="canReview(scope.row)">
            <el-button size="small" type="success" @click="openReview(scope.row, DECISION.APPROVE)">
              复核通过
            </el-button>
            <el-button size="small" type="danger" @click="openReview(scope.row, DECISION.REJECT)">
              驳回打分
            </el-button>
          </template>
          <span v-else class="done-text">已复核完成</span>
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
    <el-empty v-if="!listLoading && rows.length === 0" description="暂无符合条件的绩效评价单" :image-size="60" />

    <!-- 查看详情（§3.5 全只读；范式同本页「查看资质档案」弹窗） -->
    <el-dialog v-model="detailVisible" title="绩效评价单详情（只读）" width="900px">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small" class="detail-block">
            <el-descriptions-item label="评价单 ID">{{ detail.id }}</el-descriptions-item>
            <el-descriptions-item label="供应商名称">{{ supplierDisplayName(detail.supplier_id) }}</el-descriptions-item>
            <el-descriptions-item label="考评周期">{{ detail.period_start }} ~ {{ detail.period_end }}</el-descriptions-item>
            <el-descriptions-item label="取数模式">
              <el-tag size="small" :type="getEvaluationModeTagType(detail.mode)">
                {{ getEvaluationModeLabel(detail.mode) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="复核状态">
              <el-tag size="small" :type="getStatusTagType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="version（乐观锁）">{{ detail.version }}</el-descriptions-item>
            <el-descriptions-item label="提交人">{{ detail.created_by_name || NULL_FIELD_PLACEHOLDER }}</el-descriptions-item>
            <el-descriptions-item label="提交时间">{{ formatCreatedAt(detail.created_at) }}</el-descriptions-item>
          </el-descriptions>

          <h4 class="detail-title">客观事实数据 fact_record（入站 8 字段，契约附录 A4.1）</h4>
          <el-descriptions :column="2" border size="small" class="detail-block">
            <el-descriptions-item
              v-for="item in PERFORMANCE_FACT_DETAIL_ITEMS"
              :key="item.key"
              :label="item.label"
            >
              {{ recordValue(detail.fact_record, item.key) }}
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="detail-title">后端算分结果 result（9 字段，只读，契约附录 A4.3）</h4>
          <el-descriptions :column="2" border size="small" class="detail-block">
            <el-descriptions-item
              v-for="item in PERFORMANCE_RESULT_SCORE_ITEMS"
              :key="item.key"
              :label="item.label"
            >
              {{ resultValue(detail.result, item.key) }}
            </el-descriptions-item>
            <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.total_score">
              <span class="score-text">{{ resultValue(detail.result, 'total_score') }}</span>
            </el-descriptions-item>
            <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.grade">
              <el-tag size="small" :type="getGradeTagType(detail.result?.grade)">
                {{ detail.result?.grade ?? NULL_FIELD_PLACEHOLDER }} 级
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.quality_exempt">
              {{ boolLabel(detail.result?.quality_exempt) }}
            </el-descriptions-item>
            <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.risk_warning">
              {{ boolLabel(detail.result?.risk_warning) }}
            </el-descriptions-item>
          </el-descriptions>

          <h4 class="detail-title">复核留痕（§3.3 回写，可空；重提后由后端清理，展示以接口响应为准）</h4>
          <el-descriptions :column="2" border size="small" class="detail-block">
            <el-descriptions-item label="复核人 reviewed_by">{{ detail.reviewed_by ?? NULL_FIELD_PLACEHOLDER }}</el-descriptions-item>
            <el-descriptions-item label="复核时间 reviewed_at">{{ formatCreatedAt(detail.reviewed_at) }}</el-descriptions-item>
            <el-descriptions-item label="复核意见 review_comment" :span="2">
              {{ detail.review_comment || NULL_FIELD_PLACEHOLDER }}
            </el-descriptions-item>
            <el-descriptions-item label="最近更新" :span="2">{{ formatCreatedAt(detail.updated_at) }}</el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="detailRiskWarning"
            :title="GRADE_CD_WARNING_NOTICE"
            type="warning"
            show-icon
            :closable="false"
            class="detail-block"
          />
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- §3.3 复核对话框（入参 version/decision/comment；契约字段名 decision/comment） -->
    <el-dialog v-model="reviewVisible" title="绩效评价复核签署" width="640px">
      <el-alert type="warning" show-icon :closable="false" class="top-alert" :title="REVIEW_DECISION_ALERT" />

      <el-descriptions v-if="reviewTarget" :column="2" border size="small" class="detail-block">
        <el-descriptions-item label="供应商名称">
          {{ supplierDisplayName(reviewTarget.supplier_id) }}
        </el-descriptions-item>
        <el-descriptions-item label="考评周期">
          {{ reviewTarget.period_start }} ~ {{ reviewTarget.period_end }}
        </el-descriptions-item>
        <el-descriptions-item :label="DIMENSION_SCORES_COLUMN_LABEL">
          {{ formatDimensionScores(reviewTarget.result) }}
        </el-descriptions-item>
        <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.total_score">
          <span class="score-text">{{ reviewTarget.result?.total_score ?? NULL_FIELD_PLACEHOLDER }}</span>
        </el-descriptions-item>
        <el-descriptions-item :label="PERFORMANCE_RESULT_EXTRA_LABELS.grade">
          <el-tag size="small" :type="getGradeTagType(reviewTarget.result?.grade)">
            {{ reviewTarget.result?.grade ?? NULL_FIELD_PLACEHOLDER }} 级
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="取数模式">
          <el-tag size="small" :type="getEvaluationModeTagType(reviewTarget.mode)">
            {{ getEvaluationModeLabel(reviewTarget.mode) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="提交人 / 时间">
          {{ reviewTarget.created_by_name || NULL_FIELD_PLACEHOLDER }} · {{ formatCreatedAt(reviewTarget.created_at) }}
        </el-descriptions-item>
        <el-descriptions-item label="version（随行回传，不匹配 40901）">{{ reviewTarget.version }}</el-descriptions-item>
      </el-descriptions>

      <el-form label-width="120px">
        <el-form-item label="复核结论" required>
          <!-- 传码显中文：value=APPROVE/REJECT 两码（字典 PERFORMANCE_REVIEW_DECISION_OPTIONS） -->
          <el-radio-group v-model="reviewForm.decision">
            <el-radio v-for="opt in decisionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="复核意见" :required="isRejectDecision" :error="reviewCommentError">
          <el-input
            v-model="reviewForm.comment"
            type="textarea"
            :rows="3"
            maxlength="512"
            show-word-limit
            :placeholder="isRejectDecision ? '驳回必填：请写明事实数据或算分依据的疑点' : '选填：建议写明复核核查依据'"
          />
        </el-form-item>
        <el-form-item v-if="isRejectDecision" label-width="120px">
          <span class="reject-hint">驳回后该评价单退回原业务员（RETURNED），由其修正客观事实后重提；不得在本页改动分数。</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="closeReview">取消</el-button>
        <el-button type="primary" :loading="reviewSubmitting" @click="submitReview">提交复核结论</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
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
.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.top-alert {
  margin-bottom: 16px;
}
.filter-form {
  margin-bottom: 10px;
}
.score-text {
  font-weight: bold;
  color: #409eff;
}
.done-text {
  color: #909399;
  font-size: 13px;
}
.reject-hint {
  font-size: 12px;
  color: #e6a23c;
}
.detail-body {
  max-height: 66vh;
  overflow-y: auto;
}
.detail-block {
  margin-bottom: 14px;
}
.detail-title {
  border-left: 4px solid #67c23a;
  padding-left: 8px;
  margin: 4px 0 10px;
  color: #303133;
  font-size: 14px;
}
</style>
