<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, Files, Star } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import SupplierLogTable from '@/components/business/SupplierLogTable.vue'
import SupplierHistoryLogTable from '@/components/business/SupplierHistoryLogTable.vue'
import QualificationPanel from '@/components/business/QualificationPanel.vue'
import { SUPPLIER_STATUS_CODES } from '@/types/supplier-log'
import type { SupplierStatusCode } from '@/constants/auditDictionary'
import {
  SUPPLIER_STATUS,
  SUPPLIER_TYPE_OPTIONS,
  formatCreatedAt,
  getStatusLabel,
  getStatusTagType,
  getSupplierTypeLabel,
} from '@/constants/auditLogView'
import {
  createSupplierApi,
  deleteSupplierApi,
  getSupplierDetailApi,
  getSupplierListApi,
  updateSupplierApi,
} from '@/api/supplier'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import {
  SUPPLIER_FIELD_BY_MESSAGE,
  SUPPLIER_LOCK_CONFLICT_MSG,
  SUPPLIER_VALIDATION,
} from '@/constants/supplierValidation'
import type {
  SupplierCreateRequest,
  SupplierFormModel,
  SupplierItem,
  SupplierListQuery,
  SupplierUpdateRequest,
} from '@/types/supplier'

/**
 * 业务员控制台（参考 docs/mockups/原型展示-采购员v1.1_待评审20260929.html）。
 * 红线：本页仅 STAFF 可访问，由路由守卫 meta.roles=['STAFF']（/business/supplier-list）强制拦截；
 * 日志视角为业务员维度（entity_type=SUPPLIER）。
 * 20261014：供应商档案列表对接真实后端（契约 §1.1–§1.5，B1 交付），原型演示数据 SUPPLIER_DEMO_LIST 已删除。
 * 20261014（F2）：详情视图挂 QualificationPanel（契约 §1.6–§1.8 资质区），既有列表/弹窗逻辑零改动。
 *
 * 按钮显隐"双维度判断"（体验层，非安全边界）：
 * - 维度一 角色：新增/编辑/删除按钮仅 STAFF 渲染（userStore.role）；
 * - 维度二 状态：编辑仅 DRAFT/RETURNED 行启用、删除仅 DRAFT 行启用（契约 §1.2/§1.5）。
 * 前端隐藏/禁用只是体验优化，真正的权限拦截在后端：角色不符 40301、状态/归属不符 40302，
 * 前端不禁用也不可能有越权写入，任何按钮态均以服务端校验为最终事实源。
 *
 * 派生字段归二期（用户裁决 20261014）：business_license_url / latest_performance_grade / risk_warning
 * 后端本批恒回 null，展示层 null 容错（"—" 或不渲染预警标签），不得报错；二期回填后自动生效。
 */
type MenuKey = 'supplier-list' | 'perf-list' | 'log-list'
type ViewKey = MenuKey | 'supplier-detail'

const router = useRouter()
const userStore = useUserStore()

const activeMenu = ref<MenuKey>('supplier-list')
const currentView = ref<ViewKey>('supplier-list')
const currentSupplier = ref<SupplierItem | null>(null)

/** 按钮级角色维度：仅 STAFF 可见写操作入口（ADMIN/AUDITOR 理论上已被路由守卫挡在门外，此处双保险） */
const isStaff = computed(() => userStore.role === 'STAFF')

/* ---------------------------------------------------------------- 列表查询（契约 §1.3） */

const loading = ref(false)
const supplierList = ref<SupplierItem[]>([])
const total = ref(0)
const query = reactive({
  keyword: '',
  status: '' as SupplierStatusCode | '',
  page: 1,
  page_size: 20,
})
const statusOptions = SUPPLIER_STATUS_CODES

