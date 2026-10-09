/**
 * 底座统一错误码对照表（docs/api-spec.md 第 8 节，与后端 com.srm.core.common.ErrorCode 一一对应）。
 * tsconfig 开启了 erasableSyntaxOnly（禁用 enum），因此用 as const 对象表达。
 *
 * | code   | 枚举名                     | HTTP | 产出模块                              | 触发场景                                       |
 * |--------|----------------------------|------|---------------------------------------|------------------------------------------------|
 * | 0      | SUCCESS                    | 200  | 通用                                  | 一切正常响应                                   |
 * | 40001  | VALIDATION_FAILED          | 200  | GlobalExceptionHandler                | Bean Validation 失败（动态提取首个字段错误）   |
 * | 40101  | UNAUTHORIZED               | 401  | RestAuthenticationEntryPoint          | 无 token / token 失效访问受保护接口            |
 * | 40102  | LOGIN_FAILED               | 200  | AuthServiceImpl                       | 账号不存在/密码错误/账号停用（统一返回）       |
 * | 40301  | FORBIDDEN                  | 403  | RestAccessDeniedHandler               | @PreAuthorize 角色不匹配                       |
 * | 40302  | STATUS_NOT_ALLOWED         | 200  | 业务层                                | 当前状态不允许该操作/仅限本人操作              |
 * | 40401  | NOT_FOUND                  | 200  | 业务层                                | 资源不存在                                     |
 * | 40901  | OPTIMISTIC_LOCK_CONFLICT   | 200  | MyBatis-Plus 乐观锁                   | version 不匹配，需重新拉取详情                 |
 * | 40902  | DUPLICATE_IN_PROGRESS      | 200  | 业务层                                | 重复的在途申请/评价单                          |
 * | 40903  | USERNAME_DUPLICATE           | 200  | UserServiceImpl                       | 新增用户账号已存在                             |
 * | 50001  | INTERNAL_ERROR             | 200  | GlobalExceptionHandler                | 服务器内部错误                                 |
 */
export const ERROR_CODE = {
  SUCCESS: 0,
  VALIDATION_FAILED: 40001,
  UNAUTHORIZED: 40101,
  LOGIN_FAILED: 40102,
  FORBIDDEN: 40301,
  STATUS_NOT_ALLOWED: 40302,
  NOT_FOUND: 40401,
  OPTIMISTIC_LOCK_CONFLICT: 40901,
  DUPLICATE_IN_PROGRESS: 40902,
  USERNAME_DUPLICATE: 40903,
  INTERNAL_ERROR: 50001,
} as const

export type ErrorCodeValue = (typeof ERROR_CODE)[keyof typeof ERROR_CODE]

/**
 * 兜底文案表：仅在**后端未返回 message** 时按 code 显示；
 * message 存在时一律透传后端文案（40001/40302 等是动态具体提示，前端不得覆盖）。
 */
export const ERROR_CODE_MESSAGE: Record<number, string> = {
  [ERROR_CODE.VALIDATION_FAILED]: '参数校验失败，请检查输入',
  [ERROR_CODE.UNAUTHORIZED]: '未登录或登录已过期',
  [ERROR_CODE.LOGIN_FAILED]: '用户名或密码错误',
  [ERROR_CODE.FORBIDDEN]: '无权限执行该操作',
  [ERROR_CODE.STATUS_NOT_ALLOWED]: '当前状态不允许该操作',
  [ERROR_CODE.NOT_FOUND]: '资源不存在',
  [ERROR_CODE.OPTIMISTIC_LOCK_CONFLICT]: '数据已被其他人修改，请刷新后重试',
  [ERROR_CODE.DUPLICATE_IN_PROGRESS]: '已存在在途的申请/评价单，请勿重复提交',
  [ERROR_CODE.USERNAME_DUPLICATE]: '账号已存在，请更换工号或账号',
  [ERROR_CODE.INTERNAL_ERROR]: '服务器内部错误，请稍后重试',
}
