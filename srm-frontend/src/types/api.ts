/**
 * 后端统一响应体（docs/api-spec.md 第 0 节）。
 * code=0 表示成功；非 0 见错误码表（第 8 节）。
 */
export interface ApiResponse<T> {
  code: number
  message: string
  data: T | null
}

/** 分页响应 data（docs/api-spec.md 第 0 节）。 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  page_size: number
}
