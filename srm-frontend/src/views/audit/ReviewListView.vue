<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, Star, Select, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import ReviewLogTable from '@/components/audit/ReviewLogTable.vue'
import ReviewHistoryLogTable from '@/components/audit/ReviewHistoryLogTable.vue'
import QualificationPanel from '@/components/business/QualificationPanel.vue'
import LifecycleDecisionDialog from '@/components/business/LifecycleDecisionDialog.vue'
import { auditSupplierApi, getSupplierDetailApi, getSupplierListApi } from '@/api/supplier'
import { getLifecycleTodoApi } from '@/api/lifecycle'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { MODULE2_VALIDATION, SUPPLIER_LOCK_CONFLICT_MSG } from '@/constants/supplierValidation'
import {
  ADMISSION_DECISION_OPTIONS,
  DECISION,
  ENTITY_TYPE,
  LIFECYCLE_REQUEST_STATUS,
  LIFECYCLE_REQUEST_STATUS_OPTIONS,
  LIFECYCLE_REQUEST_TYPE,
  LIFECYCLE_REQUEST_TYPE_OPTIONS,
  SUPPLIER_STATUS,
  SUPPLIER_STATUS_OPTIONS,
  NULL_FIELD_PLACEHOLDER,
  SUPPLIER_PROFILE_MISSING_LABEL,
  formatCreatedAt,
  getLifecycleTargetStatusLabel,
  getLifecycleTypeLabel,
  getStatusLabel,
  getStatusTagType,
  getSupplierTypeLabel,
  type DecisionCode,
  type LifecycleRequestStatusCode,
  type LifecycleRequestTypeCode,
  type SupplierStatusCode,
} from '@/constants/auditLogView'
import type { SupplierAuditDecisionRequest, SupplierItem, SupplierListQuery } from '@/types/supplier'
import type {
  LifecycleRequestResponse,
  LifecycleRequestTodoItem,
  LifecycleRequestTodoQuery,
} from '@/types/lifecycle'

/**
 * 审计与合规控制台（UI 参照 docs/mockups/原型展示-审核员v1.1_待评审20260917.html，
 * 深色侧栏 #1a1b26 底 / #a9b1d6 文字 / active #67C23A 原样保留）。
 *
 * 红线 1（路由守卫）：本页仅 AUDITOR 可访问，由 router meta.roles=['AUDITOR']
 * （/audit/review-list）强制拦截；页内按钮级门控再叠一层状态维度（后端 40301/40302 才是安全边界，
 * 前端隐藏/禁用只是体验层，不复制状态机去"假装"允许）。
 * 红线 2（契约优先，用户拍板 20261015）：原型与 docs/接口契约文档 冲突时一律以契约为准。
 * 原型里"已停用供应商 → 一键恢复为正常合作"这类直改状态的交互**已废弃**（违反 acceptance §1.3），
 * 生命周期变更只能走 §2.4 业务员提申请 → §2.6 审核员终审两步；本页只做 §2.6 决策，
 * 不提供任何 PATCH 供应商状态的旁路，也不在前端本地乐观改状态。
 *
 * 本轮（20261015）已把演示数据全部替换为真实接口：
 * - 准入资质审核列表：§1.3 GET /suppliers?status=PENDING_REVIEW（PageResult list/total/page/page_size）
 * - 审核详情：§1.4 GET /suppliers/{id}（签署所需 version 的唯一来源）+ §1.8 资质区（复用 QualificationPanel）
 * - 准入签署：§2.2 POST /suppliers/{id}/audit body {version, decision, comment}
 * - 存续期资质单独审批：§2.3 PUT /qualifications/{id}/review（在 QualificationPanel 内，仅 NORMAL 供应商）
 * - 停用/淘汰终审：§2.7 GET /lifecycle-requests（跨供应商终审待办队列，PageResult 分页 +
 *   supplier_name/supplier_status 冗余两列；行内 version 与 §2.5 同为 §2.6 入参 version 的合法取值源）
 *   + §2.6 POST /lifecycle-requests/{id}/decision body {decision, comment, version}
 * 绩效评价复核 / 操作与审批日志不在本轮范围（前者属模块三，后者沿用日志视图）。
 */
const router = useRouter()
const userStore = useUserStore()

type MenuKey = 'audit-list' | 'status-decision-list' | 'perf-review-list' | 'log-list'
type ViewKey = MenuKey | 'audit-detail'

const activeMenu = ref<MenuKey>('audit-list')
const currentView = ref<ViewKey>('audit-list')

