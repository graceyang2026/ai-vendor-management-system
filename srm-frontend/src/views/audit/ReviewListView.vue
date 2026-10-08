<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, Star, Select, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import ReviewLogTable from '@/components/audit/ReviewLogTable.vue'
import ReviewHistoryLogTable from '@/components/audit/ReviewHistoryLogTable.vue'
import { getStatusLabel, getStatusTagType } from '@/constants/auditLogView'

/**
 * 审计与合规控制台（参考 docs/mockups/原型展示-审核员v1.1_待评审20260917.html）。
 * 红线：本页仅 AUDITOR 可访问，由路由守卫 meta.roles=['AUDITOR']（/audit/review-list）强制拦截；
 * 日志视角为审核员维度（合规审计与过程追溯）。
 * 本步实现：操作与审批日志主视图 + 审核详情历史审批与追溯日志；
 * 待办审核列表保留原型演示数据、审核签署/终审决策/绩效复核等业务动作待下一步对接接口。
 */
const router = useRouter()
const userStore = useUserStore()

type MenuKey = 'audit-list' | 'status-decision-list' | 'perf-review-list' | 'log-list'
type ViewKey = MenuKey | 'audit-detail'

const activeMenu = ref<MenuKey>('audit-list')
const currentView = ref<ViewKey>('audit-list')

/** 视图切换：菜单直达视图；进入详情时菜单保持"准入资质审核"高亮（与原型一致）。 */
function handleMenuSelect(index: string) {
  activeMenu.value = index as MenuKey
  currentView.value = index as ViewKey
}

function navigateTo(view: ViewKey) {
  currentView.value = view
}

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}

/** 准入审核演示数据（原型 auditSuppliers，id 与追溯日志示例的 entity_id 对应；status 为 SupplierStatus 枚举码）。 */
interface AuditSupplier {
  id: number
  name: string
  tax_no: string
  type: string
  contact: string
  phone: string
  submitter: string
  submit_time: string
  status: string
}

const auditSuppliers = reactive<AuditSupplier[]>([
  { id: 1, name: '北京智造科技有限公司', tax_no: '91110108MA01982X', type: '生产制造业', contact: '王业务', phone: '13800138000', submitter: 'Yangwei', submit_time: '2026-09-16 09:20:00', status: 'PENDING_REVIEW' },
  { id: 3, name: '深圳迅捷物流有限公司', tax_no: '914403003009821Z', type: '服务外包商', contact: '张总', phone: '13700009999', submitter: 'Yangwei', submit_time: '2026-09-15 14:00:00', status: 'NORMAL' },
  { id: 2, name: '上海华联部件有限公司', tax_no: '913100006782103Y', type: '生产制造业', contact: '李经理', phone: '13911112222', submitter: 'Yangwei', submit_time: '2026-09-14 11:00:00', status: 'RETURNED' },
])

const searchForm = reactive({ name: '', status: '' })

/** 审核列表状态筛选项（value=枚举码，label=中文）。 */
const statusFilterOptions = ['PENDING_REVIEW', 'NORMAL', 'RETURNED'] as const

const filteredSuppliers = () =>
  auditSuppliers.filter((s) => {
    const matchName = !searchForm.name || s.name.includes(searchForm.name) || s.tax_no.includes(searchForm.name)
    const matchStatus = !searchForm.status || s.status === searchForm.status
    return matchName && matchStatus
  })

const currentSupplier = ref<AuditSupplier | null>(null)

/** 审核签署表单（提交动作待下一步对接真实接口）。 */
const auditForm = reactive({ result: 'pass', comment: '' })

function openAuditPage(row: AuditSupplier) {
  currentSupplier.value = { ...row }
  auditForm.result = 'pass'
  auditForm.comment = ''
  navigateTo('audit-detail')
}

/** 业务写操作保留占位，下一步对接审核签署接口。 */
function handlePlaceholder(featureName: string) {
  ElMessage.info(`${featureName}开发中（下一步实现）`)
}
</script>

