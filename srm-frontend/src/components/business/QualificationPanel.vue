<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  ElMessage,
  type FormInstance,
  type FormRules,
  type UploadInstance,
  type UploadRequestOptions,
  type UploadRawFile,
} from 'element-plus'
import { useUserStore } from '@/stores/user'
import { createQualificationApi, getQualificationListApi, uploadFileApi } from '@/api/qualification'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import {
  ENTITY_TYPE,
  QUALIFICATION_DOC_TYPE,
  SUPPLIER_STATUS,
  formatCreatedAt,
  getQualificationDocTypeLabel,
  getStatusLabel,
  getStatusTagType,
} from '@/constants/auditLogView'
import type { SupplierStatusCode } from '@/constants/auditDictionary'
import {
  FILE_UPLOAD_ACCEPT,
  FILE_UPLOAD_ALLOWED_EXTENSIONS,
  FILE_UPLOAD_MAX_SIZE_BYTES,
  FILE_UPLOAD_MESSAGE,
  QUALIFICATION_FIELD_BY_MESSAGE,
  QUALIFICATION_VALIDATION,
  type QualificationErrorField,
} from '@/constants/qualificationValidation'
import type {
  QualificationCreateRequest,
  QualificationItem,
} from '@/types/qualification'

/**
 * 供应商资质区（契约 §1.6 文件上传 → §1.7 上传资质 → §1.8 资质列表）。
 * 嵌入业务员控制台「供应商全生命周期档案」详情页，挂在基础信息与追溯日志之间。
 *
 * 两步上传编排：el-upload 经自定义 http-request 先调 §1.6 换 file_url（上传中/失败态由
 * el-upload 列表状态 + 回调呈现，成功回显后端 file_name），表单提交时把该 file_url 随
 * §1.7 QualificationCreateRequest 一并提交；§1.7 成功后刷新 §1.8 列表，
 * 新行初始 status=PENDING 经字典显「资质待审」类标签（裁决 20261012：上传不等于生效）。
 *
 * 证照类型隐藏（任务卡 F4，用户拍板 20261015）：需求文档证照类型仅营业执照一种，
 * 弹窗不再渲染类型选择项，提交报文固定 doc_type=BUSINESS_LICENSE——§1.7 契约报文形态不变
 * （doc_type 仍必填，码值权威源 auditDictionary.QUALIFICATION_DOC_TYPE，后端
 * QualificationValidationPatterns.DOC_TYPE_REGEX 两码仍会校验）。后端 B4"同类型在途顶替"
 * 已落地：重复提交待审营业执照顶替旧待审行，前端零新增逻辑，提交成功后 loadList() 刷新
 * 自然只显一条待审。§1.8 列表"证照类型"展示列与 getQualificationDocTypeLabel 字典保留。
 *
 * 按钮显隐"双维度判断"（体验层，非安全边界）：
 * - 维度一 角色：上传资质入口仅 STAFF 渲染（§1.7 STAFF 专属，后端 40301 兜底）；
 * - 维度二 供应商状态：仅 status ∈ {DRAFT, RETURNED, NORMAL} 启用
 *   （§1.7 允许集，裁决 20261011 解除"准入后资质无法维护"死锁；另"仅本人档案"归属门槛
 *   前端无 ID 比对语义依据时不预判，由服务端 requireSelf 把关）。
 * 真正的权限拦截在后端：状态/归属不符 40302、供应商不存在 40401，前端 disabled+tooltip
 * 只是提前禁用，任何按钮态均以服务端校验为最终事实源。列表本身三角色可读（§1.8 ALL），
 * AUDITOR/ADMIN 视角只读展示（本批无审批操作，§2.3 审批属后续批次）。
 *
 * §1.8 可见性组合口径（裁决 20261014，后端 ServiceImpl 已实现）：本人 STAFF 见 APPROVED+PENDING+
 * REJECTED；非本人 STAFF 仅 APPROVED+PENDING；AUDITOR/ADMIN 全量。前端不做二次过滤，
 * 后端返回什么展示什么；REJECTED 行展示 review_comment。
 *
 * 错误口径（用户拍板 20261014）：HTTP 200 + 响应体业务 code，ApiError 按 code 分流，
 * message 直显后端契约/固定文案（校验文案唯一源见 constants/qualificationValidation.ts）。
 */
