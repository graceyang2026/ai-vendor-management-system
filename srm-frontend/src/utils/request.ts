import axios, { AxiosError, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useUserStore } from '@/stores/user'
import { ERROR_CODE, ERROR_CODE_MESSAGE } from '@/constants/errorCode'
import type { ApiResponse } from '@/types/api'

/** 后端统一响应体中非 0 code 对应的业务错误（全量 code 见 constants/errorCode.ts）。 */
export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.code = code
  }
}

/**
 * 提示文案策略：一律优先透传后端 message（40001/40302 等是动态具体文案，前端不得覆盖）；
 * 后端未给 message 时按 code 查兜底文案表。
 */
function resolveErrorMessage(code: number | undefined, backendMessage?: string): string {
  if (backendMessage) {
    return backendMessage
  }
  if (code !== undefined && ERROR_CODE_MESSAGE[code]) {
    return ERROR_CODE_MESSAGE[code]
  }
  return '请求失败，请稍后重试'
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

/**
 * 40101 UNAUTHORIZED（token 缺失/失效）：清空本地登录态并回登录页。
 * 注：40301 FORBIDDEN（角色不匹配）不走此分支 ——
 * 页面级角色拦截由路由守卫负责，接口级权限错误透传给调用方处理。
 */
function handleUnauthorized() {
  const userStore = useUserStore()
  userStore.reset()
  if (router.currentRoute.value.path !== '/login') {
    ElMessage.warning(ERROR_CODE_MESSAGE[ERROR_CODE.UNAUTHORIZED])
    router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
  }
}

// 响应拦截器：解包 ApiResponse；code 非 0 视为业务失败并抛出 ApiError
service.interceptors.response.use(
  (response: AxiosResponse<ApiResponse<unknown>>) => {
    const body = response.data
    if (body.code === ERROR_CODE.SUCCESS) {
      return response
    }
    // 40001/40102/40302/40901 等业务 code 随 HTTP 200 返回，按 body.code 判断而非 HTTP 状态码
    if (body.code === ERROR_CODE.UNAUTHORIZED) {
      handleUnauthorized()
    }
    return Promise.reject(new ApiError(body.code, resolveErrorMessage(body.code, body.message)))
  },
  (error: AxiosError<ApiResponse<unknown>>) => {
    // HTTP 层错误（40101→HTTP 401、40301→HTTP 403）：响应体仍是 ApiResponse 结构
    const body = error.response?.data
    const code = body?.code ?? ERROR_CODE.INTERNAL_ERROR
    if (code === ERROR_CODE.UNAUTHORIZED) {
      handleUnauthorized()
    }
    // 无响应体（断网/超时）时统一中文文案，避免透出不友好的英文底层错误
    const message = body ? resolveErrorMessage(body.code, body.message) : '网络异常，请稍后重试'
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
  patch<T>(url: string, data?: object): Promise<ApiResponse<T>> {
    return service.patch<ApiResponse<T>>(url, data).then((res) => res.data)
  },
  delete<T>(url: string, params?: object): Promise<ApiResponse<T>> {
    return service.delete<ApiResponse<T>>(url, { params }).then((res) => res.data)
  },
}

export default request
