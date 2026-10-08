import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import type { RoleType } from '@/types/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 允许访问的角色列表；未配置时仅要求登录。 */
    roles?: RoleType[]
    /** 页面标题。 */
    title?: string
  }
}

/** 各角色登录后的首页（与原型三角色视图一一对应）。 */
export const ROLE_HOME: Record<RoleType, string> = {
  ADMIN: '/system/user-list',
  STAFF: '/business/supplier-list',
  AUDITOR: '/audit/review-list',
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/login' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
      meta: { title: '登录' },
    },
    {
      path: '/system/user-list',
      name: 'system-user-list',
      component: () => import('@/views/system/UserListView.vue'),
      meta: { title: '用户管理', roles: ['ADMIN'] },
    },
    {
      path: '/business/supplier-list',
      name: 'business-supplier-list',
      component: () => import('@/views/business/SupplierListView.vue'),
      meta: { title: '供应商列表', roles: ['STAFF'] },
    },
    {
      path: '/audit/review-list',
      name: 'audit-review-list',
      component: () => import('@/views/audit/ReviewListView.vue'),
      meta: { title: '审核列表', roles: ['AUDITOR'] },
    },
    { path: '/:pathMatch(.*)*', redirect: '/login' },
  ],
})

// 全局前置守卫：未登录强制回登录页；已登录但角色与 meta.roles 不符时拦截并提示
router.beforeEach((to) => {
  const userStore = useUserStore()

  // 未登录：仅放行登录页
  if (!userStore.isLoggedIn) {
    return to.path === '/login' ? true : { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录访问登录页：直接跳回角色首页
  if (to.path === '/login') {
    return ROLE_HOME[userStore.role as RoleType] || '/login'
  }

  // 角色与页面 meta.roles 不符：拦截并提示，跳回当前角色首页
  const requiredRoles = to.meta.roles
  if (requiredRoles && !requiredRoles.includes(userStore.role as RoleType)) {
    ElMessage.warning(`当前角色（${userStore.role}）无权访问该页面`)
    return ROLE_HOME[userStore.role as RoleType] || '/login'
  }

  return true
})

router.afterEach((to) => {
  const title = to.meta.title
  document.title = title ? `${title} - SRM 供应商管理系统` : 'SRM 供应商管理系统'
})

export default router