async function fetchList() {
  loading.value = true
  try {
    const params: SupplierListQuery = { page: query.page, page_size: query.page_size }
    if (query.keyword.trim()) params.keyword = query.keyword.trim()
    if (query.status) params.status = query.status
    const res = await getSupplierListApi(params)
    supplierList.value = res.data?.list ?? []
    total.value = res.data?.total ?? 0
  } catch (err) {
    showRequestError(err)
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  query.page = 1
  void fetchList()
}

function handleReset() {
  query.keyword = ''
  query.status = ''
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

/** 通用错误提示：业务 code 的 message 一律优先透传后端（request.ts 同口径）。 */
function showRequestError(err: unknown) {
  if (err instanceof ApiError) {
    ElMessage.error(err.message)
  } else {
    ElMessage.error('网络异常，请稍后重试')
  }
}

/* ---------------------------------------------------------------- 状态×角色 双维度门控 */

/** 状态维度：仅 DRAFT/RETURNED 可编辑（契约 §1.2，服务端 40302 兜底）。 */
function isEditable(row: SupplierItem): boolean {
  return row.status === SUPPLIER_STATUS.DRAFT || row.status === SUPPLIER_STATUS.RETURNED
}

/** 状态维度：仅 DRAFT 可删除（契约 §1.5，服务端 40302 兜底）。 */
function isDeletable(row: SupplierItem): boolean {
  return row.status === SUPPLIER_STATUS.DRAFT
}

/* ---------------------------------------------------------------- 新增/编辑弹窗（§1.1/§1.2） */

const EMPTY_FORM: SupplierFormModel = {
  name: '',
  tax_no: '',
  type: '',
  contact_name: '',
  contact_phone: '',
  contact_email: '',
  address: '',
  remark: '',
  effective_date: '',
  expiry_date: '',
}

const dialogVisible = ref(false)
const submitting = ref(false)
/** 编辑行的服务端快照：version 与 Partial diff 的比对基线（新增态为 null） */
const editingSupplier = ref<SupplierItem | null>(null)
const formModel = reactive<SupplierFormModel>({ ...EMPTY_FORM })
const formRef = ref<FormInstance>()

/**
 * 服务端业务错误逐字段展示（本 EP 版本 FormInstance 类型未暴露 setFields，改用 el-form-item :error 绑定）：
 * 40001 按后端固定文案反查字段、40902 定位 tax_no（用户裁决 20261014）。
 */
const serverErrors = reactive<Partial<Record<keyof SupplierFormModel, string>>>({})

function setServerError(field: keyof SupplierFormModel, message: string) {
  clearServerErrors()
  serverErrors[field] = message
}

function clearServerErrors() {
  for (const key of Object.keys(serverErrors) as (keyof SupplierFormModel)[]) {
    delete serverErrors[key]
  }
}

/** 表单规则与后端 SupplierValidationPatterns 逐字同源（constants/supplierValidation.ts），失败文案即契约固定文案 */
const formRules: FormRules<SupplierFormModel> = {
  name: [
    { required: true, message: SUPPLIER_VALIDATION.NAME_MSG, trigger: 'blur' },
    { pattern: SUPPLIER_VALIDATION.NAME_REGEX, message: SUPPLIER_VALIDATION.NAME_MSG, trigger: 'blur' },
  ],
  tax_no: [
    { required: true, message: SUPPLIER_VALIDATION.TAX_NO_MSG, trigger: 'blur' },
    { pattern: SUPPLIER_VALIDATION.TAX_NO_REGEX, message: SUPPLIER_VALIDATION.TAX_NO_MSG, trigger: 'blur' },
  ],
  type: [{ required: true, message: SUPPLIER_VALIDATION.TYPE_MSG, trigger: 'change' }],
  contact_name: [
    { required: true, message: SUPPLIER_VALIDATION.CONTACT_NAME_MSG, trigger: 'blur' },
    { pattern: SUPPLIER_VALIDATION.NAME_REGEX, message: SUPPLIER_VALIDATION.CONTACT_NAME_MSG, trigger: 'blur' },
  ],
  contact_phone: [
    { required: true, message: SUPPLIER_VALIDATION.CONTACT_PHONE_MSG, trigger: 'blur' },
    { pattern: SUPPLIER_VALIDATION.PHONE_REGEX, message: SUPPLIER_VALIDATION.CONTACT_PHONE_MSG, trigger: 'blur' },
  ],
  // 选填：EMAIL_REGEX 首支 ^$ 放行空串（与后端"空串视为未填写"同源）
  contact_email: [
    { pattern: SUPPLIER_VALIDATION.EMAIL_REGEX, message: SUPPLIER_VALIDATION.CONTACT_EMAIL_MSG, trigger: 'blur' },
  ],
  effective_date: [
    { required: true, message: SUPPLIER_VALIDATION.DATE_MSG, trigger: 'change' },
    { pattern: SUPPLIER_VALIDATION.DATE_REGEX, message: SUPPLIER_VALIDATION.DATE_MSG, trigger: 'change' },
  ],
  expiry_date: [
    { required: true, message: SUPPLIER_VALIDATION.DATE_MSG, trigger: 'change' },
    { pattern: SUPPLIER_VALIDATION.DATE_REGEX, message: SUPPLIER_VALIDATION.DATE_MSG, trigger: 'change' },
    {
      // 前端先验：到期日必须晚于生效日；最终以后端跨字段复核为准（同一 DATE_MSG 文案，契约 §1.1）
      validator: (_rule, value, callback) => {
        if (value && formModel.effective_date && String(value) <= formModel.effective_date) {
          callback(new Error(SUPPLIER_VALIDATION.DATE_MSG))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
}

function resetDialogState() {
  clearServerErrors()
  formRef.value?.clearValidate()
}

function openCreate() {
  editingSupplier.value = null
  Object.assign(formModel, EMPTY_FORM)
  dialogVisible.value = true
  void nextTick(resetDialogState)
}

function openEdit(row: SupplierItem) {
  editingSupplier.value = row
  Object.assign(formModel, {
    name: row.name,
    tax_no: row.tax_no,
    type: row.type,
    contact_name: row.contact_name,
    contact_phone: row.contact_phone,
    contact_email: row.contact_email ?? '',
    address: row.address ?? '',
    remark: row.remark ?? '',
    effective_date: row.effective_date,
    expiry_date: row.expiry_date,
  })
  dialogVisible.value = true
  void nextTick(resetDialogState)
}

function buildCreatePayload(type: SupplierCreateRequest['type']): SupplierCreateRequest {
  const payload: SupplierCreateRequest = {
    name: formModel.name,
    tax_no: formModel.tax_no,
    type,
    contact_name: formModel.contact_name,
    contact_phone: formModel.contact_phone,
    effective_date: formModel.effective_date,
    expiry_date: formModel.expiry_date,
  }
  // 选填字段空串不提交（后端 null=未提交，避免把"没填"与"清空"混为一谈）
  if (formModel.contact_email) payload.contact_email = formModel.contact_email
  if (formModel.address) payload.address = formModel.address
  if (formModel.remark) payload.remark = formModel.remark
  return payload
}

/** Partial 语义（契约 §1.2）：只提交与快照相比改动过的字段 + version；显式清空的选填字段提交空串 */
function buildUpdatePayload(origin: SupplierItem): { payload: SupplierUpdateRequest; changed: number } {
  const payload: SupplierUpdateRequest = { version: origin.version }
  let changed = 0
  if (formModel.name !== origin.name) {
    payload.name = formModel.name
    changed += 1
  }
  if (formModel.tax_no !== origin.tax_no) {
    payload.tax_no = formModel.tax_no
    changed += 1
  }
  const type = formModel.type
  if (type && type !== origin.type) {
    payload.type = type
    changed += 1
  }
  if (formModel.contact_name !== origin.contact_name) {
    payload.contact_name = formModel.contact_name
    changed += 1
  }
  if (formModel.contact_phone !== origin.contact_phone) {
    payload.contact_phone = formModel.contact_phone
    changed += 1
  }
  if (formModel.contact_email !== (origin.contact_email ?? '')) {
    payload.contact_email = formModel.contact_email
    changed += 1
  }
  if (formModel.address !== (origin.address ?? '')) {
    payload.address = formModel.address
    changed += 1
  }
  if (formModel.remark !== (origin.remark ?? '')) {
    payload.remark = formModel.remark
    changed += 1
  }
  if (formModel.effective_date !== origin.effective_date) {
    payload.effective_date = formModel.effective_date
    changed += 1
  }
  if (formModel.expiry_date !== origin.expiry_date) {
    payload.expiry_date = formModel.expiry_date
    changed += 1
  }
  return { payload, changed }
}

async function handleSubmit() {
  const formEl = formRef.value
  if (!formEl) return
  clearServerErrors()
  try {
    await formEl.validate()
  } catch {
    return // 本地校验未过，错误已展示
  }
  const type = formModel.type
  if (!type) {
    setServerError('type', SUPPLIER_VALIDATION.TYPE_MSG)
    return
  }
  const origin = editingSupplier.value
  let payload: SupplierUpdateRequest | null = null
  if (origin) {
    const built = buildUpdatePayload(origin)
    if (built.changed === 0) {
      ElMessage.info('没有需要保存的改动')
      return
    }
    payload = built.payload
  }
  submitting.value = true
  try {
    if (origin && payload) {
      await updateSupplierApi(origin.id, payload)
      ElMessage.success('档案已保存')
    } else {
      await createSupplierApi(buildCreatePayload(type))
      ElMessage.success('建档成功（草稿态）')
    }
    dialogVisible.value = false
    await fetchList()
  } catch (err) {
    handleSubmitError(err)
  } finally {
    submitting.value = false
  }
}

/** 按业务 code 分流（用户拍板 20261014：HTTP 200 + 响应体 code） */
function handleSubmitError(err: unknown) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  if (err.code === ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT) {
    // 40901：契约固定文案 + 重新拉取详情刷新 version（§1.2 错误码矩阵 + 裁决 20261014）
    ElMessage.error(SUPPLIER_LOCK_CONFLICT_MSG)
    void refreshEditingSnapshot()
    return
  }
  if (err.code === ERROR_CODE.DUPLICATE_IN_PROGRESS) {
    // 40902 税号占用：定位到 tax_no 字段（裁决 20261014），文案透传后端
    ElMessage.error(err.message)
    setServerError('tax_no', err.message)
    return
  }
  if (err.code === ERROR_CODE.VALIDATION_FAILED) {
    // 40001：后端取首个字段错误固定文案返回，按文案反查字段逐字段展示（api-spec §2 校验表）
    ElMessage.error(err.message)
    const field = SUPPLIER_FIELD_BY_MESSAGE[err.message]
    if (field) {
      setServerError(field, err.message)
    }
    return
  }
  showRequestError(err)
}

/** 40901 后重拉 §1.4 详情：只刷新快照（新 version/服务端现值），不覆盖用户正在编辑的表单内容 */
async function refreshEditingSnapshot() {
  const origin = editingSupplier.value
  if (!origin) return
  try {
    const res = await getSupplierDetailApi(origin.id)
    if (res.data) {
      editingSupplier.value = res.data
      ElMessage.info('已获取服务端最新版本，请确认后重新提交')
    }
  } catch (err) {
    showRequestError(err)
  }
}

/* ---------------------------------------------------------------- 删除草稿（§1.5） */

async function handleDelete(row: SupplierItem) {
  try {
    await ElMessageBox.confirm(
      `删除将逻辑移除草稿「${row.name}」并释放其税号占位，确认删除？`,
      '删除草稿确认',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  try {
    await deleteSupplierApi(row.id)
    ElMessage.success('草稿已删除')
    if (supplierList.value.length === 1 && query.page > 1) {
      query.page -= 1
    }
    await fetchList()
  } catch (err) {
    showRequestError(err)
  }
}

/* ---------------------------------------------------------------- 详情/日志视图 */

/** 详情/日志：进入供应商全生命周期档案视图（列表行即 SupplierResponse 全 20 字段，无需二次拉取）。 */
function viewDetail(row: SupplierItem) {
  currentSupplier.value = row
  currentView.value = 'supplier-detail'
}

function handleMenuSelect(index: string) {
  activeMenu.value = index as MenuKey
  currentView.value = index as MenuKey
}

function backToList() {
  currentView.value = 'supplier-list'
  activeMenu.value = 'supplier-list'
}

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <el-container class="business-layout">
    <!-- 顶栏 -->
    <el-header height="60px" class="header-bar">
      <div class="logo">SRM 供应商管理系统 | 业务员控制台</div>
      <div class="user-info">
        <el-tag type="success" size="small">业务员 (Staff)</el-tag>
        <span>欢迎您，{{ userStore.userInfo?.real_name ?? 'Yangwei' }}（{{ userStore.userInfo?.username }}）</span>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 侧边栏菜单 -->
      <el-aside width="220px" class="aside-menu">
        <el-menu
          :default-active="activeMenu"
          background-color="#1f2d3d"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
          @select="handleMenuSelect"
        >
          <el-menu-item index="supplier-list">
            <el-icon><Files /></el-icon>
            <span>供应商档案管理</span>
          </el-menu-item>
          <el-menu-item index="perf-list">
            <el-icon><Star /></el-icon>
            <span>量化绩效评价录入</span>
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
          <!-- 1. 供应商档案列表（真实接口 §1.3） -->
          <template v-if="currentView === 'supplier-list'">
            <div class="page-header">
              <span class="page-title">供应商档案与生命周期管理</span>
              <!-- 角色维度：新增仅 STAFF（后端 STAFF 专属 40301 兜底，前端隐藏只是体验层） -->
              <el-button v-if="isStaff" type="primary" @click="openCreate">+ 新增供应商档案</el-button>
            </div>

            <el-form :inline="true" :model="query" style="margin-bottom: 10px">
              <el-form-item label="关键词">
                <el-input v-model="query.keyword" placeholder="名称或税号模糊" clearable @keyup.enter="handleSearch" />
              </el-form-item>
              <el-form-item label="合作状态">
                <!-- 传码显中文：value=SupplierStatus 码，label=字典中文 -->
                <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 160px">
                  <el-option
                    v-for="statusCode in statusOptions"
                    :key="statusCode"
                    :label="getStatusLabel(statusCode)"
                    :value="statusCode"
                  />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">查询</el-button>
                <el-button @click="handleReset">重置</el-button>
              </el-form-item>
            </el-form>

            <el-table v-loading="loading" :data="supplierList" border style="width: 100%">
              <el-table-column prop="name" label="供应商名称" min-width="180" show-overflow-tooltip />
              <el-table-column prop="tax_no" label="统一社会信用代码/税号" width="190" />
              <el-table-column label="供应商类型" width="140">
                <template #default="scope">{{ getSupplierTypeLabel(scope.row.type) }}</template>
              </el-table-column>
              <el-table-column prop="contact_name" label="联系人" width="100" />
              <el-table-column prop="contact_phone" label="联系电话" width="130" />
              <el-table-column label="当前状态与风险标记" width="190">
                <template #default="scope">
                  <el-tag :type="getStatusTagType(scope.row.status)">{{ getStatusLabel(scope.row.status) }}</el-tag>
                  <!-- 派生字段归二期（裁决 20261014）：本批恒 null，仅显式 true 才渲染预警标签 -->
                  <el-tag
                    v-if="scope.row.risk_warning === true"
                    type="danger"
                    effect="dark"
                    style="margin-left: 5px"
                  >
                    风险预警
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="最近评级" width="100">
                <template #default="scope">{{ scope.row.latest_performance_grade || '—' }}</template>
              </el-table-column>
              <el-table-column label="业务操作" width="230" fixed="right">
                <template #default="scope">
                  <el-button size="small" type="primary" link @click="viewDetail(scope.row)">详情/日志</el-button>
                  <!-- 双维度门控：角色(v-if)×状态(:disabled)；服务端 40301/40302 才是安全边界 -->
                  <el-button
                    v-if="isStaff"
                    size="small"
                    type="primary"
                    link
                    :disabled="!isEditable(scope.row)"
                    @click="openEdit(scope.row)"
                  >
                    编辑
                  </el-button>
                  <el-button
                    v-if="isStaff"
                    size="small"
                    type="danger"
                    link
                    :disabled="!isDeletable(scope.row)"
                    @click="handleDelete(scope.row)"
                  >
                    删除草稿
                  </el-button>
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
          </template>

          <!-- 2. 供应商档案详情与特定档案追溯日志 -->
          <template v-else-if="currentView === 'supplier-detail' && currentSupplier">
            <div class="page-header">
              <span class="page-title">供应商全生命周期档案 - {{ currentSupplier.name }}</span>
              <el-button @click="backToList">返回列表</el-button>
            </div>

            <div class="detail-section">
              <h4>基础与资质信息</h4>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="供应商名称">{{ currentSupplier.name }}</el-descriptions-item>
                <el-descriptions-item label="统一社会信用代码/税号">{{ currentSupplier.tax_no }}</el-descriptions-item>
                <el-descriptions-item label="供应商类型">{{ getSupplierTypeLabel(currentSupplier.type) }}</el-descriptions-item>
                <el-descriptions-item label="当前状态与标记">
                  <el-tag :type="getStatusTagType(currentSupplier.status)">{{ getStatusLabel(currentSupplier.status) }}</el-tag>
                  <!-- 派生字段归二期：null 不渲染（"未计算"≠"无风险"，不得误标） -->
                  <el-tag
                    v-if="currentSupplier.risk_warning === true"
                    type="danger"
                    effect="dark"
                    style="margin-left: 5px"
                  >
                    风险预警
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="联系人">{{ currentSupplier.contact_name }} ({{ currentSupplier.contact_phone }})</el-descriptions-item>
                <el-descriptions-item label="联系邮箱">{{ currentSupplier.contact_email || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="合作周期">{{ currentSupplier.effective_date }} ~ {{ currentSupplier.expiry_date }}</el-descriptions-item>
                <el-descriptions-item label="最近归档评级">{{ currentSupplier.latest_performance_grade || '—' }}</el-descriptions-item>
                <el-descriptions-item label="建档人">{{ currentSupplier.created_by_name }}</el-descriptions-item>
                <el-descriptions-item label="创建时间">{{ formatCreatedAt(currentSupplier.created_at) }}</el-descriptions-item>
                <el-descriptions-item label="企业地址" :span="2">{{ currentSupplier.address || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="备注" :span="2">{{ currentSupplier.remark || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="营业执照文件" :span="2">
                  <!-- 派生字段归二期（裁决 20261014）：本批恒 null，null 时显示"—"占位不报错 -->
                  <el-link v-if="currentSupplier.business_license_url" type="primary">
                    {{ currentSupplier.business_license_url }}
                  </el-link>
                  <span v-else>—</span>
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <div class="detail-section">
              <h4>资质档案</h4>
              <!-- §1.6/§1.7/§1.8 资质区：两步上传+列表；supplier-status 快照驱动入口门控（后端 40301/40302 才是安全边界） -->
              <QualificationPanel :supplier-id="currentSupplier.id" :supplier-status="currentSupplier.status" />
            </div>

            <div class="detail-section">
              <h4>当前档案追溯日志历史</h4>
              <SupplierHistoryLogTable
                :supplier-id="currentSupplier.id"
                :supplier-name="currentSupplier.name"
              />
            </div>
          </template>

          <!-- 3. 量化绩效评价录入（占位，下一步实现） -->
          <template v-else-if="currentView === 'perf-list'">
            <div class="page-header">
              <span class="page-title">供应商日常绩效评价记录 (历史留存可追溯)</span>
            </div>
            <el-alert
              title="防篡改铁律：业务员仅录入客观履约事实明细，得分与评级由后端算分引擎硬编码计算，前端禁止提交或覆盖分数/评级。"
              type="warning"
              show-icon
              :closable="false"
              style="margin-bottom: 20px"
            />
            <el-empty description="量化绩效评价录入模块开发中（下一步实现）" />
          </template>

          <!-- 4. 操作与审批日志（本步实现） -->
          <SupplierLogTable v-else-if="currentView === 'log-list'" />
        </div>
      </el-main>
    </el-container>

    <!-- 新增/编辑供应商档案弹窗（§1.1/§1.2） -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingSupplier ? '编辑供应商档案' : '新增供应商档案（草稿）'"
      width="640px"
    >
      <el-alert
        v-if="editingSupplier"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        title="仅草稿/待修改状态可编辑（服务端按状态与归属校验，最终为准）；保存时只提交改动字段并携带乐观锁版本。"
      />
      <el-form ref="formRef" :model="formModel" :rules="formRules" label-width="150px">
        <el-form-item label="供应商名称" prop="name" :error="serverErrors.name">
          <el-input v-model="formModel.name" placeholder="必填，2~64 字符" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="tax_no" :error="serverErrors.tax_no">
          <el-input v-model="formModel.tax_no" placeholder="必填，18 位统一社会信用代码" maxlength="18" />
        </el-form-item>
        <el-form-item label="供应商类型" prop="type" :error="serverErrors.type">
          <!-- 传码显中文：提交 SupplierType 码，展示字典中文 -->
          <el-select v-model="formModel.type" placeholder="请选择（仅认三码）" style="width: 100%">
            <el-option v-for="opt in SUPPLIER_TYPE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人" prop="contact_name" :error="serverErrors.contact_name">
          <el-input v-model="formModel.contact_name" placeholder="必填，2~64 字符" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="联系电话" prop="contact_phone" :error="serverErrors.contact_phone">
          <el-input v-model="formModel.contact_phone" placeholder="必填，11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="联系邮箱" prop="contact_email" :error="serverErrors.contact_email">
          <el-input v-model="formModel.contact_email" placeholder="选填，填写则需合法邮箱" />
        </el-form-item>
        <el-form-item label="企业地址" prop="address">
          <el-input v-model="formModel.address" placeholder="选填" />
        </el-form-item>
        <el-form-item label="合作生效日期" prop="effective_date" :error="serverErrors.effective_date">
          <el-date-picker
            v-model="formModel.effective_date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="必填 yyyy-MM-dd"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="合作到期日期" prop="expiry_date" :error="serverErrors.expiry_date">
          <el-date-picker
            v-model="formModel.expiry_date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="须晚于生效日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="formModel.remark" type="textarea" :rows="2" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.business-layout {
  height: 100vh;
}
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #1f2d3d;
  color: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.1);
  z-index: 100;
}
.logo {
  font-size: 18px;
  font-weight: bold;
  letter-spacing: 1px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #e0e6ed;
}
.body-container {
  overflow: hidden;
}
.aside-menu {
  background-color: #1f2d3d;
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
.detail-section {
  margin-bottom: 24px;
}
.detail-section h4 {
  margin: 0 0 12px;
  color: #303133;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}
</style>
