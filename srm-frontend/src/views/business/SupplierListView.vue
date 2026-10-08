<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, Files, Star } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import SupplierLogTable from '@/components/business/SupplierLogTable.vue'
import SupplierHistoryLogTable from '@/components/business/SupplierHistoryLogTable.vue'
import { getSupplierStatusTagType } from '@/types/supplier-log'

/**
 * 业务员控制台（参考 docs/mockups/原型展示-采购员v1.1_待评审20260929.html）。
 * 红线：本页仅 STAFF 可访问，由路由守卫 meta.roles=['STAFF']（/business/supplier-list）强制拦截；
 * 日志视角为业务员维度（entity_type=SUPPLIER）。
 * 本步实现：操作与审批日志主视图 + 供应商详情档案追溯日志；
 * 新增/编辑、状态申请、绩效录入等业务功能保留占位，待下一步实现。
 */
type MenuKey = 'supplier-list' | 'perf-list' | 'log-list'
type ViewKey = MenuKey | 'supplier-detail'

/** 供应商演示行（字段对齐 docs/api-spec.md 第 2 节 SupplierResponse 子集，snake_case）。 */
interface SupplierDemoRow {
  id: number
  name: string
  tax_no: string
  type: string
  contact_name: string
  contact_phone: string
  contact_email?: string
  address?: string
  business_license_url?: string
  status: string
  risk_warning: boolean
}

/** 原型演示供应商档案（业务功能下一步对接真实接口后移除）。 */
const SUPPLIER_DEMO_LIST: SupplierDemoRow[] = [
  {
    id: 1,
    name: '北京智造科技有限公司',
    tax_no: '91110108MA01982X',
    type: '生产制造业',
    contact_name: '王业务',
    contact_phone: '13800138000',
    contact_email: 'wang@bm.com',
    address: '北京市海淀区科技园1号',
    business_license_url: '营业执照_智造.pdf',
    status: '草稿/待提交',
    risk_warning: false,
  },
  {
    id: 2,
    name: '上海华联部件有限公司',
    tax_no: '913100006782103Y',
    type: '生产制造业',
    contact_name: '李经理',
    contact_phone: '13911112222',
    contact_email: 'li@hualian.com',
    address: '上海市浦东新区张江高科',
    business_license_url: '营业执照_华联.pdf',
    status: '待修改',
    risk_warning: false,
  },
  {
    id: 3,
    name: '深圳迅捷物流有限公司',
    tax_no: '914403003009821Z',
    type: '服务外包商',
    contact_name: '张总',
    contact_phone: '13700009999',
    contact_email: 'service@xunjie.com',
    address: '深圳市南山区',
    business_license_url: '营业执照_迅捷.pdf',
    status: '正常/合作中',
    risk_warning: false,
  },
  {
    id: 4,
    name: '沧州重工机械配件厂',
    tax_no: '91130900MA07YY88',
    type: '生产制造业',
    contact_name: '赵厂长',
    contact_phone: '13699887766',
    contact_email: 'zhao@czzg.com',
    address: '河北省沧州市开发区',
    business_license_url: '营业执照_沧州重工.pdf',
    status: '停用',
    risk_warning: true,
  },
]

const router = useRouter()
const userStore = useUserStore()

const activeMenu = ref<MenuKey>('supplier-list')
const currentView = ref<ViewKey>('supplier-list')
const currentSupplier = ref<SupplierDemoRow>(SUPPLIER_DEMO_LIST[0])

const searchForm = reactive({ name: '', status: '' })
const supplierList = ref<SupplierDemoRow[]>([...SUPPLIER_DEMO_LIST])

const statusOptions = ['草稿/待提交', '待审核', '正常/合作中', '待修改', '停用', '淘汰']

/** 新增/编辑弹窗等档案写操作保留占位，下一步对接真实接口。 */
function handlePlaceholder(featureName: string) {
  ElMessage.info(`${featureName}功能开发中（下一步实现）`)
}

function handleMenuSelect(index: string) {
  activeMenu.value = index as MenuKey
  currentView.value = index as MenuKey
}

function handleSearch() {
  supplierList.value = SUPPLIER_DEMO_LIST.filter((supplier) => {
    const matchName =
      !searchForm.name || supplier.name.includes(searchForm.name) || supplier.tax_no.includes(searchForm.name)
    const matchStatus = !searchForm.status || supplier.status === searchForm.status
    return matchName && matchStatus
  })
}

/** 详情/日志：进入供应商全生命周期档案视图（嵌入追溯日志子组件）。 */
function viewDetail(row: SupplierDemoRow) {
  currentSupplier.value = row
  currentView.value = 'supplier-detail'
}

