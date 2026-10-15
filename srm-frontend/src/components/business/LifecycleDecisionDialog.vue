<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { decideLifecycleRequestApi } from '@/api/lifecycle'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { SUPPLIER_LOCK_CONFLICT_MSG } from '@/constants/supplierValidation'
import {
  DECISION,
  formatCreatedAt,
  getLifecycleDecisionOptions,
  getLifecycleTargetStatusLabel,
  getLifecycleTypeLabel,
  getStatusLabel,
  getStatusTagType,
  type DecisionCode,
} from '@/constants/auditLogView'
import type { LifecycleDecisionRequest, LifecycleRequestResponse, LifecycleRequestRow } from '@/types/lifecycle'

/**
 * 审核员终审决策弹窗（契约 §2.6 POST /lifecycle-requests/{id}/decision，仅 AUDITOR）。
 *
 * 决策语义按申请类型分流展示（任务卡 F2.2：SUSPEND→批准停用、RESUME→批准恢复合作、
 * ELIMINATE→批准淘汰），但**提交报文仍只有 APPROVE/REJECT 两码**（码值权威源
 * auditDictionary.DECISION = 后端 SupplierValidationPatterns.DECISION_REGEX 单一事实源）；
 * 供应商目标状态由后端 Service 硬编码联动，前端无法也不得指定目标态（状态机唯一入口铁律）。
 *
 * 乐观锁取值链（裁决 20261013 候选 A）：version **必须取 §2.5 申请历史列表返回的申请单 version**
 * （LifecycleRequestRow.request.version），不是供应商 version；缺失/不匹配 40901，
 * 前端提示后由父级重拉 §2.5 换新 version（refresh 事件），绝不本地凑一个版本号硬试。
 *
 * 已决件（status≠PENDING）不进本弹窗（父级行入口隐藏 + 只读展示 decided_*）；
 * REJECT 的意见在本域是**选填**（契约 §2.6 字段表 comment 非必填，与 §2.2/§2.3 驳回必填不同，
 * 后端 LifecycleDecisionRequest.comment 也无条件必填校验），故不做必填先验。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 待决策行：供应商档案快照（§1.3）+ 申请单（§2.5，version 唯一取值来源） */
  row: LifecycleRequestRow | null
}>()

const emit = defineEmits<{
  /** 决策成功回传 §2.6 响应（decided_* 已回写）；父级须重拉该供应商 §1.4 取新供应商 version，禁止本地改状态 */
  decided: [request: LifecycleRequestResponse]
  /** 40901/40302/40401 等"服务端已变"情形：父级重拉 §2.5 申请历史（换新 version / 剔除已决行） */
  refresh: [supplierId: number]
}>()

const submitting = ref(false)
/** 表单 UI 模型（非契约对象）：decision 收敛为两码之一，初始默认 APPROVE（与原型单选首项一致） */
const formModel = reactive<{ decision: DecisionCode; comment: string }>({
  decision: DECISION.APPROVE,
  comment: '',
})

const decisionOptions = computed(() => getLifecycleDecisionOptions(props.row?.request.type))
const isReject = computed(() => formModel.decision === DECISION.REJECT)

watch(visible, (opened) => {
  if (!opened) return
  formModel.decision = DECISION.APPROVE
  formModel.comment = ''
})

function close() {
  visible.value = false
  submitting.value = false
}

async function handleDecide() {
  const row = props.row
  if (!row) return
  // §2.6 请求体三字段（version 取 §2.5 申请单 version；comment 选填，空白不提交——
  // 后端原样回写 decision_comment，空串与 null 语义不同，前端不凑值）
  const payload: LifecycleDecisionRequest = {
    decision: formModel.decision,
    version: row.request.version,
  }
  const comment = formModel.comment.trim()
  if (comment) payload.comment = comment
  submitting.value = true
  try {
    const res = await decideLifecycleRequestApi(row.request.id, payload)
    ElMessage.success('终审决策已提交，供应商状态以服务端最新档案为准')
    close()
    if (res.data) emit('decided', res.data)
  } catch (err) {
    handleDecideError(err)
  } finally {
    submitting.value = false
  }
}

/** 按业务 code 分流（40901 走既有范式：契约固定文案 + 重拉取新 version，同 SupplierListView 处理） */
function handleDecideError(err: unknown) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  if (err.code === ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT) {
    ElMessage.error(SUPPLIER_LOCK_CONFLICT_MSG)
    if (props.row) emit('refresh', props.row.supplier_id)
    return
  }
  if (err.code === ERROR_CODE.STATUS_NOT_ALLOWED || err.code === ERROR_CODE.NOT_FOUND) {
    // 40302 申请单已决/供应商被并发淘汰、40401 申请单不存在：服务端事实已变，直显文案并请父级重拉
    ElMessage.error(err.message)
    if (props.row) emit('refresh', props.row.supplier_id)
    return
  }
  ElMessage.error(err.message)
}
</script>

<template>
  <el-dialog v-model="visible" title="供应商停用 / 淘汰终审决策" width="600px">
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
      title="批准将立即联动供应商状态（由后端状态机执行）；驳回只改申请单、供应商状态与版本不变。本页不提供任何直接改状态的旁路。"
    />

    <el-descriptions v-if="row" :column="2" border size="small" style="margin-bottom: 16px">
      <el-descriptions-item label="目标供应商">{{ row.supplier_name }}</el-descriptions-item>
      <el-descriptions-item label="供应商当前状态">
        <el-tag size="small" :type="getStatusTagType(row.supplier_status)">{{ getStatusLabel(row.supplier_status) }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="申请类型">{{ getLifecycleTypeLabel(row.request.type) }}</el-descriptions-item>
      <el-descriptions-item label="批准后目标状态">{{ getLifecycleTargetStatusLabel(row.request.type) }}</el-descriptions-item>
      <el-descriptions-item label="申请人">{{ row.request.applied_by_name || '-' }}</el-descriptions-item>
      <el-descriptions-item label="申请时间">{{ formatCreatedAt(row.request.applied_at) }}</el-descriptions-item>
      <el-descriptions-item label="申请理由" :span="2">{{ row.request.reason }}</el-descriptions-item>
    </el-descriptions>

    <el-form label-width="120px">
      <el-form-item label="终审决策" required>
        <!-- 传码显中文：单选 value=APPROVE/REJECT 两码，label 按申请类型分流（字典 getLifecycleDecisionOptions） -->
        <el-radio-group v-model="formModel.decision">
          <el-radio v-for="opt in decisionOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="决策意见">
        <el-input
          v-model="formModel.comment"
          type="textarea"
          :rows="3"
          maxlength="512"
          show-word-limit
          placeholder="选填（契约 §2.6 本域不强制驳回意见）；建议写明停用/恢复/淘汰的核查依据以便追溯"
        />
      </el-form-item>
      <el-form-item v-if="isReject" label-width="120px">
        <span class="reject-hint">驳回后该申请单置为「已驳回」，业务员可重新提交同类型申请；供应商维持当前状态。</span>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleDecide">提交终审决策</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.reject-hint {
  font-size: 12px;
  color: #e6a23c;
}
</style>
