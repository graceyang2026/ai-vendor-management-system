<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSupplierLogListApi } from '@/api/supplier-log'
import { ApiError } from '@/utils/request'
import { SUPPLIER_LOG_DEMO_LIST } from '@/constants/supplierLogDemo'
import {
  ALL_ACTION_OPTIONS,
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
 * 全量业务操作与审批日志（业务员 STAFF 维度视角，entity_type=SUPPLIER）。
 * 契约红线：action / old_status / new_status 用后端枚举码，中文经字典渲染；
 * 真实接口失败或后端扩展筛选参数未生效时落入原型示例数据兜底，筛选与分页保持可交互。
 */

const loading = ref(false)
const logs = ref<AuditLogRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 当前展示的是否为兜底示例数据 */
const isDemoData = ref(false)

const filters = reactive({
  entityName: '',
  action: '',
})

/** 按目标对象名 + 动作码过滤（示例数据模式，兼作后端未实现筛选时的前端补偿）。 */
function matchFilters(list: AuditLogRecord[]): AuditLogRecord[] {
  return list.filter((log) => {
    const matchEntity = !filters.entityName || getEntityLabelOrId(log).includes(filters.entityName)
    const matchAction = !filters.action || log.action === filters.action
    return matchEntity && matchAction
  })
}

/** 示例数据本地筛选 + 分页（兜底模式）。 */
function applyDemoFilter() {
  const filtered = matchFilters(SUPPLIER_LOG_DEMO_LIST)
  total.value = filtered.length
  logs.value = filtered.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
  isDemoData.value = true
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await getSupplierLogListApi({
      entity_name: filters.entityName || undefined,
      action: filters.action || undefined,
      page: page.value,
      page_size: pageSize.value,
    })
    if (res.data) {
      logs.value = matchFilters(res.data.list)
      total.value = res.data.total
      isDemoData.value = false
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作与审批日志加载失败')
    applyDemoFilter()
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  page.value = 1
  loadLogs()
}

function handleReset() {
  filters.entityName = ''
  filters.action = ''
  page.value = 1
  loadLogs()
}

/** 翻页：兜底模式走本地过滤，真实数据模式重新请求。 */
function handlePageChange() {
  if (isDemoData.value) {
    applyDemoFilter()
  } else {
    loadLogs()
  }
}

function handleSizeChange() {
  if (page.value !== 1) {
    // 重置页码会触发 current-change，由其统一发起加载
    page.value = 1
  } else {
    handlePageChange()
  }
}

onMounted(loadLogs)
</script>

<template>
  <div>
    <div class="page-header">
      <span class="page-title">全量业务操作与审批日志 (审计可追溯)</span>
    </div>

    <el-alert
      v-if="isDemoData"
      title="后端日志查询尚未就绪，当前展示原型示例数据"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <!-- 顶部筛选栏：下拉 value=后端动作码，label=中文 -->
    <el-form :inline="true" class="filter-form">
      <el-form-item label="供应商名称">
        <el-input v-model="filters.entityName" placeholder="搜索供应商..." clearable style="width: 200px" />
      </el-form-item>
      <el-form-item label="操作类型">
        <el-select v-model="filters.action" placeholder="全部类型" clearable style="width: 170px">
          <el-option v-for="option in ALL_ACTION_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 表格展示区 -->
    <el-table v-loading="loading" :data="logs" border stripe style="width: 100%">
      <el-table-column label="操作时间" width="170">
        <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="目标供应商" min-width="180">
        <template #default="scope">{{ getEntityLabelOrId(scope.row) }}</template>
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
      <el-table-column label="意见/结果/详细说明" min-width="240" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.comment || '-' }}</template>
      </el-table-column>
    </el-table>

    <!-- 底部分页 -->
    <div class="pagination-bar">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
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
.filter-form {
  margin-bottom: 10px;
}
.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
