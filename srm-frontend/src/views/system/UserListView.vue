<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Document, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import AdminLogView from '@/components/admin/AdminLogView.vue'
import UserAccountView from '@/components/admin/UserAccountView.vue'

/**
 * 系统管理员控制台（参考 docs/mockups/原型展示-系统管理员v1.1_待评审20260917.html）。
 * 红线：本页仅 ADMIN 可访问，由路由守卫 meta.roles=['ADMIN']（/system/user-list）强制拦截；
 * 左侧菜单在 用户账号管理 / 系统操作日志 间切换主内容区（角色与权限维护不列入导航）。
 */
const router = useRouter()
const userStore = useUserStore()

type MenuKey = 'user-list' | 'log-list'

const activeMenu = ref<MenuKey>('user-list')

function handleMenuSelect(index: string) {
  activeMenu.value = index as MenuKey
}

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <el-container class="admin-layout">
    <!-- 顶栏导航 -->
    <el-header height="60px" class="header-bar">
      <div class="logo">SRM 供应商管理系统 | 管理后台</div>
      <div class="user-info">
        <el-tag type="danger" size="small">系统管理员 (Admin)</el-tag>
        <span>欢迎您，{{ userStore.userInfo?.real_name ?? '超级管理员' }}</span>
        <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- 侧边栏菜单 -->
      <el-aside width="220px" class="aside-menu">
        <el-menu
          :default-active="activeMenu"
          background-color="#304156"
          text-color="#bfcbd9"
          active-text-color="#409EFF"
          @select="handleMenuSelect"
        >
          <el-menu-item index="user-list">
            <el-icon><User /></el-icon>
            <span>用户账号管理</span>
          </el-menu-item>
          <el-menu-item index="log-list">
            <el-icon><Document /></el-icon>
            <span>系统操作日志</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- 主内容展示区域 -->
      <el-main class="main-content">
        <div class="page-card">
          <!-- 1. 用户账号管理（本步实现：列表 / 新增 / 编辑角色 / 启停） -->
          <template v-if="activeMenu === 'user-list'">
            <el-alert
              title="ADMIN 权限边界：负责用户账号管理与角色分配，原则上强行隔离，不参与任何供应商业务数据的增删改查及审批。"
              type="info"
              show-icon
              :closable="false"
              style="margin-bottom: 20px"
            />
            <UserAccountView />
          </template>

          <!-- 2. 系统操作日志 -->
          <AdminLogView v-else-if="activeMenu === 'log-list'" />
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
  background-color: #304156;
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
</style>
