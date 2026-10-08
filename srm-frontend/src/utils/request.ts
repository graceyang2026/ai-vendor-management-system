import axios, { AxiosError, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import type { ApiResponse } from '@/types/api'

/** 后端统一响应体中非 0 code 对应的业务错误。 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

const service = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 15000,
})

// 请求拦截器：除登录外统一附加 Authorization: Bearer <token>（docs/api-spec.md 第 0 节）
service.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

/** token 失效：清空本地登录态并回登录页（40101）。 */
function handleUnauthorized() {
  const userStore = useUserStore()
  userStore.reset()
  if (router.currentRoute.value.path !== '/login') {
    ElMessage.warning('登录已过期，请重新登录')
    router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

// 响应拦截器：解包 ApiResponse；code 非 0 视为业务失败并抛出 ApiError
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data
    if (body.code === 0) {
      return response
    }
    if (body.code === 40101) {
      handleUnauthorized()
    }
    return Promise.reject(new ApiError(body.code, body.message || '请求失败'))
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    // HTTP 层错误：后端 GlobalExceptionHandler 兜底返回的仍是 ApiResponse 结构
    const body = error.response?.data
    if (body?.code === 40101) {
      handleUnauthorized()
    }
    const code = body?.code ?? 50001
    const message = body?.message || error.message || '网络异常，请稍后重试'
    return Promise.reject(new ApiError(code, message))
  },
)

const request = {
  get<T>(url: string, params?: object): Promise<ApiResponse<T>> {
    return service.get<ApiResponse<T>>(url, { params }).then((res) => res.data)
  },
  post<T>(url: string, data?: object): Promise<ApiResponse<T>> {
    return service.post<ApiResponse<T>>(url, data).then((res) => res.data)
  },
  put<T>(url: string, data?: object): Promise<ApiResponse<T>> {
    return service.put<ApiResponse<T>>(url, data).then((res) => res.data)
  },
  delete<T>(url: string, params?: object): Promise<ApiResponse<T>> {
    return service.delete<ApiResponse<T>>(url, { params }).then((res) => res.data)
  },
}

export default request
