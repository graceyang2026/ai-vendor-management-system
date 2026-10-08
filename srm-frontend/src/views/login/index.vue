<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { ROLE_HOME } from '@/router'
import { ERROR_CODE } from '@/constants/errorCode'
import { ApiError } from '@/utils/request'
import type { RoleType } from '@/types/auth'

/**
 * 原型角色映射（原型 key -> 项目红线枚举 RoleType），示例账号与后端播种一致（docs/api-spec.md 第 1 节）：
 * admin -> ADMIN（系统管理员，admin / Admin@123）
 * staff -> STAFF（业务人员，staff01 / Staff@123）
 * audit -> AUDITOR（审计人员，auditor01 / Auditor@123）
 */
type RoleKey = 'admin' | 'staff' | 'audit'

interface RolePreset {
  key: RoleKey
  label: string
  role: RoleType
  desc: string
  defaultAccount: string
  defaultPassword: string
  tagType: 'danger' | 'success' | 'warning'
}

const ROLE_PRESETS: RolePreset[] = [
  {
    key: 'admin',
    label: 'admin (系统管理员 - 权限管理)',
    role: 'ADMIN',
    desc: '系统管理员 (ADMIN)',
    defaultAccount: 'admin',
    defaultPassword: 'Admin@123',
    tagType: 'danger',
  },
  {
    key: 'staff',
    label: 'staff (业务人员 - 建档/提交/事实录入)',
    role: 'STAFF',
    desc: '业务人员 (STAFF)',
    defaultAccount: 'staff01',
    defaultPassword: 'Staff@123',
    tagType: 'success',
  },
  {
    key: 'audit',
    label: 'audit (审计人员 - 审批/绩效复核/淘汰)',
    role: 'AUDITOR',
    desc: '审计人员 (AUDITOR)',
    defaultAccount: 'auditor01',
    defaultPassword: 'Auditor@123',
    tagType: 'warning',
  },
]

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const loginForm = reactive({
  roleKey: 'staff' as RoleKey,
  username: 'staff01',
  password: 'Staff@123',
})

const currentPreset = computed(() => ROLE_PRESETS.find((p) => p.key === loginForm.roleKey) as RolePreset)

const rules: FormRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

/** 切换演示角色：联动切换当前角色（desc）、默认账号与演示密码。 */
function handleRoleChange(key: string) {
  const preset = ROLE_PRESETS.find((p) => p.key === key)
  if (!preset) {
    return
  }
  loginForm.username = preset.defaultAccount
  loginForm.password = preset.defaultPassword
}

/** 登录：校验通过后请求后端，按角色跳转到对应首页。 */
async function handleLogin() {
  if (!formRef.value) {
    return
  }
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    await userStore.login({
      username: loginForm.username,
      password: loginForm.password,
      role_type: currentPreset.value.role,
    })
    ElMessage.success(`登录成功，欢迎 ${userStore.userInfo?.real_name ?? loginForm.username}`)
    const redirect =
      typeof route.query.redirect === 'string' ? route.query.redirect : ROLE_HOME[userStore.role as RoleType]
    router.replace(redirect)
  } catch (error) {
    // 提示文案优先用后端 message（40102 统一返回"用户名或密码错误"，不暴露具体原因）
    ElMessage.error(error instanceof ApiError ? error.message : '登录失败，请稍后重试')
    // 40102 登录失败：清空密码便于重新输入
    if (error instanceof ApiError && error.code === ERROR_CODE.LOGIN_FAILED) {
      loginForm.password = ''
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <el-card class="login-card">
      <div class="login-title">🏢 SRM 供应商管理系统</div>
      <el-form
        ref="formRef"
        :model="loginForm"
        :rules="rules"
        label-position="top"
        @keyup.enter="handleLogin"
      >
        <el-form-item label="选择演示账号">
          <el-select v-model="loginForm.roleKey" style="width: 100%" @change="handleRoleChange">
            <el-option v-for="preset in ROLE_PRESETS" :key="preset.key" :label="preset.label" :value="preset.key" />
          </el-select>
        </el-form-item>
        <el-form-item label="登录账号" prop="username">
          <el-input v-model="loginForm.username" placeholder="请输入登录账号" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="loginForm.password"
            type="password"
            show-password
            placeholder="请输入密码"
          />
        </el-form-item>
        <el-form-item label="当前角色身份">
          <el-tag :type="currentPreset.tagType">{{ currentPreset.desc }}</el-tag>
        </el-form-item>
        <el-button type="primary" size="large" class="login-button" :loading="loading" @click="handleLogin">
          登 录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  /* 保留原型背景：深色渐变 */
  background: linear-gradient(135deg, #1f2937 0%, #111827 100%);
}
.login-card {
  width: 440px;
  border-radius: 8px;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.3);
}
.login-title {
  text-align: center;
  margin-bottom: 25px;
  font-size: 22px;
  color: #1f2937;
  font-weight: bold;
}
.login-button {
  width: 100%;
  margin-top: 15px;
}
</style>