/** 视图切换：菜单直达视图；进入详情时菜单保持"准入资质审核"高亮（与原型一致）。 */
function handleMenuSelect(index: string) {
  activeMenu.value = index as MenuKey
  currentView.value = index as ViewKey
  if (index === 'status-decision-list' && decisionRows.value.length === 0) {
    void loadDecisionRows()
  }
}

function navigateTo(view: ViewKey) {
  currentView.value = view
}

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}

/** 通用错误提示：业务 code 的 message 一律优先透传后端（utils/request.ts 同口径）。 */
function showRequestError(err: unknown) {
  if (err instanceof ApiError) {
    ElMessage.error(err.message)
  } else {
    ElMessage.error('网络异常，请稍后重试')
  }
}

/* ================================================================ F1 · 准入资质审核（§1.3 / §1.4 / §2.2） */

const auditLoading = ref(false)
const auditList = ref<SupplierItem[]>([])
const auditTotal = ref(0)
const auditQuery = reactive({
  keyword: '',
  // 默认聚焦待审核（本页存在意义）；状态维度可切换查看其他状态供应商的资质档案
  status: SUPPLIER_STATUS.PENDING_REVIEW as SupplierStatusCode | '',
  page: 1,
  page_size: 20,
})

async function fetchAuditList() {
  auditLoading.value = true
  try {
    const params: SupplierListQuery = { page: auditQuery.page, page_size: auditQuery.page_size }
    if (auditQuery.keyword.trim()) params.keyword = auditQuery.keyword.trim()
    if (auditQuery.status) params.status = auditQuery.status
    const res = await getSupplierListApi(params)
    auditList.value = res.data?.list ?? []
    auditTotal.value = res.data?.total ?? 0
  } catch (err) {
    auditList.value = []
    auditTotal.value = 0
    showRequestError(err)
  } finally {
    auditLoading.value = false
  }
}

function handleAuditSearch() {
  auditQuery.page = 1
  void fetchAuditList()
}

function handleAuditReset() {
  auditQuery.keyword = ''
  auditQuery.status = SUPPLIER_STATUS.PENDING_REVIEW
  auditQuery.page = 1
  void fetchAuditList()
}

function handleAuditPageChange(page: number) {
  auditQuery.page = page
  void fetchAuditList()
}

function handleAuditSizeChange(size: number) {
  auditQuery.page_size = size
  auditQuery.page = 1
  void fetchAuditList()
}

onMounted(() => {
  void fetchAuditList()
})

/** 行是否处于待审核（签署入口唯一出现条件；码值比较走 SUPPLIER_STATUS 常量，模板不出现裸串值）。 */
function isPendingReview(row: SupplierItem): boolean {
  return row.status === SUPPLIER_STATUS.PENDING_REVIEW
}

/* ---------------------------------------------------------------- 审核详情 + 签署表单（§1.4 / §2.2） */

const currentSupplier = ref<SupplierItem | null>(null)
const detailLoading = ref(false)
const signing = ref(false)
/** 签署表单 UI 模型（非契约对象）：decision 收敛为 DECISION 两码，version 不入表单（取自 §1.4 详情现值） */
const auditForm = reactive<{ decision: DecisionCode; comment: string }>({
  decision: DECISION.APPROVE,
  comment: '',
})
const rejectCommentError = ref('')

/** 进详情一律重拉 §1.4：列表行 version 可能已被他人写操作推进，签署前必须取服务端最新乐观锁版本。 */
async function loadDetail(id: number) {
  detailLoading.value = true
  try {
    const res = await getSupplierDetailApi(id)
    currentSupplier.value = res.data
    return res.data
  } catch (err) {
    showRequestError(err)
    return null
  } finally {
    detailLoading.value = false
  }
}

function resetAuditForm() {
  auditForm.decision = DECISION.APPROVE
  auditForm.comment = ''
  rejectCommentError.value = ''
}

/** 「进行准入审核」：仅 PENDING_REVIEW 行出现该入口（任务卡 F1.1）。 */
async function openAuditPage(row: SupplierItem) {
  resetAuditForm()
  // 先用列表行快照占位（避免详情视图整块空白），随后重拉 §1.4 取服务端最新 version 作为签署基线
  currentSupplier.value = row
  navigateTo('audit-detail')
  await loadDetail(row.id)
}

/** 「查看资质档案」：非待审核行的只读入口（AUDITOR 视角 §1.8 全量可见；NORMAL 供应商行内另有 §2.3 审批）。 */
const qualificationDialogVisible = ref(false)
const qualificationSupplier = ref<SupplierItem | null>(null)

function openQualificationDialog(row: SupplierItem) {
  qualificationSupplier.value = row
  qualificationDialogVisible.value = true
}

