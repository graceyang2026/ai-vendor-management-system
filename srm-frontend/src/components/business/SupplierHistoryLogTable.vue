<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getSupplierLogListApi } from '@/api/supplier-log'
import { ApiError } from '@/utils/request'
import { SUPPLIER_LOG_DEMO_LIST } from '@/constants/supplierLogDemo'
import {
  formatCreatedAt,
  formatOperator,
  getActionLabel,
  getActionTagType,
  getEntityLabelOrId,
  getStatusLabel,
  getStatusTagType,
} from '@/constants/auditLogView'
import type { AuditLogRecord } from '@/types/audit-log'

/**
 * 特定供应商档案的追溯日志历史（嵌入供应商详情页"当前档案追溯日志历史"卡片）。
 * 入参 supplier-id 映射后端 entity_id；后端未就绪时按对象名本地过滤示例数据兜底。
 */
const props = defineProps<{
  supplierId?: string | number
  supplierName: string
}>()

const loading = ref(false)
const logs = ref<AuditLogRecord[]>([])
const isDemoData = ref(false)

function applyDemoFilter() {
  logs.value = SUPPLIER_LOG_DEMO_LIST.filter((log) => {
    if (props.supplierId !== undefined && log.entity_id === props.supplierId) {
      return true
    }
    return getEntityLabelOrId(log) === props.supplierName
  })
  isDemoData.value = true
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await getSupplierLogListApi({
      entity_id: props.supplierId,
      page: 1,
      page_size: 100,
    })
    if (res.data) {
      logs.value = res.data.list
      isDemoData.value = false
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '档案追溯日志加载失败')
    applyDemoFilter()
  } finally {
    loading.value = false
  }
}

// 详情视图复用本组件时，切换供应商需重新加载
watch(() => [props.supplierId, props.supplierName], loadLogs)

onMounted(loadLogs)
</script>

<template>
  <div>
    <el-alert
      v-if="isDemoData"
      title="后端档案追溯日志接口尚未就绪，当前展示原型示例数据"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />
    <el-table v-loading="loading" :data="logs" border stripe style="width: 100%">
      <el-table-column label="操作时间" width="170">
        <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="160">
        <template #default="scope">{{ formatOperator(scope.row.operator_name, scope.row.operator_role) }}</template>
      </el-table-column>
      <el-table-column label="操作类型" width="150">
        <template #default="scope">
          <el-tag size="small" :type="getActionTagType(scope.row.action)">{{ getActionLabel(scope.row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原状态" width="130">
        <template #default="scope">
          <el-tag size="small" type="info">{{ getStatusLabel(scope.row.old_status, scope.row.entity_type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="新状态" width="130">
        <template #default="scope">
          <el-tag size="small" :type="getStatusTagType(scope.row.new_status)">{{
            getStatusLabel(scope.row.new_status, scope.row.entity_type)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="意见 / 结果说明" min-width="220" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.comment || '-' }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && logs.length === 0" description="该供应商暂无追溯日志" :image-size="60" />
  </div>
</template>
