import request from '@/utils/request'
import type { ApiResponse, PageResult } from '@/types/api'
import type {
  UserCreatePayload,
  UserItem,
  UserQueryParams,
  UserStatusPayload,
  UserUpdatePayload,
} from '@/types/user'

/**
 * 用户账号管理接口（docs/api-spec.md 第 6 节，后端 UserController 已全部实现）。
 * 端点均 @PreAuthorize("hasRole('ADMIN')")，非 ADMIN 访问 → 40301（HTTP 403）。
 * 字段严格按后端契约：GET 仅分页无关键词；PUT 严禁携带 username/password（后端非 null 即 40001）。
 */

/** GET /users —— 分页查询用户列表（后端 page_size 默认 20；无关键词/状态筛选参数）。 */
export function getUserListApi(params: UserQueryParams = {}): Promise<ApiResponse<PageResult<UserItem>>> {
  return request.get<PageResult<UserItem>>('/users', { ...params })
}

/** POST /users —— 新增用户（username/password/real_name/role 均必填；重名 → 40903）。 */
export function createUserApi(data: UserCreatePayload): Promise<ApiResponse<UserItem>> {
  return request.post<UserItem>('/users', data)
}

/** PUT /users/{id} —— 修改资料与角色（仅 real_name/role；改自己角色 → 40302）。 */
export function updateUserApi(id: number, data: UserUpdatePayload): Promise<ApiResponse<UserItem>> {
  return request.put<UserItem>(`/users/${id}`, data)
}

/** PATCH /users/{id}/status —— 启用/停用（body { enabled }；停用自己 → 40302；重复设置幂等）。 */
export function updateUserStatusApi(id: number, data: UserStatusPayload): Promise<ApiResponse<null>> {
  return request.patch<null>(`/users/${id}/status`, data)
}
