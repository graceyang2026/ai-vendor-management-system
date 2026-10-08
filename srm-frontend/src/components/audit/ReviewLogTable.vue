<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getReviewLogListApi } from '@/api/audit-log'
import { ApiError } from '@/utils/request'
import { REVIEW_LOG_DEMO_LIST } from '@/constants/reviewLogDemo'
import { REVIEW_ACTION_TYPES, getReviewStatusTagType, type ReviewLogItem } from '@/types/audit-log'

/**
 * 全量操作与审批日志（审核员 AUDITOR 维度视角，系统全量合规审计与过程日志/审计追踪）。
 * 红线：真实接口失败（后端扩展筛选参数未就绪/网络异常）时落入原型示例数据兜底，
 * 筛选与分页在兜底模式下走本地逻辑保持可交互。
 */

const loading = ref(false)
const logs = ref<ReviewLogItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 当前展示的是否为兜底示例数据 */
const isDemoData = ref(false)

const filters = reactive({
  supplierName: '',
  actionType: '',
})

/** 示例数据本地筛选 + 分页（兜底模式）。 */
function applyDemoFilter() {
  const filtered = REVIEW_LOG_DEMO_LIST.filter((log) => {
    const matchSupplier = !filters.supplierName || log.supplier_name.includes(filters.supplierName)
    const matchType = !filters.actionType || log.action_type === filters.actionType
    return matchSupplier && matchType
  })
  total.value = filtered.length
  logs.value = filtered.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
  isDemoData.value = true
}

async function loadLogs() {
  loading.value = true
  try {
    const res = await getReviewLogListApi({
      supplier_name: filters.supplierName || undefined,
      action_type: filters.actionType || undefined,
      page: page.value,
      page_size: pageSize.value,
    })
    if (res.data) {
      logs.value = res.data.list
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
  filters.supplierName = ''
  filters.actionType = ''
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
      <span class="page-title">系统全量合规审计与过程日志 (审计追踪)</span>
    </div>

    <el-alert
      v-if="isDemoData"
      title="后端日志扩展筛选接口尚未就绪，当前展示原型示例数据"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <!-- 顶部筛选栏 -->
    <el-form :inline="true" class="filter-form">
      <el-form-item label="目标供应商">
        <el-input v-model="filters.supplierName" placeholder="搜索供应商..." clearable style="width: 200px" />
      </el-form-item>
      <el-form-item label="操作类型">
        <el-select v-model="filters.actionType" placeholder="全部类型" clearable style="width: 160px">
          <el-option v-for="actionType in REVIEW_ACTION_TYPES" :key="actionType" :label="actionType" :value="actionType" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 表格展示区 -->
    <el-table v-loading="loading" :data="logs" border stripe style="width: 100%">
      <el-table-column prop="created_at" label="操作时间" width="170" />
      <el-table-column prop="supplier_name" label="目标供应商" min-width="180" />
      <el-table-column prop="operator_name" label="审核人/操作人" width="150" />
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
      <el-table-column prop="comment" label="审计意见 / 原因 / 批注说明" min-width="240" show-overflow-tooltip />
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