async function handleSign() {
  const supplier = currentSupplier.value
  if (!supplier) return
  rejectCommentError.value = ''
  const comment = auditForm.comment.trim()
  // 本地先验：§2.2 REJECT 时 comment 必填（后端 Service 折 40001，同一契约文案 REJECT_COMMENT_MSG）
  if (auditForm.decision === DECISION.REJECT && !comment) {
    rejectCommentError.value = MODULE2_VALIDATION.REJECT_COMMENT_MSG
    ElMessage.error(MODULE2_VALIDATION.REJECT_COMMENT_MSG)
    return
  }
  // §2.2 请求体三字段（version 取 §1.4 详情现值；comment 选填侧空白不提交，
  // 必填侧已由上方本地先验与服务端 40001 双重把关）
  const payload: SupplierAuditDecisionRequest = {
    version: supplier.version,
    decision: auditForm.decision,
  }
  if (comment) payload.comment = comment
  signing.value = true
  try {
    await auditSupplierApi(supplier.id, payload)
    ElMessage.success('准入审核结论已签署，供应商状态与资质批复以服务端结果为准')
    resetAuditForm()
    // 铁律：不本地改状态 —— 重拉 §1.4 取服务端事实（新 version + 新 status），并刷新列表
    await loadDetail(supplier.id)
    await fetchAuditList()
  } catch (err) {
    handleSignError(err)
  } finally {
    signing.value = false
  }
}

/** §2.2 错误分流：40901 用契约文案并即时重拉详情换新 version（业务员侧 SupplierListView 同款范式） */
function handleSignError(err: unknown) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  if (err.code === ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT) {
    ElMessage.error(SUPPLIER_LOCK_CONFLICT_MSG)
    const id = currentSupplier.value?.id
    if (id) void loadDetail(id)
    return
  }
  if (err.code === ERROR_CODE.VALIDATION_FAILED) {
    ElMessage.error(err.message)
    if (err.message === MODULE2_VALIDATION.REJECT_COMMENT_MSG) {
      rejectCommentError.value = err.message
    }
    return
  }
  if (err.code === ERROR_CODE.STATUS_NOT_ALLOWED || err.code === ERROR_CODE.NOT_FOUND) {
    // 40302（该供应商已被他人签署/状态已变）、40401：服务端事实已变，提示后重拉列表纠偏
    ElMessage.error(err.message)
    void fetchAuditList()
    return
  }
  showRequestError(err)
}

/* ================================================================ F2 · 停用/淘汰终审决策（§2.7 待办队列 + §2.6 终审） */

/**
 * 待办队列唯一数据源：§2.7 GET /lifecycle-requests（跨供应商聚合，仅 AUDITOR，非 AUDITOR 服务端 40301）。
 * 分页（page/page_size/total）、`applied_at` 倒序、以及 supplier_name / supplier_status 冗余两列
 * 全部由后端负责：本页**单次调用**取数，不再扫描 §1.3 供应商档案、不再逐供应商回查 §2.5
 * （原「逐供应商循环回查」过渡实现连同其批大小/限并发/截断提示参数已随该端点落地整体删除）。
 * page_size 契约默认 20、**上限 100**（超出由后端按 §1.3 收敛口径折 100，不报错），故分页档位封顶 100。
 */
const DECISION_PAGE_SIZE_MAX = 100

const decisionLoading = ref(false)
const decisionRows = ref<LifecycleRequestTodoItem[]>([])
/** §2.7 PageResult.total：当前筛选条件下的**全量**件数（不是本页行数） */
const decisionTotal = ref(0)
const decisionQuery = reactive({
  // 工作台默认聚焦"待终审"（本页存在意义）；status/type 一律传后端枚举码，空值 = 不过滤（契约 §2.7 缺省口径）
  status: LIFECYCLE_REQUEST_STATUS.PENDING as LifecycleRequestStatusCode | '',
  type: '' as LifecycleRequestTypeCode | '',
  page: 1,
  page_size: 20,
})

/**
 * 表头计数文案：直接取服务端 total，不拿本页行数凑（分页后页内计数≠待办总量）。
 * 状态中文经 getStatusLabel + ENTITY_TYPE.LIFECYCLE_REQUEST 分流（同码值多域，须走生命周期口径）；
 * 未选状态时 §2.7 不过滤，故显示"全部"。
 */
const decisionSummaryLabel = computed(() => {
  const scope = decisionQuery.status
    ? getStatusLabel(decisionQuery.status, ENTITY_TYPE.LIFECYCLE_REQUEST)
    : '全部'
  return `${scope} ${decisionTotal.value} 件`
})

