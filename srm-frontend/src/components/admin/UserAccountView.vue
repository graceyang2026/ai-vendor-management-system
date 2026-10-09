<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { createUserApi, getUserListApi, updateUserApi, updateUserStatusApi } from '@/api/user'
import { ApiError } from '@/utils/request'
import { ERROR_CODE } from '@/constants/errorCode'
import { ROLE_LABEL_CN, formatCreatedAt } from '@/constants/auditDictionary'
import { useUserStore } from '@/stores/user'
import {
  USER_ROLE_OPTIONS,
  USER_STATUS_OPTIONS,
  getUserRoleTagType,
  toUserStatusCode,
  type UserCreatePayload,
  type UserItem,
} from '@/types/user'

/**
 * 内部用户账号管理（ADMIN 专属，参考 docs/mockups/原型展示-系统管理员v1.1_待评审20260917.html
 * 的 user-list / user-add / user-edit 三视图）。
 *
 * 契约红线（docs/api-spec.md 第 6 节 + 后端 UserController/UserServiceImpl 已实现）：
 * - 后端字段是 username / real_name / role / enabled(boolean)，原型的 account / name / status 已按真实契约映射；
 * - 后端与 sys_user 表均无 email 字段，原型的工作邮箱列与表单项已删除；
 * - POST /users 的 password 后端 @NotBlank 必填（原型未画该输入框，此处补齐）；
 * - PUT /users/{id} 只发 real_name / role —— 携带 username 或 password 后端直接 40001 拒绝；
 * - 防自锁：停用自己 / 修改自己角色后端返回 40302，前端同步禁用入口。
 *
 * 筛选说明：后端 GET /users 仅消费 page / page_size（无关键词与状态参数），
 * 故一次拉取整表后在本地完成关键词/状态过滤，分页仍交后端参数位（MVP 用户量小）。
 */

const userStore = useUserStore()
/** 当前登录管理员 ID（防自锁判定用） */
const selfId = computed(() => userStore.userInfo?.user_id)

/** 角色码 → 中文（沿用审计字典，未知码原样回显）。 */
function getUserRoleLabel(role: string): string {
  return ROLE_LABEL_CN[role] ?? role
}

type UserView = 'user-list' | 'user-add' | 'user-edit'
const currentView = ref<UserView>('user-list')

const loading = ref(false)
const saving = ref(false)
const allUsers = ref<UserItem[]>([])

/** 后端分页参数位：拉全量时用大 page_size，真实分页待后端支持筛选后启用 */
const query = reactive({ page: 1, page_size: 100 })

const searchForm = reactive({ keyword: '', status: '' as '' | UserStatusFilter })
type UserStatusFilter = 'ENABLED' | 'DISABLED'

/** 本地关键词（账号/姓名模糊）+ 状态过滤。 */
const users = computed(() =>
  allUsers.value.filter((row) => {
    const kw = searchForm.keyword.trim().toLowerCase()
    const matchKeyword =
      !kw || row.username.toLowerCase().includes(kw) || row.real_name.toLowerCase().includes(kw)
    const matchStatus =
      !searchForm.status || toUserStatusCode(row.enabled) === searchForm.status
    return matchKeyword && matchStatus
  }),
)