function backToList() {
  currentView.value = 'supplier-list'
  activeMenu.value = 'supplier-list'
}

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <el-container class="business-layout">
    <!-- 顶栏 -->
    <el-header height="60px" class="header-bar">
      <div class="logo">SRM 供应商管理系统 | 业务员控制台</div>
      <div class="user-info">
        <el-tag type="success" size="small">业务员 (Staff)</el-tag>
        <span>欢迎您，{{ userStore.userInfo?.real_name ?? 'Yangwei' }}（{{ userStore.userInfo?.username }}）</span>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 侧边栏菜单 -->
      <el-aside width="220px" class="aside-menu">
        <el-menu
          :default-active="activeMenu"
          background-color="#1f2d3d"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
          @select="handleMenuSelect"
        >
          <el-menu-item index="supplier-list">
            <el-icon><Files /></el-icon>
            <span>供应商档案管理</span>
          </el-menu-item>
          <el-menu-item index="perf-list">
            <el-icon><Star /></el-icon>
            <span>量化绩效评价录入</span>
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
          <!-- 1. 供应商档案列表 -->
          <template v-if="currentView === 'supplier-list'">
            <div class="page-header">
              <span class="page-title">供应商档案与生命周期管理</span>
              <el-button type="primary" @click="handlePlaceholder('新增供应商档案')">+ 新增供应商档案</el-button>
            </div>

            <el-form :inline="true" :model="searchForm" style="margin-bottom: 10px">
              <el-form-item label="供应商名称">
                <el-input v-model="searchForm.name" placeholder="请输入名称或税号" clearable />
              </el-form-item>
              <el-form-item label="合作状态">
                <el-select v-model="searchForm.status" placeholder="全部状态" clearable style="width: 160px">
                  <el-option v-for="statusOption in statusOptions" :key="statusOption" :label="statusOption" :value="statusOption" />
                </el-select>
              </el-form-item>
              <el-form-item>
                <el-button type="primary" @click="handleSearch">查询</el-button>
              </el-form-item>
            </el-form>

            <el-table :data="supplierList" border style="width: 100%">
              <el-table-column prop="name" label="供应商名称" min-width="180" />
              <el-table-column prop="tax_no" label="统一社会信用代码/税号" width="190" />
              <el-table-column prop="type" label="供应商类型" width="130" />
              <el-table-column prop="contact_name" label="联系人" width="110" />
              <el-table-column prop="contact_phone" label="联系电话" width="130" />
              <el-table-column prop="status" label="当前状态与风险标记" width="180">
                <template #default="scope">
                  <el-tag :type="getSupplierStatusTagType(scope.row.status)">{{ scope.row.status }}</el-tag>
                  <el-tag v-if="scope.row.risk_warning" type="danger" effect="dark" style="margin-left: 5px">风险预警</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="业务操作" width="120" fixed="right">
                <template #default="scope">
                  <el-button size="small" type="primary" link @click="viewDetail(scope.row)">详情/日志</el-button>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-- 2. 供应商档案详情与特定档案追溯日志 -->
          <template v-else-if="currentView === 'supplier-detail'">
            <div class="page-header">
              <span class="page-title">供应商全生命周期档案 - {{ currentSupplier.name }}</span>
              <el-button @click="backToList">返回列表</el-button>
            </div>

            <div class="detail-section">
              <h4>基础与资质信息</h4>
              <el-descriptions :column="2" border>
                <el-descriptions-item label="供应商名称">{{ currentSupplier.name }}</el-descriptions-item>
                <el-descriptions-item label="统一社会信用代码/税号">{{ currentSupplier.tax_no }}</el-descriptions-item>
                <el-descriptions-item label="供应商类型">{{ currentSupplier.type }}</el-descriptions-item>
                <el-descriptions-item label="当前状态与标记">
                  <el-tag :type="getSupplierStatusTagType(currentSupplier.status)">{{ currentSupplier.status }}</el-tag>
                  <el-tag v-if="currentSupplier.risk_warning" type="danger" effect="dark" style="margin-left: 5px">
                    风险预警 (硬阻断开启)
                  </el-tag>
                </el-descriptions-item>
                <el-descriptions-item label="联系人">{{ currentSupplier.contact_name }} ({{ currentSupplier.contact_phone }})</el-descriptions-item>
                <el-descriptions-item label="联系邮箱">{{ currentSupplier.contact_email || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="企业地址" :span="2">{{ currentSupplier.address || '未填写' }}</el-descriptions-item>
                <el-descriptions-item label="营业执照文件" :span="2">
                  <el-link type="primary">{{ currentSupplier.business_license_url || '营业执照正本_扫描件.pdf' }}</el-link>
                </el-descriptions-item>
              </el-descriptions>
            </div>

            <div class="detail-section">
              <h4>当前档案追溯日志历史</h4>
              <SupplierHistoryLogTable
                :supplier-id="currentSupplier.id"
                :supplier-name="currentSupplier.name"
              />
            </div>
          </template>

          <!-- 3. 量化绩效评价录入（占位，下一步实现） -->
          <template v-else-if="currentView === 'perf-list'">
            <div class="page-header">
              <span class="page-title">供应商日常绩效评价记录 (历史留存可追溯)</span>
            </div>
            <el-alert
              title="防篡改铁律：业务员仅录入客观履约事实明细，得分与评级由后端算分引擎硬编码计算，前端禁止提交或覆盖分数/评级。"
              type="warning"
              show-icon
              :closable="false"
              style="margin-bottom: 20px"
            />
            <el-empty description="量化绩效评价录入模块开发中（下一步实现）" />
          </template>

          <!-- 4. 操作与审批日志（本步实现） -->
          <SupplierLogTable v-else-if="currentView === 'log-list'" />
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.business-layout {
  height: 100vh;
}
.header-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #1f2d3d;
  color: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.1);
  z-index: 100;
}
.logo {
  font-size: 18px;
  font-weight: bold;
  letter-spacing: 1px;
}
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  color: #e0e6ed;
}
.body-container {
  overflow: hidden;
}
.aside-menu {
  background-color: #1f2d3d;
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
.detail-section {
  margin-bottom: 24px;
}
.detail-section h4 {
  margin: 0 0 12px;
  color: #303133;
  border-left: 4px solid #409eff;
  padding-left: 8px;
}
</style>
