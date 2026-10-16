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

/* ---------------------------------------------------------------- 二进制导出（blob）分流支撑
 * 唯一绕过「HTTP 200 + JSON ApiResponse」约定的端点是 GET /suppliers/export（需求定稿 2026-10-16，
 * 后端 SupplierController.XLSX_CONTENT_TYPE 实文）。为它新增的三条判定helper：
 * 正常返回是 xlsx 二进制流（Content-Type=spreadsheetml.sheet，绝不能当 JSON 解析否则把文件内容判成错误）；
 * 失败仍由全局兜底回既有 JSON ApiResponse（40001 随 HTTP 200；40301→HTTP 403、40101→HTTP 401，
 * 二者在 axios 里落进 error 回调且 body 被 axios 存成 Blob）。故拦截器须按 Content-Type 把两者分流。 */

/** xlsx（OOXML）标准 MIME —— 判定"这是正常文件、不是错误"用（与后端 XLSX_CONTENT_TYPE 同值）。 */
const SPREADSHEET_CONTENT_TYPE = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

/** 读取响应头值（axios AxiosHeaders.get，兼容大小写），无则空串。 */
function readHeader(headers: AxiosResponse['headers'] | undefined, name: string): string {
  if (!headers) return ''
  const raw = typeof headers.get === 'function' ? headers.get(name) : undefined
  return raw == null ? '' : String(raw)
}

/**
 * 把"以 blob 承载的 JSON 错误体"读回并解析为统一 ApiResponse；非 JSON 或解析失败返回 null。
 * 仅在确认响应 Content-Type 为 application/json 时调用，避免把二进制文件当文本硬解。
 */
async function readBlobAsApiResponse(blob: Blob): Promise<ApiResponse<unknown> | null> {
  try {
    const text = await blob.text()
    const parsed: unknown = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && 'code' in parsed) {
      return parsed as ApiResponse<unknown>
    }
    return null
  } catch {
    return null
  }
}

/**
 * 从 Content-Disposition 解析下载文件名，兼容 `filename=` 与 `filename*=UTF-8''`（RFC 5987）。
 * 无匹配返回 undefined，由调用方回退默认名。
 */
export function parseFileNameFromDisposition(disposition: string): string | undefined {
  if (!disposition) return undefined
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)
  if (encoded?.[1]) {
    try {
      return decodeURIComponent(encoded[1].trim())
    } catch {
      // 非法百分号编码：继续尝试普通 filename=
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)
  return plain?.[1]?.trim()
}

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

/**
 * 统一把 blob 请求携带的 JSON 错误体转成 ApiError（成功回调里的 HTTP 200 + JSON 分支与
 * 失败回调里的 HTTP 4xx + JSON 分支共用）。读不到 JSON 时按网络异常兜底，绝不吞掉真实错误。
 */
async function rejectBlobJsonError(
  blob: Blob,
  headers: AxiosResponse['headers'] | undefined,
): Promise<never> {
  if (readHeader(headers, 'content-type').includes('application/json')) {
    const body = await readBlobAsApiResponse(blob)
    const code = body?.code ?? ERROR_CODE.INTERNAL_ERROR
    if (code === ERROR_CODE.UNAUTHORIZED) {
      handleUnauthorized()
    }
    throw new ApiError(code, resolveErrorMessage(code, body?.message))
  }
  // 非 spreadsheet、又非可解析 JSON 的意外二进制响应：判为导出失败，不透传底层英文错误
  throw new ApiError(ERROR_CODE.INTERNAL_ERROR, '导出失败，请稍后重试')
}

// 响应拦截器：解包 ApiResponse；code 非 0 视为业务失败并抛出 ApiError
service.interceptors.response.use(
  async (response: AxiosResponse<ApiResponse<unknown> | Blob>) => {
    // blob 通道（导出等二进制下载）：先按 Content-Type 分流，正常文件原样透传整个 response 供上层取头 + 落盘。
    const data = response.data
    if (response.config?.responseType === 'blob' && data instanceof Blob) {
      const contentType = readHeader(response.headers, 'content-type')
      if (
        contentType.includes(SPREADSHEET_CONTENT_TYPE) ||
        contentType.includes('spreadsheet') ||
        contentType.includes('octet-stream')
      ) {
        // 正常的 xlsx 文件流：绝不能按 JSON 解析，直接返回完整 AxiosResponse（headers 含文件名）
        return response
      }
      // 正常约定外的响应（后端把失败/异常回成了 JSON ApiResponse，随 HTTP 200 一起到达此处）→ 读回落 ApiError 口径
      return rejectBlobJsonError(data, response.headers)
    }

    const body = data as ApiResponse<unknown>
    if (body.code === ERROR_CODE.SUCCESS) {
      return response
    }
    // 40001/40102/40302/40901 等业务 code 随 HTTP 200 返回，按 body.code 判断而非 HTTP 状态码
    if (body.code === ERROR_CODE.UNAUTHORIZED) {
      handleUnauthorized()
    }
    return Promise.reject(new ApiError(body.code, resolveErrorMessage(body.code, body.message)))
  },
  async (error: AxiosError<ApiResponse<unknown> | Blob>) => {
    // HTTP 层错误（40101→HTTP 401、40301→HTTP 403）：响应体仍是 ApiResponse 结构
    const raw = error.response?.data
    // blob 请求遭遇 HTTP 4xx/5xx：axios 已把 JSON 错误体存成 Blob，需读回并按既有 code 口径分流
    if (error.config?.responseType === 'blob' && raw instanceof Blob) {
      return rejectBlobJsonError(raw, error.response?.headers)
    }
    const body = raw as ApiResponse<unknown> | undefined
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

  /** multipart/form-data 专用（§1.6 文件上传；axios 对 FormData 自动设置 Content-Type boundary，不得手工指定） */
  postForm<T>(url: string, formData: FormData): Promise<ApiResponse<T>> {
    return service.post<ApiResponse<T>>(url, formData).then((res) => res.data)
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

  /**
   * 二进制下载专用（GET + responseType=blob；GET /suppliers/export 导出 xlsx 等）。
   * 成功：拦截器原样透传整个 AxiosResponse，返回 blob + Content-Disposition 里的文件名（无则 undefined）。
   * 失败（JSON 错误体）：由上方 blob 分流抛出 ApiError，调用方 catch 走既有错误提示口径。
   */
  async downloadBlob(
    url: string,
    params?: object,
  ): Promise<{ blob: Blob; fileName?: string }> {
    const res = await service.get<Blob>(url, { params, responseType: 'blob' })
    return {
      blob: res.data,
      fileName: parseFileNameFromDisposition(readHeader(res.headers, 'content-disposition')),
    }
  },
}

export default request