async function loadDecisionRows() {
  decisionLoading.value = true
  try {
    const params: LifecycleRequestTodoQuery = {
      page: decisionQuery.page,
      page_size: Math.min(decisionQuery.page_size, DECISION_PAGE_SIZE_MAX),
    }
    if (decisionQuery.status) params.status = decisionQuery.status
    if (decisionQuery.type) params.type = decisionQuery.type
    const res = await getLifecycleTodoApi(params)
    decisionRows.value = res.data?.list ?? []
    decisionTotal.value = res.data?.total ?? 0
  } catch (err) {
    decisionRows.value = []
    decisionTotal.value = 0
    showRequestError(err)
  } finally {
    decisionLoading.value = false
  }
}

function handleDecisionSearch() {
  decisionQuery.page = 1
  void loadDecisionRows()
}

function handleDecisionReset() {
  decisionQuery.status = LIFECYCLE_REQUEST_STATUS.PENDING
  decisionQuery.type = ''
  decisionQuery.page = 1
  void loadDecisionRows()
}

function handleDecisionPageChange(page: number) {
  decisionQuery.page = page
  void loadDecisionRows()
}

function handleDecisionSizeChange(size: number) {
  decisionQuery.page_size = Math.min(size, DECISION_PAGE_SIZE_MAX)
  decisionQuery.page = 1
  void loadDecisionRows()
}

/** 行是否可终审（status=PENDING；已决件只读，任务卡 F2.4）。 */
function canDecide(row: LifecycleRequestTodoItem): boolean {
  return row.status === LIFECYCLE_REQUEST_STATUS.PENDING
}

/** 该 SUSPENDED 供应商是否有在途恢复申请（业务员侧同类型重复提交会撞 40902，故显式标注）。 */
function hasPendingResume(row: LifecycleRequestTodoItem): boolean {
  return (
    row.supplier_status === SUPPLIER_STATUS.SUSPENDED &&
    row.type === LIFECYCLE_REQUEST_TYPE.RESUME &&
    row.status === LIFECYCLE_REQUEST_STATUS.PENDING
  )
}

/**
 * §2.7 冗余供应商列空值判定：供应商已被逻辑删除（@TableLogic 隐身）或存在历史脏 FK 时，
 * 后端批量反查 map miss 后把 supplier_name / supplier_status 折成 **null**（不是空串，也不裁剪申请单行，
 * 源：LifecycleRequestServiceImpl#supplierBriefs + toTodoItem）。展示层据此显式标注"档案缺失"，
 * 绝不 `|| ''` 折叠成"供应商存在"；空串是另一种形态（后端 name 非空约束，理论不出现），不混为一谈。
 */
function isSupplierProfileMissing(row: LifecycleRequestTodoItem): boolean {
  return row.supplier_name === null || row.supplier_status === null
}

/* ---------------------------------------------------------------- §2.6 终审弹窗 */

const decisionDialogVisible = ref(false)
const decisionRow = ref<LifecycleRequestTodoItem | null>(null)

function openDecisionDialog(row: LifecycleRequestTodoItem) {
  if (!canDecide(row)) {
    ElMessage.info('该申请单已决策，仅作只读追溯展示')
    return
  }
  decisionRow.value = row
  decisionDialogVisible.value = true
}

/**
 * §2.6 决策成功后（任务卡 F2.3）：重拉该供应商 §1.4 详情取新 SupplierResponse.version 与最新状态，
 * 供应商状态以服务端联动结果为准，前端绝不本地改写；随后重刷 §2.7 待办队列与准入列表（同视图数据源）。
 */
async function handleDecided(request: LifecycleRequestResponse) {
  const supplierId = request.supplier_id
  const detail = await loadDetailQuiet(supplierId)
  if (detail) {
    ElMessage.info(`供应商「${detail.name}」最新状态：${getStatusLabel(detail.status)}（服务端档案已同步）`)
  }
  if (currentSupplier.value?.id === supplierId) {
    currentSupplier.value = detail ?? currentSupplier.value
  }
  await loadDecisionRows()
  await fetchAuditList()
}

/** 40901/40302/40401 后重刷 §2.7 待办队列换新 version（行内 version 与 §2.5 同口径，皆为 §2.6 合法取值源） */
async function handleDecisionRefresh() {
  await loadDecisionRows()
  await fetchAuditList()
}

async function loadDetailQuiet(supplierId: number) {
  try {
    const res = await getSupplierDetailApi(supplierId)
    return res.data
  } catch (err) {
    showRequestError(err)
    return null
  }
}
</script>

