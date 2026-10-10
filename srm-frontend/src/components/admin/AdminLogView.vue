<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminLogListApi } from '@/api/admin-log'
import { ApiError } from '@/utils/request'
import type { AuditLogRecord } from '@/types/audit-log'
import {
  ADMIN_ACTION_OPTIONS,
  AUDIT_ACTION,
  formatCreatedAt,
  formatOperator,
  getActionLabel,
  getActionTagType,
  getEntityLabelOrId,
  getStatusLabel,
  getStatusTagType,
} from '@/constants/auditLogView'

/**
 * 系统管理操作与权限审计日志（ADMIN 维度，entity_type=USER）。
 * 契约收敛说明：原型自造的 action_type / target_object / detail 后端不存在，
 * 分别对应真实字段 action / entity_id(+entity_name) / comment。
 * 动作码一律用后端 AuditAction 用户域真实码（CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER），
 * 中文与颜色由 @/constants/auditDictionary 映射，组件内不写中文动作名；
 * 状态列按后端 UserServiceImpl 原值传布尔字符串 "true"/"false"（UPDATE_USER 不改状态故两列留空，
 * 变更明细落 comment，见 docs/用户管理接口规格增补稿.md §3.2~§3.5）。
 * 下方 DEMO_LOGS 仅为接口异常时的兜底示例数据，形态与后端真实返回保持一致。
 */
const DEMO_LOGS: AuditLogRecord[] = [
  {
    id: 1,
    entity_type: 'USER',
    entity_id: 2,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.CREATE_USER,
    old_status: null,
    new_status: 'true',
    result: 'SUCCESS',
    comment: '成功创建业务员账号，分配角色：业务员 (Staff)',
    created_at: '2026-09-16T08:30:15',
    entity_name: 'staff01 (杨伟)',
  },
  {
    id: 2,
    entity_type: 'USER',
    entity_id: 3,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.CREATE_USER,
    old_status: null,
    new_status: 'true',
    result: 'SUCCESS',
    comment: '成功创建审计员账号，分配角色：审计员 (Auditor)',
    created_at: '2026-09-16T08:32:00',
    entity_name: 'auditor01 (李四)',
  },
  {
    id: 3,
    entity_type: 'USER',
    entity_id: 4,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.DISABLE_USER,
    old_status: 'true',
    new_status: 'false',
    result: 'SUCCESS',
    comment: '将账号状态由【启用】变更为【停用】',
    created_at: '2026-09-16T09:10:45',
    entity_name: 'staff02 (王五)',
  },
  {
    id: 4,
    entity_type: 'USER',
    entity_id: 4,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.UPDATE_USER,
    old_status: null,
    new_status: null,
    result: 'SUCCESS',
    comment: '角色调整: STAFF→AUDITOR（角色有变更，需重新登录后生效）',
    created_at: '2026-09-17T10:05:20',
    entity_name: 'staff02 (王五)',
  },
  {
    id: 5,
    entity_type: 'USER',
    entity_id: 4,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.ENABLE_USER,
    old_status: 'false',
    new_status: 'true',
    result: 'SUCCESS',
    comment: '账号状态从【停用】变更为【启用】',
    created_at: '2026-09-17T14:42:08',
    entity_name: 'staff02 (王五)',
  },
  {
    id: 6,
    entity_type: 'USER',
    entity_id: 2,
    operator_id: 1,
    operator_name: '超级管理员',
    operator_role: 'ADMIN',
    action: AUDIT_ACTION.UPDATE_USER,
    old_status: null,
    new_status: null,
    result: 'SUCCESS',
    comment: '角色调整: AUDITOR→STAFF（角色有变更，需重新登录后生效）',
    created_at: '2026-09-18T09:15:33',
    entity_name: 'staff01 (杨伟)',
  },
]

const loading = ref(false)
const logs = ref<AuditLogRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(10)
/** 当前展示的是否为兜底示例数据 */
const isDemoData = ref(false)

const filters = reactive({
  operatorName: '',
  action: '',
})
const dateRange = ref<string[] | null>(null)

/** 操作人 + 动作码 + 时间范围本地过滤（示例数据模式，兼作后端未实现筛选时的补偿）。 */
function matchFilters(list: AuditLogRecord[]): AuditLogRecord[] {
  const startDate = dateRange.value?.[0]
  const endDate = dateRange.value?.[1]
  return list.filter((log) => {
    const matchOperator = !filters.operatorName || formatOperator(log.operator_name, log.operator_role).includes(filters.operatorName)
    const matchAction = !filters.action || log.action === filters.action
    const logDate = log.created_at.slice(0, 10)
    const matchStart = !startDate || logDate >= startDate
    const matchEnd = !endDate || logDate <= endDate
    return matchOperator && matchAction && matchStart && matchEnd
  })
}

/** 示例数据本地筛选 + 分页（兜底模式下保持筛选/分页交互可用）。 */
function applyDemoFilter() {
  const filtered = matchFilters(DEMO_LOGS)
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
      action: filters.action || undefined,
      start_date: dateRange.value?.[0],
      end_date: dateRange.value?.[1],
      page: page.value,
      page_size: pageSize.value,
    })
    if (res.data) {
      logs.value = matchFilters(res.data.list)
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
  filters.action = ''
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
        <el-select v-model="filters.action" placeholder="全部类型" clearable style="width: 160px">
          <el-option v-for="option in ADMIN_ACTION_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
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
      <el-table-column label="操作时间" width="170">
        <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="160">
        <template #default="scope">{{ formatOperator(scope.row.operator_name, scope.row.operator_role) }}</template>
      </el-table-column>
      <el-table-column label="操作类型" width="140">
        <template #default="scope">
          <el-tag size="small" :type="getActionTagType(scope.row.action)">{{ getActionLabel(scope.row.action) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标账号/对象" width="170">
        <template #default="scope">{{ getEntityLabelOrId(scope.row) }}</template>
      </el-table-column>
      <el-table-column label="状态/角色变更" width="200">
        <template #default="scope">
          <el-tag v-if="scope.row.old_status" size="small" type="info">{{
            getStatusLabel(scope.row.old_status, scope.row.entity_type)
          }}</el-tag>
          <span v-if="scope.row.old_status" style="margin: 0 6px">→</span>
          <el-tag size="small" :type="getStatusTagType(scope.row.new_status)">{{
            getStatusLabel(scope.row.new_status, scope.row.entity_type)
          }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="详细变动记录与参数说明" min-width="240" show-overflow-tooltip>
        <template #default="scope">{{ scope.row.comment || '-' }}</template>
      </el-table-column>
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
