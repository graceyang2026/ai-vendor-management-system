<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminLogListApi } from '@/api/admin-log'
import { ApiError } from '@/utils/request'
import { ADMIN_ACTION_TYPES, type AdminLogItem } from '@/types/admin-log'

/** 原型示例数据：后端管理日志查询形态未就绪时兜底，保证页面可演示（数据取自管理员原型）。 */
const DEMO_LOGS: AdminLogItem[] = [
  {
    id: 1,
    created_at: '2026-09-16 08:30:15',
    operator_name: '超级管理员 (Admin)',
    action_type: '新增用户',
    target_object: 'EMP001 (张三)',
    detail: '成功创建业务员账号，分配角色：业务员 (Staff)',
  },
  {
    id: 2,
    created_at: '2026-09-16 08:32:00',
    operator_name: '超级管理员 (Admin)',
    action_type: '新增用户',
    target_object: 'EMP002 (李四)',
    detail: '成功创建审计员账号，分配角色：审计员 (Auditor)',
  },
  {
    id: 3,
    created_at: '2026-09-16 09:10:45',
    operator_name: '超级管理员 (Admin)',
    action_type: '停用账号',
    target_object: 'EMP003 (王五)',
    detail: '将账号状态由【启用】变更为【停用】',
  },
  {
    id: 4,
    created_at: '2026-09-17 10:05:20',
    operator_name: '超级管理员 (Admin)',
    action_type: '调整用户角色',
    target_object: 'EMP003 (王五)',
    detail: '角色由【业务员 (Staff)】变更修改为【审计员 (Auditor)】',
  },
  {
    id: 5,
    created_at: '2026-09-17 14:42:08',
    operator_name: '超级管理员 (Admin)',
    action_type: '启用账号',
    target_object: 'EMP003 (王五)',
    detail: '账号状态从【停用】变更为【启用】',
  },
  {
    id: 6,
    created_at: '2026-09-18 09:15:33',
    operator_name: '超级管理员 (Admin)',
    action_type: '调整用户角色',
    target_object: 'EMP001 (张三)',
    detail: '角色由【审计员 (Auditor)】变更修改为【业务员 (Staff)】',
  },
]

/** el-tag 颜色映射（任务规约）：新增/启用 success、调整 warning、停用 danger。 */
const ACTION_TAG_TYPE: Record<string, 'success' | 'warning' | 'danger'> = {
  新增用户: 'success',
  启用账号: 'success',
  调整用户角色: 'warning',
  停用账号: 'danger',
}

function getActionTagType(actionType: string): 'success' | 'warning' | 'danger' {
  return ACTION_TAG_TYPE[actionType] ?? 'success'
}

const loading = ref(false)
const logs = ref<AdminLogItem[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 当前展示的是否为兜底示例数据 */
const isDemoData = ref(false)

const filters = reactive({
  operatorName: '',
  actionType: '',
})
const dateRange = ref<string[] | null>(null)

/** 示例数据本地筛选 + 分页（兜底模式下保持筛选/分页交互可用）。 */
function applyDemoFilter() {
  const startDate = dateRange.value?.[0]
  const endDate = dateRange.value?.[1]
  const filtered = DEMO_LOGS.filter((log) => {
    const matchOperator = !filters.operatorName || log.operator_name.includes(filters.operatorName)
    const matchType = !filters.actionType || log.action_type === filters.actionType
    const logDate = log.created_at.slice(0, 10)
    const matchStart = !startDate || logDate >= startDate
    const matchEnd = !endDate || logDate <= endDate
    return matchOperator && matchType && matchStart && matchEnd
  })
  total.value = filtered.length
  logs.value = filtered.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
  isDemoData.value = true
}

/** 请求后端日志接口；失败（后端管理日志形态未就绪/网络异常）时落入示例数据兜底。 */
async function loadLogs() {
  loading.value = true
  try {
    const res = await getAdminLogListApi({
      operator_name: filters.operatorName || undefined,
      action_type: filters.actionType || undefined,
      start_date: dateRange.value?.[0],
      end_date: dateRange.value?.[1],
      page: page.value,
      page_size: pageSize.value,
    })
    if (res.data) {
      logs.value = res.data.list
      total.value = res.data.total
      isDemoData.value = false
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '系统操作日志加载失败')
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
  filters.operatorName = ''
  filters.actionType = ''
  dateRange.value = null
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
      <span class="page-title">系统管理操作与权限审计日志</span>
    </div>

    <el-alert
      v-if="isDemoData"
      title="后端管理日志查询接口尚未就绪，当前展示原型示例数据"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
    />

    <!-- 筛选区 -->
    <el-form :inline="true" class="filter-form">
      <el-form-item label="操作人">
        <el-input v-model="filters.operatorName" placeholder="搜索操作人..." clearable style="width: 180px" />
      </el-form-item>
      <el-form-item label="操作类型">
        <el-select v-model="filters.actionType" placeholder="全部类型" clearable style="width: 160px">
          <el-option v-for="actionType in ADMIN_ACTION_TYPES" :key="actionType" :label="actionType" :value="actionType" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="handleSearch">查询</el-button>
        <el-button @click="handleReset">重置</el-button>
      </el-form-item>
    </el-form>

    <!-- 表格区 -->
    <el-table v-loading="loading" :data="logs" border stripe style="width: 100%">
      <el-table-column prop="created_at" label="操作时间" width="170" />
      <el-table-column prop="operator_name" label="操作人" width="150" />
      <el-table-column prop="action_type" label="操作类型" width="140">
        <template #default="scope">
          <el-tag size="small" :type="getActionTagType(scope.row.action_type)">{{ scope.row.action_type }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="target_object" label="目标账号/对象" width="160" />
      <el-table-column prop="detail" label="详细变动记录与参数说明" min-width="240" show-overflow-tooltip />
    </el-table>

    <!-- 分页区 -->
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