<template>
  <el-container class="admin-layout">
    <!-- 顶栏导航（原型视觉保留） -->
    <el-header height="60px" class="header-bar">
      <div class="logo">SRM 供应商管理系统 | 审计与合规控制台</div>
      <div class="user-info">
        <el-tag type="warning" size="small">审计员 (Auditor)</el-tag>
        <span>欢迎您，{{ userStore.userInfo?.real_name ?? userStore.userInfo?.username ?? '-' }} (合规审计部)</span>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 侧边栏菜单（原型深色：#1a1b26 底 / #a9b1d6 文字 / active #67C23A） -->
      <el-aside width="220px" class="aside-menu">
        <el-menu
          :default-active="activeMenu"
          background-color="#1a1b26"
          text-color="#a9b1d6"
          active-text-color="#67C23A"
          @select="handleMenuSelect"
        >
          <el-menu-item index="audit-list">
            <el-icon><Select /></el-icon>
            <span>供应商准入资质审核</span>
          </el-menu-item>
          <el-menu-item index="status-decision-list">
            <el-icon><SwitchButton /></el-icon>
            <span>状态变更终审决策</span>
          </el-menu-item>
          <el-menu-item index="perf-review-list">
            <el-icon><Star /></el-icon>
            <span>绩效评价复核</span>
          </el-menu-item>
          <el-menu-item index="log-list">
            <el-icon><Document /></el-icon>
            <span>操作与审批日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容展示区域 -->
      <el-main class="main-content">
        <div class="page-card">
          <!-- 1. 准入资质审核列表（真实接口 §1.3） -->
          <template v-if="currentView === 'audit-list'">
            <div class="page-header">
              <span class="page-title">待审核准入供应商申请</span>
              <el-button type="primary" plain @click="fetchAuditList">刷新列表</el-button>
            </div>
            <el-form :inline="true" class="filter-form">
              <el-form-item label="供应商名称">
                <el-input
                  v-model="auditQuery.keyword"
                  placeholder="输入名称或税号"
                  clearable
                  style="width: 200px"
                  @keyup.enter="handleAuditSearch"
                />
              </el-form-item>
              <el-form-item label="状态">
                <!-- 传码显中文：value=SupplierStatus 码（§1.3 status 入参），label=字典中文 -->
                <el-select v-model="auditQuery.status" placeholder="全部状态" clearable style="width: 160px">
                  <el-option
                    v-for="opt in SUPPLIER_STATUS_OPTIONS"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleAuditSearch">查询</el-button>
                <el-button @click="handleAuditReset">重置</el-button>
              </el-form-item>
            </el-form>
            <el-table v-loading="auditLoading" :data="auditList" border stripe style="width: 100%">
              <el-table-column prop="name" label="供应商名称" min-width="180" show-overflow-tooltip />
              <el-table-column prop="tax_no" label="统一社会信用代码/税号" width="190" />
              <el-table-column label="供应商类型" width="130">
                <template #default="scope">{{ getSupplierTypeLabel(scope.row.type) }}</template>
              </el-table-column>
              <el-table-column prop="created_by_name" label="送审业务员" width="120" />
              <!-- 送审时间列｜MVP 口径（用户裁决 20261015）：以 updated_at 近似——契约附录 A1 无 submitted_at
                   字段，本阶段不加该字段、不改契约；二期如需真值再加 submitted_at 或从 audit_log(SUBMIT) 反查 -->
              <el-table-column label="最近更新时间" width="160">
                <template #default="scope">{{ formatCreatedAt(scope.row.updated_at) }}</template>
              </el-table-column>
              <el-table-column label="当前状态" width="120">
                <template #default="scope">
                  <el-tag :type="getStatusTagType(scope.row.status)">{{ getStatusLabel(scope.row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="合规审核" width="160" fixed="right">
                <template #default="scope">
                  <!-- 状态×角色双维度：仅 PENDING_REVIEW 行给签署入口；其余状态只读查看资质档案（§1.8 ALL 可读） -->
                  <el-button
                    v-if="isPendingReview(scope.row)"
                    size="small"
                    type="success"
                    @click="openAuditPage(scope.row)"
                  >
                    进行准入审核
                  </el-button>
                  <el-button v-else size="small" link type="primary" @click="openQualificationDialog(scope.row)">
                    查看资质档案
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              style="margin-top: 16px; justify-content: flex-end"
              :current-page="auditQuery.page"
              :page-size="auditQuery.page_size"
              :page-sizes="[10, 20, 50, 100]"
              :total="auditTotal"
              layout="total, sizes, prev, pager, next"
              @current-change="handleAuditPageChange"
              @size-change="handleAuditSizeChange"
            />
          </template>

          <!-- 2. 准入合规审核详情（§1.4 档案 + §1.8/§2.3 资质 + §2.2 签署 + 追溯日志） -->
          <template v-else-if="currentView === 'audit-detail' && currentSupplier">
            <div class="page-header">
              <span class="page-title">供应商准入合规审核 - {{ currentSupplier.name }}</span>
              <el-button @click="navigateTo('audit-list')">返回待审列表</el-button>
            </div>

            <div v-loading="detailLoading" class="detail-section">
              <h4>企业提交基本信息校验</h4>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="企业全称">{{ currentSupplier.name }}</el-descriptions-item>
                <el-descriptions-item label="统一社会信用代码/税号">{{ currentSupplier.tax_no }}</el-descriptions-item>
                <el-descriptions-item label="企业类型">{{ getSupplierTypeLabel(currentSupplier.type) }}</el-descriptions-item>
                <el-descriptions-item label="联系人/电话">
                  {{ currentSupplier.contact_name }} ({{ currentSupplier.contact_phone }})
                </el-descriptions-item>
                <el-descriptions-item label="送审业务员">{{ currentSupplier.created_by_name }}</el-descriptions-item>
                <el-descriptions-item label="档案创建时间">{{ formatCreatedAt(currentSupplier.created_at) }}</el-descriptions-item>
                <el-descriptions-item label="合作周期">
                  {{ currentSupplier.effective_date }} ~ {{ currentSupplier.expiry_date }}
                </el-descriptions-item>
                <el-descriptions-item label="当前状态">
                  <el-tag :type="getStatusTagType(currentSupplier.status)">{{ getStatusLabel(currentSupplier.status) }}</el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="联系邮箱">{{ currentSupplier.contact_email || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="企业地址">{{ currentSupplier.address || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="备注" :span="2">{{ currentSupplier.remark || '未填写' }}</el-descriptions-item>
              </el-descriptions>
            </div>

            <div class="detail-section">
              <h4>资质档案核验（§1.8；正常合作供应商的存续期补传件可在此单独审批 §2.3）</h4>
              <!-- 复用业务员侧资质组件：AUDITOR 视角全量可见；§2.3 审批入口由组件内"角色×供应商NORMAL×资质PENDING"门控 -->
              <QualificationPanel :supplier-id="currentSupplier.id" :supplier-status="currentSupplier.status" />
            </div>

            <!-- 仅待审核状态显示签署表单（§2.2 只能对 PENDING_REVIEW 签署，其余 40302） -->
            <div v-if="isPendingReview(currentSupplier)" class="audit-box">
              <h4 class="audit-box-title">审计员签署审核结论</h4>
              <el-alert
                type="info"
                show-icon
                :closable="false"
                style="margin-bottom: 16px"
                title="签署将同事务连带批复该供应商全部待审资质（准入期不调 §2.3）；结论不可撤回，如需变更须由业务员重新送审。"
              />
              <el-form label-width="150px">
                <el-form-item label="审核结果" required>
                  <!-- 传码显中文：value=APPROVE/REJECT（DECISION），label=字典中文（含状态语义说明） -->
                  <el-radio-group v-model="auditForm.decision">
                    <el-radio v-for="opt in ADMISSION_DECISION_OPTIONS" :key="opt.value" :value="opt.value">
                      {{ opt.label }}
                    </el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item
                  label="审计驳回/审批意见"
                  :required="auditForm.decision === DECISION.REJECT"
                  :error="rejectCommentError"
                >
                  <el-input
                    v-model="auditForm.comment"
                    type="textarea"
                    :rows="3"
                    maxlength="512"
                    show-word-limit
                    placeholder="若选择驳回，请务必填写具体的退回修改原因（如：营业执照不清晰/税号不匹配）..."
                  />
                </el-form-item>
                <el-form-item>
                  <el-button type="success" :loading="signing" @click="handleSign">提交审核签署结果</el-button>
                  <el-button @click="navigateTo('audit-list')">取消</el-button>
                </el-form-item>
              </el-form>
            </div>
            <el-alert
              v-else
              type="info"
              show-icon
              :closable="false"
              style="margin-bottom: 12px"
              title="该供应商当前状态不是「待审核」，准入签署入口已关闭（§2.2 仅接受 PENDING_REVIEW，服务端 40302 兜底）；如需变更合作状态请走「状态变更终审决策」。"
            />

            <div class="detail-section" style="margin-top: 24px">
              <h4>历史审批与追溯日志</h4>
              <ReviewHistoryLogTable :supplier-id="currentSupplier.id" :supplier-name="currentSupplier.name" />
            </div>
          </template>

          <!-- 3. 状态变更终审决策（§2.7 跨供应商待办队列 + §2.6 终审；两步流程第二步，不提供直改状态旁路） -->
          <template v-else-if="currentView === 'status-decision-list'">
            <div class="page-header">
              <span class="page-title">供应商停用 / 淘汰终审决策</span>
              <div class="header-actions">
                <!-- 计数取 §2.7 PageResult.total（全量真值），不拿本页行数凑 -->
                <el-tag type="warning" size="small">{{ decisionSummaryLabel }}</el-tag>
                <el-button type="primary" plain :loading="decisionLoading" @click="loadDecisionRows">刷新</el-button>
              </div>
            </div>

            <el-alert
              type="warning"
              show-icon
              :closable="false"
              style="margin-bottom: 16px"
              title="本页只处理业务员已提交的停用/恢复/淘汰申请（§2.4 建单 → §2.6 终审两步流程）。批准将联动供应商状态，驳回只改申请单；页面不提供任何直接改供应商状态的按钮。"
            />

            <el-form :inline="true" class="filter-form">
              <el-form-item label="申请单状态">
                <!-- 传码显中文：value=LifecycleRequestStatus 码（§2.7 status 入参），label=字典中文；清空=不过滤 -->
                <el-select
                  v-model="decisionQuery.status"
                  placeholder="全部状态"
                  clearable
                  style="width: 150px"
                  @change="handleDecisionSearch"
                >
                  <el-option
                    v-for="opt in LIFECYCLE_REQUEST_STATUS_OPTIONS"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="申请类型">
                <!-- 传码显中文：value=SUSPEND/RESUME/ELIMINATE（§2.7 type 入参，后端 TYPE_REGEX 校验，非法码值 40001） -->
                <el-select
                  v-model="decisionQuery.type"
                  placeholder="全部类型"
                  clearable
                  style="width: 150px"
                  @change="handleDecisionSearch"
                >
                  <el-option
                    v-for="opt in LIFECYCLE_REQUEST_TYPE_OPTIONS"
                    :key="opt.value"
                    :label="opt.label"
                    :value="opt.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleDecisionSearch">查询</el-button>
                <el-button @click="handleDecisionReset">重置</el-button>
              </el-form-item>
            </el-form>

            <el-table v-loading="decisionLoading" :data="decisionRows" border stripe style="width: 100%">
              <el-table-column label="目标供应商" min-width="180" show-overflow-tooltip>
                <template #default="scope">
                  <!-- §2.7 冗余列 supplier_name：null（供应商已注销/脏 FK）显式占位并标注，绝不折空串伪装"供应商存在" -->
                  <span
                    v-if="isSupplierProfileMissing(scope.row)"
                    class="supplier-missing"
                    :title="SUPPLIER_PROFILE_MISSING_LABEL"
                  >{{ NULL_FIELD_PLACEHOLDER }}</span>
                  <span v-else>{{ scope.row.supplier_name }}</span>
                  <!-- SUSPENDED 供应商的在途恢复申请：同类型重复提交会撞 40902，显式标注提示 -->
                  <el-tag v-if="hasPendingResume(scope.row)" type="warning" effect="dark" size="small" style="margin-left: 5px">
                    在途恢复申请
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="供应商当前状态" width="140">
                <template #default="scope">
                  <!-- supplier_status=当前合作状态（与"申请变更目标"是两个概念）；复用供应商域字典，null 走缺失占位 -->
                  <el-tag v-if="scope.row.supplier_status !== null" :type="getStatusTagType(scope.row.supplier_status)">
                    {{ getStatusLabel(scope.row.supplier_status) }}
                  </el-tag>
                  <span v-else class="supplier-missing" :title="SUPPLIER_PROFILE_MISSING_LABEL">
                    {{ NULL_FIELD_PLACEHOLDER }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="申请变更目标" width="180">
                <template #default="scope">
                  <!-- 传码显中文：type 码 → 申请类型中文 + 批准后目标状态（目标态由后端联动，此处仅展示语义） -->
                  {{ getLifecycleTypeLabel(scope.row.type) }}
                  <span class="target-arrow">→</span>
                  {{ getLifecycleTargetStatusLabel(scope.row.type) }}
                </template>
              </el-table-column>
              <el-table-column label="申请人" width="110">
                <template #default="scope">{{ scope.row.applied_by_name || '-' }}</template>
              </el-table-column>
              <el-table-column label="申请时间" width="160">
                <template #default="scope">{{ formatCreatedAt(scope.row.applied_at) }}</template>
              </el-table-column>
              <el-table-column label="申请原因" min-width="180" show-overflow-tooltip>
                <template #default="scope">{{ scope.row.reason }}</template>
              </el-table-column>
              <el-table-column label="申请单状态" width="110">
                <template #default="scope">
                  <!-- 同码值多域：生命周期域中文必须经 entity_type 分流（待终审/已批准/已驳回） -->
                  <el-tag size="small" :type="getStatusTagType(scope.row.status)">
                    {{ getStatusLabel(scope.row.status, ENTITY_TYPE.LIFECYCLE_REQUEST) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="终审留痕（已决只读）" min-width="200">
                <template #default="scope">
                  <div v-if="!canDecide(scope.row)" class="decided-cell">
                    <div>{{ scope.row.decided_by_name || '-' }} · {{ formatCreatedAt(scope.row.decided_at) }}</div>
                    <div class="decided-comment">{{ scope.row.decision_comment || '（决策意见未填写）' }}</div>
                  </div>
                  <span v-else>—</span>
                </template>
              </el-table-column>
              <el-table-column label="终审决策" width="120" fixed="right">
                <template #default="scope">
                  <!-- 已决件（status≠PENDING）不提供决策入口（任务卡 F2.4）；角色维度由路由守卫 + 服务端 40301 兜底 -->
                  <el-button
                    v-if="canDecide(scope.row)"
                    size="small"
                    type="success"
                    @click="openDecisionDialog(scope.row)"
                  >
                    终审
                  </el-button>
                  <el-button v-else size="small" link disabled>已决策</el-button>
                </template>
              </el-table-column>
            </el-table>
            <el-pagination
              style="margin-top: 16px; justify-content: flex-end"
              :current-page="decisionQuery.page"
              :page-size="decisionQuery.page_size"
              :page-sizes="[10, 20, 50, 100]"
              :total="decisionTotal"
              layout="total, sizes, prev, pager, next"
              @current-change="handleDecisionPageChange"
              @size-change="handleDecisionSizeChange"
            />
            <el-empty v-if="!decisionLoading && decisionRows.length === 0" description="暂无符合条件的生命周期申请单" :image-size="60" />
          </template>

          <!-- 4. 绩效评价复核（属模块三，本轮占位） -->
          <template v-else-if="currentView === 'perf-review-list'">
            <div class="page-header">
              <span class="page-title">业务员季度绩效打分复核</span>
            </div>
            <el-empty description="绩效评价复核模块开发中（下一步实现）" />
          </template>

          <!-- 5. 操作与审批日志 -->
          <ReviewLogTable v-else-if="currentView === 'log-list'" />
        </div>
      </el-main>
    </el-container>

    <!-- 非待审核行的资质档案弹窗（§1.8 只读 + NORMAL 供应商 §2.3 审批入口） -->
    <el-dialog v-model="qualificationDialogVisible" title="供应商资质档案" width="900px">
      <div v-if="qualificationSupplier" class="qualification-dialog-body">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="供应商名称">{{ qualificationSupplier.name }}</el-descriptions-item>
          <el-descriptions-item label="当前状态">
            <el-tag size="small" :type="getStatusTagType(qualificationSupplier.status)">
              {{ getStatusLabel(qualificationSupplier.status) }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <QualificationPanel :supplier-id="qualificationSupplier.id" :supplier-status="qualificationSupplier.status" />
      </div>
      <template #footer>
        <el-button @click="qualificationDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- §2.6 终审决策弹窗（version 取 §2.7 待办队列行内现值；成功后父级重拉 §1.4 + §2.7） -->
    <LifecycleDecisionDialog
      v-model="decisionDialogVisible"
      :row="decisionRow"
      @decided="handleDecided"
      @refresh="handleDecisionRefresh"
    />
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #2b2d42;
  color: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.15);
  z-index: 100;
}
.logo {
  font-size: 18px;
  font-weight: bold;
  letter-spacing: 0.5px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #edf2f4;
}
.body-container {
  overflow: hidden;
}
.aside-menu {
  background-color: #1a1b26;
  overflow-y: auto;
}
.aside-menu :deep(.el-menu) {
  border-right: none;
}
.main-content {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
.page-card {
  background: #fff;
  padding: 24px;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  min-height: calc(100vh - 100px);
}
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
.filter-form {
  margin-bottom: 10px;
}
.detail-section {
  margin-bottom: 24px;
}
.detail-section h4 {
  border-left: 4px solid #67c23a;
  padding-left: 8px;
  margin-bottom: 12px;
  color: #303133;
}
.audit-box {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 20px;
  margin-top: 20px;
}
.audit-box-title {
  margin-top: 0;
  color: #e6a23c;
}
.target-arrow {
  margin: 0 4px;
  color: #909399;
}
.decided-cell {
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
}
.decided-comment {
  color: #909399;
}
.qualification-dialog-body {
  max-height: 60vh;
  overflow-y: auto;
}
/* §2.7 冗余供应商列为 null（供应商已注销/脏 FK）时的缺失态：灰体倾斜 + 原生 title 说明，不折空串 */
.supplier-missing {
  color: #909399;
  font-style: italic;
}
</style>
