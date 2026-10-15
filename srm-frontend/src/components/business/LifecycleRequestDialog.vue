<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createLifecycleRequestApi } from '@/api/lifecycle'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { MODULE2_VALIDATION } from '@/constants/supplierValidation'
import {
  getLifecycleTargetStatusLabel,
  getLifecycleTypeOptionsForStatus,
  getStatusLabel,
  getStatusTagType,
  type LifecycleRequestTypeCode,
} from '@/constants/auditLogView'
import type { LifecycleRequestResponse } from '@/types/lifecycle'
import type { SupplierItem } from '@/types/supplier'

/**
 * 业务员提生命周期申请弹窗（契约 §2.4 POST /suppliers/{id}/lifecycle-requests，仅 STAFF）。
 *
 * 两步流程铁律（acceptance §1.3 / rules.md 4.2）：本弹窗**只建一张 PENDING 申请单**，
 * 绝不改供应商状态、也不接收 version（后端 LifecycleRequestCreateRequest 两字段 type/reason，
 * 刻意没有 status 与 version）。成功后父级只刷新列表读服务端真值，前端不做任何本地状态推断，
 * 也不照原型「一键恢复为正常合作」那种绕过申请的直改交互（原型 UI 视觉可抄，操作语义照契约）。
 *
 * 类型-状态门槛（契约 §2.4：SUSPEND 仅 NORMAL、RESUME 仅 SUSPENDED、
 * ELIMINATE 仅 NORMAL/SUSPENDED、ELIMINATED 终态禁建）在前端只用于**过滤可选项**（体验层）：
 * 选项表来自 auditDictionary.getLifecycleTypeOptionsForStatus，门槛权威判定在后端
 * LifecycleRequestServiceImpl（不符 40302），前端不复制状态机去"假装"允许。
 *
 * 错误口径（用户拍板 20261014：HTTP 200 + 响应体 code，message 一律优先透传后端）：
 * 40001 参数校验失败（type 非法/reason 空白，后端 LifecycleValidationPatterns.PARAM_INVALID_MSG）
 * / 40301 非 STAFF（Security 层）/ 40302 状态门槛不符或非建档人「仅限本人操作」/ 40401 供应商不存在
 * / 40902 同类型已有在途申请（后端固定文案「已存在在途申请，请勿重复提交」）。
 */
const visible = defineModel<boolean>({ required: true })

const props = defineProps<{
  /** 目标供应商行快照（§1.3 列表行即 SupplierResponse 全 20 字段，status 驱动类型可选集） */
  supplier: SupplierItem | null
}>()

const emit = defineEmits<{
  /** 建单成功回传 §2.4 响应（LifecycleRequestResponse，status=PENDING、version=1），父级据此刷新列表 */
  created: [request: LifecycleRequestResponse]
}>()

/** 表单 UI 模型（非契约对象；reason 空串态，提交前收敛为 trim 非空） */
interface LifecycleRequestForm {
  type: LifecycleRequestTypeCode | ''
  reason: string
}

const formRef = ref<FormInstance>()
const submitting = ref(false)
const formModel = reactive<LifecycleRequestForm>({ type: '', reason: '' })
/** 40001 逐字段展示位（后端泛义文案反查 reason，同 SupplierListView 的 :error 绑定惯例） */
const reasonError = ref('')

/** 可选申请类型：按供应商当前状态过滤（不可用类型不进下拉，而非置灰诱导点击） */
const typeOptions = computed(() => (props.supplier ? getLifecycleTypeOptionsForStatus(props.supplier.status) : []))

/** 选中类型的批准后目标态提示（仅文案，来自字典，实际生效由 §2.6 终审 APPROVE 后由后端联动） */
const targetStatusHint = computed(() =>
  formModel.type ? `批准后供应商将变更为「${getLifecycleTargetStatusLabel(formModel.type)}」` : '',
)

/**
 * 打开（或切换目标供应商）时复位：类型默认取该状态首个可用项，避免空白下拉。
 * 仅建档人可提（用户裁决 20261015，契约 §2.4/§3 line「仅建档人可提」）：父级 SupplierListView 已叠加
 * created_by 与当前登录用户 ID 比对，非建档人的行直接隐藏申请入口（体验层收紧）；真实拦截仍在后端
 * LifecycleRequestServiceImpl.create()——非建档人 requireSelf→40302「仅限本人操作」。若列表快照滞后
 * 仍点进本弹窗，由下方 handleSubmitError 透传后端文案兜底（走 errorCode 既有映射，不新造文案）。
 */
watch(visible, (opened) => {
  if (!opened) return
  formModel.type = typeOptions.value[0]?.value ?? ''
  formModel.reason = ''
  reasonError.value = ''
  formRef.value?.clearValidate()
})

