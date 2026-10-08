<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReviewLogListApi } from '@/api/audit-log'
import { ApiError } from '@/utils/request'
import { REVIEW_LOG_DEMO_LIST } from '@/constants/reviewLogDemo'
import { getReviewStatusTagType, type ReviewLogItem } from '@/types/audit-log'

/**
 * 特定供应商/审核单据的历史审批与追溯日志（嵌入审核详情页"历史审批与追溯日志"区域）。
 * 接收 supplier_id（或 review_id）+ supplier_name 作为 Prop；
 * 后端未就绪时按供应商本地过滤示例数据兜底。
 */
const props = defineProps<{
  supplierId?: string | number
  /** 审核单据 ID（后端支持单据维度追溯时优先使用） */
  reviewId?: string | number
  supplierName: string
}>()

const loading = ref(false)
const logs = ref<ReviewLogItem[]>([])
const isDemoData = ref(false)

function applyDemoFilter() {
  logs.value = REVIEW_LOG_DEMO_LIST.filter((log) => {
    if (props.supplierId !== undefined) {
      return log.supplier_id === props.supplierId
    }
    return log.supplier_name === props.supplierName
  })
  isDemoData.value = true
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await getReviewLogListApi({
      supplier_id: props.supplierId ?? props.reviewId,
      supplier_name: props.supplierName,
      page: 1,
      page_size: 100,
    })
    if (res.data) {
      logs.value = res.data.list
      isDemoData.value = false
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '历史审批追溯日志加载失败')
    applyDemoFilter()
  } finally {
    loading.value = false
  }
}

// 详情视图复用本组件时，切换供应商/单据需重新加载
watch(() => [props.supplierId, props.reviewId, props.supplierName], loadLogs)

onMounted(loadLogs)
</script>

<template>
  <div>
    <el-alert
      v-if="isDemoData"
      title="后端追溯日志接口尚未就绪，当前展示原型示例数据"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />
    <el-table v-loading="loading" :data="logs" border stripe style="width: 100%">
      <el-table-column prop="created_at" label="操作时间" width="170" />
      <el-table-column prop="operator_name" label="审核人" width="140" />
      <el-table-column prop="action_type" label="动作类型" width="140" />
      <el-table-column prop="old_status" label="原状态" width="120">
        <template #default="scope">
          <el-tag size="small" type="info">{{ scope.row.old_status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="new_status" label="新状态" width="120">
        <template #default="scope">
          <el-tag size="small" :type="getReviewStatusTagType(scope.row.new_status)">{{ scope.row.new_status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="comment" label="审核意见 / 原因" min-width="220" show-overflow-tooltip />
    </el-table>
    <el-empty v-if="!loading && logs.length === 0" description="该供应商暂无历史审批与追溯日志" :image-size="60" />
  </div>
</template>