<template>
  <el-container class="admin-layout">
    <!-- 顶栏导航 -->
    <el-header height="60px" class="header-bar">
      <div class="logo">SRM 供应商管理系统 | 审计与合规控制台</div>
      <div class="user-info">
        <el-tag type="warning" size="small">审计员 (Auditor)</el-tag>
        <span>欢迎您，{{ userStore.userInfo?.real_name ?? 'Auditor_01' }} (合规审计部)</span>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 侧边栏菜单 -->
      <el-aside width="220px" class="aside-menu">
        <el-menu
          :default-active="activeMenu"
          background-color="#1a1b26"
          text-color="#a9b1d6"
          active-text-color="#67C23A"
          @select="handleMenuSelect"
        >
          <el-menu-item index="audit-list">
            <el-icon><Select /></el-icon>
            <span>供应商准入资质审核</span>
          </el-menu-item>
          <el-menu-item index="status-decision-list">
            <el-icon><SwitchButton /></el-icon>
            <span>状态变更终审决策</span>
          </el-menu-item>
          <el-menu-item index="perf-review-list">
            <el-icon><Star /></el-icon>
            <span>绩效评价复核</span>
          </el-menu-item>
          <el-menu-item index="log-list">
            <el-icon><Document /></el-icon>
            <span>操作与审批日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容展示区域 -->
      <el-main class="main-content">
        <div class="page-card">
          <!-- 1. 准入资质审核列表（原型演示数据，审核签署待实现） -->
          <template v-if="currentView === 'audit-list'">
            <div class="page-header">
              <span class="page-title">待审核准入供应商申请</span>
            </div>
            <el-form :inline="true" class="filter-form">
              <el-form-item label="供应商名称">
                <el-input v-model="searchForm.name" placeholder="输入名称或税号" clearable style="width: 200px" />
              </el-form-item>
              <el-form-item label="状态">
                <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 140px">
                  <el-option v-for="statusCode in statusFilterOptions" :key="statusCode" :label="getStatusLabel(statusCode)" :value="statusCode" />
                </el-select>
              </el-form-item>
            </el-form>
            <el-table :data="filteredSuppliers()" border stripe style="width: 100%">
              <el-table-column prop="name" label="供应商名称" min-width="180" />
              <el-table-column prop="tax_no" label="统一社会信用代码/税号" width="190" />
              <el-table-column prop="type" label="供应商类型" width="130" />
              <el-table-column prop="submitter" label="提交业务员" width="120" />
              <el-table-column prop="submit_time" label="提交时间" width="160" />
              <el-table-column prop="status" label="当前状态" width="120">
                <template #default="scope">
                  <el-tag :type="getStatusTagType(scope.row.status)">{{ getStatusLabel(scope.row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="合规审核" width="160" fixed="right">
                <template #default="scope">
                  <el-button size="small" type="success" @click="openAuditPage(scope.row)">进行准入审核</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-- 2. 执行资质审核详情（嵌入历史审批与追溯日志） -->
          <template v-else-if="currentView === 'audit-detail' && currentSupplier">
            <div class="page-header">
              <span class="page-title">供应商准入合规审核 - {{ currentSupplier.name }}</span>
              <el-button @click="navigateTo('audit-list')">返回待审列表</el-button>
            </div>

            <div class="detail-section">
              <h4>企业提交基本信息校验</h4>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="企业全称">{{ currentSupplier.name }}</el-descriptions-item>
                <el-descriptions-item label="统一社会信用代码/税号">{{ currentSupplier.tax_no }}</el-descriptions-item>
                <el-descriptions-item label="企业类型">{{ currentSupplier.type }}</el-descriptions-item>
                <el-descriptions-item label="联系人/电话">{{ currentSupplier.contact }} ({{ currentSupplier.phone }})</el-descriptions-item>
                <el-descriptions-item label="提交业务员">{{ currentSupplier.submitter }}</el-descriptions-item>
                <el-descriptions-item label="提交时间">{{ currentSupplier.submit_time }}</el-descriptions-item>
                <el-descriptions-item label="营业执照原件" :span="2">
                  <el-space>
                    <el-tag type="success">【文件已核验】营业执照正本_扫描件.pdf</el-tag>
                    <el-button type="primary" link @click="handlePlaceholder('文件预览')">预览文件</el-button>
                  </el-space>
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <!-- 仅待审核状态显示审批表单（提交签署下一步实现） -->
            <div v-if="currentSupplier.status === 'PENDING_REVIEW'" class="audit-box">
              <h4 class="audit-box-title">审计员签署审核结论</h4>
              <el-form label-width="120px">
                <el-form-item label="审核结果" required>
                  <el-radio-group v-model="auditForm.result">
                    <el-radio value="pass">审核通过 (准入为正式合作供应商)</el-radio>
                    <el-radio value="reject">审核驳回 (退回给业务员修正)</el-radio>
                  </el-radio-group>
                </el-form-item>
                <el-form-item label="审计驳回/审批意见" :required="auditForm.result === 'reject'">
                  <el-input
                    v-model="auditForm.comment"
                    type="textarea"
                    :rows="3"
                    placeholder="若选择驳回，请务必填写具体的退回修改原因（如：营业执照不清晰/税号不匹配）..."
                  />
                </el-form-item>
                <el-form-item>
                  <el-button type="success" @click="handlePlaceholder('审核签署')">提交审核签署结果</el-button>
                  <el-button @click="navigateTo('audit-list')">取消</el-button>
                </el-form-item>
              </el-form>
            </div>

            <!-- 本步核心：历史审批与追溯日志 -->
            <div class="detail-section" style="margin-top: 24px">
              <h4>历史审批与追溯日志</h4>
              <ReviewHistoryLogTable :supplier-id="currentSupplier.id" :supplier-name="currentSupplier.name" />
            </div>
          </template>

          <!-- 3. 状态变更终审决策（占位） -->
          <template v-else-if="currentView === 'status-decision-list'">
            <div class="page-header">
              <span class="page-title">供应商停用 / 淘汰终审决策</span>
            </div>
            <el-empty description="状态变更终审决策模块开发中（下一步实现）" />
          </template>

          <!-- 4. 绩效评价复核（占位） -->
          <template v-else-if="currentView === 'perf-review-list'">
            <div class="page-header">
              <span class="page-title">业务员季度绩效打分复核</span>
            </div>
            <el-empty description="绩效评价复核模块开发中（下一步实现）" />
          </template>

          <!-- 5. 操作与审批日志（本步实现） -->
          <ReviewLogTable v-else-if="currentView === 'log-list'" />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.admin-layout {
  height: 100vh;
}
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #2b2d42;
  color: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.15);
  z-index: 100;
}
.logo {
  font-size: 18px;
  font-weight: bold;
  letter-spacing: 0.5px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #edf2f4;
}
.body-container {
  overflow: hidden;
}
.aside-menu {
  background-color: #1a1b26;
  overflow-y: auto;
}
.aside-menu :deep(.el-menu) {
  border-right: none;
}
.main-content {
  background-color: #f0f2f5;
  padding: 20px;
  overflow-y: auto;
}
.page-card {
  background: #fff;
  padding: 24px;
  border-radius: 4px;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
  min-height: calc(100vh - 100px);
}
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
.detail-section {
  margin-bottom: 24px;
}
.detail-section h4 {
  border-left: 4px solid #67c23a;
  padding-left: 8px;
  margin-bottom: 12px;
  color: #303133;
}
.audit-box {
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  padding: 20px;
  margin-top: 20px;
}
.audit-box-title {
  margin-top: 0;
  color: #e6a23c;
}
</style>