const formRules: FormRules<LifecycleRequestForm> = {
  type: [{ required: true, message: MODULE2_VALIDATION.PARAM_INVALID_MSG, trigger: 'change' }],
  reason: [
    // 后端 reason 仅 @NotBlank（PARAM_INVALID_MSG），"纯空白串"由 trim 后本地先验，最终仍由服务端 40001 复核
    { required: true, message: MODULE2_VALIDATION.PARAM_INVALID_MSG, trigger: 'blur' },
    {
      validator: (_rule, value, callback) => {
        if (!String(value ?? '').trim()) {
          callback(new Error(MODULE2_VALIDATION.PARAM_INVALID_MSG))
          return
        }
        callback()
      },
      trigger: 'blur',
    },
  ],
}

function close() {
  visible.value = false
  submitting.value = false
}

async function handleSubmit() {
  const supplier = props.supplier
  if (!supplier) return
  reasonError.value = ''
  const formEl = formRef.value
  if (!formEl) return
  try {
    await formEl.validate()
  } catch {
    return // 本地校验未过，错误已展示
  }
  if (!formModel.type) {
    // 防御：选项表为空（终态/不可申请状态）时不给提交机会，服务端另有 40302 兜底
    ElMessage.warning('该供应商当前状态不允许提出生命周期申请（以服务端 40302 校验为准）')
    return
  }
  submitting.value = true
  try {
    const res = await createLifecycleRequestApi(supplier.id, {
      type: formModel.type,
      reason: formModel.reason.trim(),
    })
    ElMessage.success('申请已提交，等待审核员终审（供应商状态暂不变更）')
    close()
    if (res.data) emit('created', res.data)
  } catch (err) {
    handleSubmitError(err)
  } finally {
    submitting.value = false
  }
}

/** 按业务 code 分流；文案一律透传后端（缺 message 时 request.ts 已按 errorCode 表兜底） */
function handleSubmitError(err: unknown) {
  if (!(err instanceof ApiError)) {
    ElMessage.error('网络异常，请稍后重试')
    return
  }
  if (err.code === ERROR_CODE.DUPLICATE_IN_PROGRESS) {
    // 40902 同类型在途单：后端固定「已存在在途申请，请勿重复提交」，直显不覆盖
    ElMessage.error(err.message)
    return
  }
  if (err.code === ERROR_CODE.VALIDATION_FAILED) {
    ElMessage.error(err.message)
    if (err.message === MODULE2_VALIDATION.PARAM_INVALID_MSG) {
      reasonError.value = err.message
    }
    return
  }
  if (err.code === ERROR_CODE.STATUS_NOT_ALLOWED) {
    // 40302：状态门槛不符，或非建档人「仅限本人操作」（后端 requireSelf，同 §1.2/§1.5 口径）；
    // 入口本被父级 created_by 门控隐藏，命中此处多为列表快照滞后——透传后端文案，不新造（裁决 20261015）
    ElMessage.error(err.message)
    return
  }
  ElMessage.error(err.message)
}
</script>

<template>
  <el-dialog v-model="visible" title="提出生命周期申请（停用 / 恢复 / 淘汰）" width="560px">
    <el-alert
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 16px"
      title="本入口只提交申请单，供应商状态不会变化；状态变更须由审核员终审（§2.6）批准后生效。同类型已有在途申请时不可重复提交。"
    />

    <el-descriptions v-if="supplier" :column="2" border size="small" style="margin-bottom: 16px">
      <el-descriptions-item label="供应商名称">{{ supplier.name }}</el-descriptions-item>
      <el-descriptions-item label="当前状态">
        <el-tag size="small" :type="getStatusTagType(supplier.status)">{{ getStatusLabel(supplier.status) }}</el-tag>
      </el-descriptions-item>
    </el-descriptions>

    <el-form ref="formRef" :model="formModel" :rules="formRules" label-width="110px">
      <el-form-item label="申请类型" prop="type">
        <!-- 传码显中文：value=LifecycleRequestType 码，label=字典中文；可用集由状态门槛表过滤 -->
        <el-select v-model="formModel.type" placeholder="请选择申请类型" style="width: 100%">
          <el-option v-for="opt in typeOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="targetStatusHint" label-width="110px">
        <span class="target-hint">{{ targetStatusHint }}</span>
      </el-form-item>
      <el-form-item label="申请理由" prop="reason" :error="reasonError">
        <el-input
          v-model="formModel.reason"
          type="textarea"
          :rows="4"
          maxlength="512"
          show-word-limit
          placeholder="必填：停用说明原因、恢复说明整改情况、淘汰说明依据（不得为纯空白）"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">提交申请</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.target-hint {
  font-size: 12px;
  color: #909399;
}
</style>
