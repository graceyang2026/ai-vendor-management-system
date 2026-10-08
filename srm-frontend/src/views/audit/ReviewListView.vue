<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

async function handleLogout() {
  await userStore.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span><b>准入与绩效审核（AUDITOR）</b></span>
          <div class="page-user">
            <span class="welcome">欢迎您，<b>{{ userStore.userInfo?.real_name }}</b>（{{ userStore.role }}）</span>
            <el-button type="danger" size="small" plain @click="handleLogout">退出登录</el-button>
          </div>
        </div>
      </template>
      <el-alert
        title="AUDITOR 权限边界：负责准入审核、驳回填写、绩效复核确认及停用/淘汰的最终决策。"
        type="warning"
        show-icon
        :closable="false"
        style="margin-bottom: 20px"
      />
      <el-empty description="审核列表功能开发中" />
    </el-card>
  </div>
</template>

<style scoped>
.page-container {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.page-user {
  display: flex;
  align-items: center;
  gap: 15px;
}
.welcome {
  font-size: 14px;
}
</style>