async function loadUsers() {
  loading.value = true
  try {
    const res = await getUserListApi({ ...query })
    allUsers.value = res.data?.list ?? []
  } catch (error) {
    allUsers.value = []
    ElMessage.error(error instanceof ApiError ? error.message : '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  // 过滤在本地 computed 完成，此处仅回到第一页语义占位（后端筛选参数就绪后改为重新请求）
  query.page = 1
}

function handleReset() {
  searchForm.keyword = ''
  searchForm.status = ''
  handleSearch()
}

/* ------------------------------------------------------------------ 新增 */

const addFormRef = ref<FormInstance>()
const addForm = reactive<UserCreatePayload>({ username: '', password: '', real_name: '', role: 'STAFF' })

const addRules: FormRules<UserCreatePayload> = {
  username: [
    { required: true, message: '请输入登录账号（工号）', trigger: 'blur' },
    { min: 2, max: 50, message: '账号长度需在 2-50 之间', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 6, max: 32, message: '初始密码长度需在 6-32 之间', trigger: 'blur' },
  ],
  real_name: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择分配角色', trigger: 'change' }],
}

function handleToCreate() {
  addForm.username = ''
  addForm.password = ''
  addForm.real_name = ''
  addForm.role = 'STAFF'
  addFormRef.value?.clearValidate()
  currentView.value = 'user-add'
}

async function handleCreate() {
  if (!addFormRef.value) return
  const valid = await addFormRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    // 请求体严格四字段，禁止携带后端 UserCreateRequest 之外的属性（如 email）
    await createUserApi({
      username: addForm.username.trim(),
      password: addForm.password,
      real_name: addForm.real_name.trim(),
      role: addForm.role,
    })
    ElMessage.success('账号创建成功，操作已留痕日志')
    currentView.value = 'user-list'
    await loadUsers()
  } catch (error) {
    if (error instanceof ApiError && error.code === ERROR_CODE.USERNAME_DUPLICATE) {
      ElMessage.error(error.message || '账号已存在，请更换工号或账号')
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : '账号创建失败')
    }
  } finally {
    saving.value = false
  }
}

/* ------------------------------------------------------------------ 编辑角色 */

const editFormRef = ref<FormInstance>()
const editForm = reactive<{ id: number | null; username: string; real_name: string; role: string }>({
  id: null,
  username: '',
  real_name: '',
  role: 'STAFF',
})

const editRules: FormRules<typeof editForm> = {
  real_name: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择分配角色', trigger: 'change' }],
}

const isEditingSelf = computed(() => editForm.id !== null && editForm.id === selfId.value)

function handleEdit(row: UserItem) {
  editForm.id = row.id
  editForm.username = row.username
  editForm.real_name = row.real_name
  editForm.role = row.role
  editFormRef.value?.clearValidate()
  currentView.value = 'user-edit'
}

async function handleUpdate() {
  if (!editFormRef.value || editForm.id === null) return
  const valid = await editFormRef.value.validate().catch(() => false)
  if (!valid) return

  saving.value = true
  try {
    // 仅 PUT real_name / role：username、password 出现在请求体后端即 40001 拒绝
    await updateUserApi(editForm.id, {
      real_name: editForm.real_name.trim(),
      role: editForm.role,
    })
    ElMessage.success('用户资料已更新，操作已留痕日志')
    currentView.value = 'user-list'
    await loadUsers()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '用户更新失败')
  } finally {
    saving.value = false
  }
}

/* ------------------------------------------------------------------ 启用 / 停用 */

async function handleToggleStatus(row: UserItem) {
  const nextEnabled = !row.enabled
  const actionText = nextEnabled ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(`确认${actionText}账号「${row.username}（${row.real_name}）」？`, `${actionText}账号`, {
      type: 'warning',
      confirmButtonText: `确认${actionText}`,
      cancelButtonText: '取消',
    })
  } catch {
    return // 用户取消
  }

  rowLoadingId.value = row.id
  try {
    await updateUserStatusApi(row.id, { enabled: nextEnabled })
    ElMessage.success(`账号已${actionText}，操作已留痕日志`)
    await loadUsers()
  } catch (error) {
    if (error instanceof ApiError && error.code === ERROR_CODE.STATUS_NOT_ALLOWED) {
      ElMessage.error(error.message || '不允许对该账号执行此操作')
    } else {
      ElMessage.error(error instanceof ApiError ? error.message : `${actionText}失败`)
    }
  } finally {
    rowLoadingId.value = null
  }
}

const rowLoadingId = ref<number | null>(null)

function backToList() {
  currentView.value = 'user-list'
}

onMounted(loadUsers)
</script>