const props = defineProps<{
  supplierId: number
  /** 当前供应商状态（§1.7 上传门槛的状态维度；取自详情行 SupplierItem.status 快照） */
  supplierStatus: SupplierStatusCode
}>()

const userStore = useUserStore()

/* ---------------------------------------------------------------- 入口门控（角色×状态） */

/** §1.7 供应商状态允许集：DRAFT/RETURNED/NORMAL（后端 QualificationServiceImpl.isUploadable 同集合） */
const UPLOAD_ALLOWED_SUPPLIER_STATUS: SupplierStatusCode[] = [
  SUPPLIER_STATUS.DRAFT,
  SUPPLIER_STATUS.RETURNED,
  SUPPLIER_STATUS.NORMAL,
]

const isStaff = computed(() => userStore.role === 'STAFF')
const statusAllowed = computed(() => UPLOAD_ALLOWED_SUPPLIER_STATUS.includes(props.supplierStatus))
const canUpload = computed(() => isStaff.value && statusAllowed.value)

const uploadGateTip = computed(() => {
  if (!isStaff.value) return '上传资质为业务员（STAFF）专属操作，当前角色只读展示（服务端 40301 兜底）'
  if (!statusAllowed.value) {
    return `供应商当前状态（${getStatusLabel(props.supplierStatus)}）不允许上传资质，仅 草稿/待修改/正常合作 可上传（以服务端 40302 校验为准）`
  }
  return ''
})

/* ---------------------------------------------------------------- §1.8 列表 */

const loading = ref(false)
const items = ref<QualificationItem[]>([])

function showRequestError(err: unknown) {
  if (err instanceof ApiError) {
    ElMessage.error(err.message)
  } else {
    ElMessage.error('网络异常，请稍后重试')
  }
}

