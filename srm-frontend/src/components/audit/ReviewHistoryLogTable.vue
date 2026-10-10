<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getReviewLogListApi } from '@/api/audit-log'
import { ApiError } from '@/utils/request'
import { REVIEW_LOG_DEMO_LIST } from '@/constants/reviewLogDemo'
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
import { AUDITOR_ENTITY_TYPES } from '@/types/audit-log'

/**
 * 特定供应商/审核单据的历史审批与追溯日志（嵌入审核详情页"历史审批与追溯日志"区域）。
 * 入参 supplier-id 映射后端 entity_id；后端未就绪时按对象名本地过滤示例数据兜底。
 *
 * 契约缺口：后端 audit_log 对终审决策记录的是 LIFECYCLE_REQUEST（entity_id=申请单 ID），
 * 按供应商聚合追溯需后端补 supplier_id 查询参数，兜底模式下改按 entity_name 匹配以保证三类记录可见。
 * 视角范围（裁决 20261011，docs/api-spec.md §5「entity_type 参数取值规则」延伸至详情内追溯面板）：
 * 显式传业务域三实体逗号列表，后端按 IN 过滤，确保系统管理员的 USER 域日志不因 ID 撞号串入本面板。
 */
const props = defineProps<{
  supplierId?: string | number
  /** 审核单据 ID（后端支持单据维度追溯时优先使用） */
  reviewId?: string | number
  supplierName: string
}>()

const loading = ref(false)
const logs = ref<AuditLogRecord[]>([])
const isDemoData = ref(false)

function applyDemoFilter() {
  logs.value = REVIEW_LOG_DEMO_LIST.filter((log) => {
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
    const res = await getReviewLogListApi({
      // 缺陷修复 20261011（决策点 1）：按 entity_id 查追溯日志必须同时传业务域三实体逗号列表
      // （后端 entity_type 支持逗号多值 IN），防 USER 域日志因用户 ID 与供应商 ID 数值撞号而串入审核员详情视图
      entity_type: AUDITOR_ENTITY_TYPES.join(','),
      entity_id: props.supplierId ?? props.reviewId,
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
      <el-table-column label="操作时间" width="170">
        <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="审核人" width="160">
        <template #default="scope">{{ formatOperator(scope.row.operator_name, scope.row.operator_role) }}</template>
      </el-table-column>
      <el-table-column label="动作类型" width="150">
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
      <el-table-column label="审核意见 / 原因" min-width="220" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.comment || '-' }}</template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && logs.length === 0" description="该供应商暂无历史审批与追溯日志" :image-size="60" />
  </div>
</template>