<template>
  <div class="user-account">
    <!-- 1. 用户列表（user-list） -->
    <template v-if="currentView === 'user-list'">
      <div class="page-header">
        <span class="page-title">内部用户账号管理</span>
        <el-button type="primary" @click="handleToCreate">+ 新增用户账号</el-button>
      </div>

      <el-form :inline="true" class="filter-bar">
        <el-form-item label="姓名/工号">
          <el-input
            v-model="searchForm.keyword"
            placeholder="请输入姓名或工号"
            clearable
            style="width: 200px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="账号状态">
          <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option v-for="opt in USER_STATUS_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 列严格对齐后端 UserResponse：无 email 列 -->
      <el-table v-loading="loading" :data="users" border stripe style="width: 100%">
        <el-table-column prop="username" label="工号/账号" width="140" />
        <el-table-column prop="real_name" label="姓名" width="140" />
        <el-table-column label="分配角色" width="180">
          <template #default="scope">
            <el-tag :type="getUserRoleTagType(scope.row.role)" size="small">{{ getUserRoleLabel(scope.row.role) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.enabled ? 'success' : 'danger'" size="small">
              {{ scope.row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="scope">{{ formatCreatedAt(scope.row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" min-width="220">
          <template #default="scope">
            <el-button size="small" type="primary" link @click="handleEdit(scope.row)">编辑角色</el-button>
            <el-button
              size="small"
              :type="scope.row.enabled ? 'danger' : 'success'"
              link
              :disabled="scope.row.id === selfId"
              :loading="rowLoadingId === scope.row.id"
              :title="scope.row.id === selfId ? '防自锁：不允许停用自己' : ''"
              @click="handleToggleStatus(scope.row)"
            >
              {{ scope.row.enabled ? '停用账号' : '启用账号' }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无用户数据" :image-size="60" />
        </template>
      </el-table>
    </template>

    <!-- 2. 新增用户（user-add，无邮箱输入框） -->
    <template v-else-if="currentView === 'user-add'">
      <div class="page-header">
        <span class="page-title">新建内部用户</span>
        <el-button @click="backToList">返回用户列表</el-button>
      </div>
      <el-form ref="addFormRef" :model="addForm" :rules="addRules" label-width="100px" class="form-container">
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="addForm.username" placeholder="请输入员工工号或账号" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="addForm.password" type="password" show-password placeholder="请输入初始登录密码" />
        </el-form-item>
        <el-form-item label="真实姓名" prop="real_name">
          <el-input v-model="addForm.real_name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="分配角色" prop="role">
          <el-select v-model="addForm.role" placeholder="请选择角色" style="width: 100%">
            <el-option v-for="opt in USER_ROLE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleCreate">确认创建</el-button>
          <el-button @click="backToList">取消</el-button>
        </el-form-item>
      </el-form>
    </template>

    <!-- 3. 编辑用户（user-edit：账号只读，仅姓名与角色可改） -->
    <template v-else>
      <div class="page-header">
        <span class="page-title">编辑用户角色与权限 - {{ editForm.real_name }}</span>
        <el-button @click="backToList">返回用户列表</el-button>
      </div>
      <el-alert
        title="登录账号不可修改；姓名与角色变更均写入审计日志（UPDATE_USER）。"
        type="info"
        show-icon
        :closable="false"
        style="margin-bottom: 20px"
      />
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px" class="form-container">
        <el-form-item label="登录账号">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="真实姓名" prop="real_name">
          <el-input v-model="editForm.real_name" placeholder="请输入姓名" />
        </el-form-item>
        <el-form-item label="调整角色" prop="role">
          <el-select
            v-model="editForm.role"
            style="width: 100%"
            :disabled="isEditingSelf"
            :placeholder="isEditingSelf ? '不允许修改自己的角色' : '请选择角色'"
          >
            <el-option v-for="opt in USER_ROLE_OPTIONS" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleUpdate">保存修改</el-button>
          <el-button @click="backToList">取消</el-button>
        </el-form-item>
      </el-form>
    </template>
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
.filter-bar {
  margin-bottom: 4px;
}
.form-container {
  max-width: 520px;
}
</style>