async function loadList() {
  loading.value = true
  try {
    const res = await getQualificationListApi(props.supplierId)
    // 后端返回什么展示什么：可见性组合由 Service 行级裁定，前端零二次过滤
    items.value = res.data ?? []
  } catch (err) {
    items.value = []
    showRequestError(err)
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
// 详情页复用本组件切换供应商时重新加载
watch(() => props.supplierId, loadList)

/** file_url 展示名：取 URL 末段（§1.6 落盘名为 UUID，列表无 file_name 出参；解码失败则原样回显） */
function fileDisplayName(fileUrl: string): string {
  const tail = fileUrl.split('/').pop() ?? fileUrl
  try {
    return decodeURIComponent(tail)
  } catch {
    return tail
  }
}

/** 新窗口打开证照文件（后端 /uploads/** 静态映射可访问，MVP 本地磁盘存储；dev 代理见 vite.config.ts） */
function openFileUrl(fileUrl: string) {
  window.open(fileUrl, '_blank', 'noopener')
}

/* ---------------------------------------------------------------- §1.6+§1.7 上传弹窗 */

/**
 * 弹窗表单 UI 模型（非契约对象）：file_url 不在表单里手填，只能经 §1.6 上传成功后注入；
 * doc_type 不入表单（F4 隐藏类型选择，提交时由 buildCreatePayload 固定 BUSINESS_LICENSE）。
 */
interface QualificationUploadForm {
  effective_date: string
  expiry_date: string
}

const EMPTY_FORM: QualificationUploadForm = {
  effective_date: '',
  expiry_date: '',
}

const dialogVisible = ref(false)
const submitting = ref(false)
const uploading = ref(false)
const formModel = reactive<QualificationUploadForm>({ ...EMPTY_FORM })
const formRef = ref<FormInstance>()
const uploadRef = ref<UploadInstance>()

/** §1.6 成功产物：提交 §1.7 时的 file_url 唯一来源（file_name 仅回显） */
const uploadedFileUrl = ref('')
const uploadedFileName = ref('')

/**
 * 服务端/本地业务错误逐字段展示（同 SupplierListView 惯例：el-form-item :error 绑定）。
 * QualificationErrorField 仍含 'doc_type'（constants/qualificationValidation.ts 为后端 40001
 * 反查唯一映射链，不可断）：后端若回 DOC_TYPE_MSG，ElMessage 直显文案兜底，逐字段挂载因
 * 表单项已移除而无渲染位，信息不丢失。
 */
const serverErrors = reactive<Partial<Record<QualificationErrorField, string>>>({})

function setServerError(field: QualificationErrorField, message: string) {
  clearServerErrors()
  serverErrors[field] = message
}

function clearServerErrors() {
  for (const key of Object.keys(serverErrors) as QualificationErrorField[]) {
    delete serverErrors[key]
  }
}

/** 校验规则与后端 QualificationValidationPatterns 逐字同源（constants/qualificationValidation.ts） */
const formRules: FormRules<QualificationUploadForm> = {
  effective_date: [
    { required: true, message: QUALIFICATION_VALIDATION.DATE_MSG, trigger: 'change' },
    { pattern: QUALIFICATION_VALIDATION.DATE_REGEX, message: QUALIFICATION_VALIDATION.DATE_MSG, trigger: 'change' },
  ],
  expiry_date: [
    { required: true, message: QUALIFICATION_VALIDATION.DATE_MSG, trigger: 'change' },
    { pattern: QUALIFICATION_VALIDATION.DATE_REGEX, message: QUALIFICATION_VALIDATION.DATE_MSG, trigger: 'change' },
    {
      // 前端先验：到期日必须晚于生效日（契约 §1.7"晚于"；后端 @AssertTrue 同规则复核，同一 DATE_MSG）
      validator: (_rule, value, callback) => {
        if (value && formModel.effective_date && String(value) <= formModel.effective_date) {
          callback(new Error(QUALIFICATION_VALIDATION.DATE_MSG))
          return
        }
        callback()
      },
      trigger: 'change',
    },
  ],
}

function openUploadDialog() {
  Object.assign(formModel, EMPTY_FORM)
  uploadedFileUrl.value = ''
  uploadedFileName.value = ''
  clearServerErrors()
  uploadRef.value?.clearFiles()
  dialogVisible.value = true
}

function closeUploadDialog() {
  dialogVisible.value = false
  uploading.value = false
  submitting.value = false
}

/**
 * 前端先验（§1.6 类型/大小，文案与后端 FileUploadConstraints 同源；超限恰好 10MB 合法）。
 * 最终校验以后端为准：容器层 multipart 超限由 GlobalExceptionHandler 兜底同文案。
 */
function beforeFileUpload(file: UploadRawFile): boolean {
  const dot = file.name.lastIndexOf('.')
  const ext = dot >= 0 ? file.name.slice(dot + 1).toLowerCase() : ''
  if (!FILE_UPLOAD_ALLOWED_EXTENSIONS.includes(ext)) {
    ElMessage.error(FILE_UPLOAD_MESSAGE.TYPE_NOT_ALLOWED)
    return false
  }
  if (file.size > FILE_UPLOAD_MAX_SIZE_BYTES) {
    ElMessage.error(FILE_UPLOAD_MESSAGE.SIZE_EXCEEDED)
    return false
  }
  return true
}

/**
 * 自定义上传：走统一 request 实例调 §1.6（携带 Bearer token 与 ApiResponse 解包，
 * 非 0 code 由拦截器抛 ApiError → el-upload 置 failed 态并触发 uploadError 回调）。
 */
function handleFileUpload(options: UploadRequestOptions): Promise<unknown> {
  uploading.value = true
  uploadedFileUrl.value = ''
  uploadedFileName.value = ''
  return uploadFileApi(options.file)
    .then((res) => {
      if (res.data) {
        uploadedFileUrl.value = res.data.file_url
        // 上传成功回显后端 file_name（原始文件名；落盘名是 UUID，回显仅体验层）
        uploadedFileName.value = res.data.file_name
      }
      return res.data
    })
    // FE-F3 修复：成功 / reject（拦截器抛 ApiError 走 on-error）统一在此复位 uploading，
    // 否则上传按钮永久 loading、提交送审按钮永久 disabled；closeUploadDialog 的复位保留作兜底
    .finally(() => {
      uploading.value = false
    })
}

// EP on-error 实参为 UploadAjaxError extends Error（element-plus/es/components/upload/src/ajax.d.ts，根入口未导出该类型名，
// TS2614 实测）；形参取 supertype Error 满足逆变即可，err.message 即拦截器抛出的 ApiError.message（后端文案直显）
function handleUploadError(err: Error) {
  uploadedFileUrl.value = ''
  uploadedFileName.value = ''
  ElMessage.error(err.message || FILE_UPLOAD_MESSAGE.TYPE_NOT_ALLOWED)
}

function handleFileRemove() {
  // 文件被移除后 §1.6 产物作废（file_url 不可复用旧件提交，防止"显示没了、提交还带着"的错位）
  uploadedFileUrl.value = ''
  uploadedFileName.value = ''
}

/**
 * §1.7 提交报文（4 字段契约形态不变）。doc_type 固定 BUSINESS_LICENSE（F4，用户拍板 20261015：
 * 需求证照类型仅营业执照一种，前端隐藏选择项）；码值取字典权威源
 * auditDictionary.QUALIFICATION_DOC_TYPE（后端 QualificationValidationPatterns.DOC_TYPE_REGEX
 * 两码之一），不在组件内硬编码字符串，QualificationCreateRequest 类型定义不动。
 */
function buildCreatePayload(): QualificationCreateRequest {
  return {
    doc_type: QUALIFICATION_DOC_TYPE.BUSINESS_LICENSE,
    file_url: uploadedFileUrl.value,
    effective_date: formModel.effective_date,
    expiry_date: formModel.expiry_date,
  }
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
  if (!uploadedFileUrl.value) {
    // 与后端 FileUploadConstraints.MESSAGE_FILE_MISSING 同文案（file part 缺失在后端即 40001）
    setServerError('file_url', FILE_UPLOAD_MESSAGE.MISSING)
    ElMessage.error(FILE_UPLOAD_MESSAGE.MISSING)
    return
  }
  submitting.value = true
  try {
    await createQualificationApi(props.supplierId, buildCreatePayload())
    // 新行状态中文不在此硬编码：§1.8 刷新后由列表经字典 getStatusLabel(status, SUPPLIER_QUALIFICATION) 渲染
    ElMessage.success('资质已提交送审')
    closeUploadDialog()
    await loadList()
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
  if (err.code === ERROR_CODE.VALIDATION_FAILED) {
    // 40001：后端取首个字段错误固定文案返回，按文案反查字段逐字段展示（同 SupplierListView 范式）
    ElMessage.error(err.message)
    const field = QUALIFICATION_FIELD_BY_MESSAGE[err.message]
    if (field) {
      setServerError(field, err.message)
    }
    return
  }
  // 40302（状态/归属不允许）、40401（供应商不存在）等：message 直显后端契约文案
  showRequestError(err)
}
</script>

<template>
  <div>
    <div class="panel-toolbar">
      <span class="panel-hint">先传文件（§1.6）换 file_url，随资质表单提交（§1.7）送审；新记录初始为待审状态，生效需审核批复。</span>
      <!-- 双维度门控：角色(v-if)×状态(:disabled)；后端 40301/40302 才是安全边界（前端禁用仅体验层） -->
      <el-tooltip v-if="isStaff" :content="uploadGateTip" :disabled="canUpload" placement="top">
        <span>
          <el-button type="primary" :disabled="!canUpload" @click="openUploadDialog">+ 上传资质</el-button>
        </span>
      </el-tooltip>
      <el-tag v-else type="info" size="small">当前角色为只读展示（上传资质限业务员）</el-tag>
    </div>

    <!-- §1.8 资质列表：后端行级可见性裁定，前端不做二次过滤 -->
    <el-table v-loading="loading" :data="items" border stripe style="width: 100%">
      <el-table-column label="证照类型" width="120">
        <template #default="scope">{{ getQualificationDocTypeLabel(scope.row.doc_type) }}</template>
      </el-table-column>
      <el-table-column label="证照文件" min-width="200" show-overflow-tooltip>
        <template #default="scope">
          <!-- /uploads/** 静态可访问，新窗口打开 -->
          <el-link type="primary" @click="openFileUrl(scope.row.file_url)">
            {{ fileDisplayName(scope.row.file_url) }}
          </el-link>
        </template>
      </el-table-column>
      <el-table-column label="有效期起" width="110">
        <template #default="scope">{{ scope.row.effective_date }}</template>
      </el-table-column>
      <el-table-column label="有效期止" width="150">
        <template #default="scope">
          {{ scope.row.expiry_date }}
          <!-- expired 为后端已实现的同表派生位（expiry_date < 今天），true 红标 -->
          <el-tag v-if="scope.row.expired === true" type="danger" effect="dark" size="small" style="margin-left: 5px">
            已过期
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核状态" width="110">
        <template #default="scope">
          <!-- 传码显中文：资质域口径经 entity_type 分流（资质待审/已生效/已驳回，裁决 20261012） -->
          <el-tag size="small" :type="getStatusTagType(scope.row.status)">
            {{ getStatusLabel(scope.row.status, ENTITY_TYPE.SUPPLIER_QUALIFICATION) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="审核意见" min-width="160" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.review_comment || '-' }}</template>
      </el-table-column>
      <el-table-column label="上传时间" width="160">
        <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
      </el-table-column>
      <!-- version 不出列：§1.8 每行携带的乐观锁版本是 §2.3 审批入参的唯一取值链（类型保留、内部字段不外显） -->
    </el-table>
    <el-empty v-if="!loading && items.length === 0" description="该供应商暂无资质记录" :image-size="60" />

    <!-- §1.6+§1.7 上传资质弹窗（F4：不再提供证照类型选择项，提交固定按营业执照） -->
    <el-dialog v-model="dialogVisible" title="上传资质（提交后进入待审）" width="560px">
      <el-alert
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 16px"
        title="文件先传 §1.6 换 file_url（pdf/jpg/jpeg/png、不超过 10MB），成功后提交资质表单；新记录一律待审，生效由审核批复。证照类型默认按营业执照提交。"
      />
      <el-form ref="formRef" :model="formModel" :rules="formRules" label-width="110px">
        <el-form-item label="证照生效日" prop="effective_date" :error="serverErrors.effective_date">
          <el-date-picker
            v-model="formModel.effective_date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="必填 yyyy-MM-dd"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="证照到期日" prop="expiry_date" :error="serverErrors.expiry_date">
          <el-date-picker
            v-model="formModel.expiry_date"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="须晚于生效日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="证照文件" prop="file_url" :error="serverErrors.file_url">
          <el-upload
            ref="uploadRef"
            :accept="FILE_UPLOAD_ACCEPT"
            :before-upload="beforeFileUpload"
            :http-request="handleFileUpload"
            :limit="1"
            :on-error="handleUploadError"
            :on-remove="handleFileRemove"
          >
            <el-button type="primary" plain :loading="uploading">选择文件并上传</el-button>
            <template #tip>
              <div class="upload-tip">仅支持 pdf / jpg / jpeg / png，单文件不超过 10MB（超限/类型不符将被后端 40001 拒绝）。</div>
            </template>
          </el-upload>
          <!-- 上传成功回显 file_name；file_url 已就绪并在提交时随带 -->
          <div v-if="uploadedFileName" class="uploaded-echo">已挂载资质文件: {{ uploadedFileName }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeUploadDialog">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="uploading" @click="handleSubmit">提交送审</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}
.panel-hint {
  font-size: 13px;
  color: #909399;
}
.upload-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.uploaded-echo {
  margin-top: 5px;
  font-size: 13px;
  color: #67c23a;
}
</style>
