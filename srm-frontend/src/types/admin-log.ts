/**
 * 系统操作日志（管理员 ADMIN）类型定义。
 * 复用后端统一契约 AuditLogRecord（docs/api-spec.md 第 5 节）：管理动作的业务对象是用户账号，
 * 故 entity_type='USER'、entity_id=目标账号 ID、comment=详细变动说明；
 * 原型自定义的 action_type / target_object / detail 三个字段后端不存在，已按真实契约收敛。
 *
 * 动作码口径（权威源：srm-backend com.srm.core.audit.AuditAction，共 11 码，用户域四码均已定义）：
 * CREATE_USER / UPDATE_USER / ENABLE_USER / DISABLE_USER。前端曾自造的角色调整占位动作码已彻底删除
 * —— 后端把"资料修改 + 角色调整"统一落 UPDATE_USER，变更明细写 comment
 * （docs/用户管理接口规格增补稿.md §3.4 + docs/api-spec.md 第 5/6 节）。
 * 本文件只保留类型别名；动作筛选选项与中文/颜色文案统一由 @/constants/auditDictionary 提供
 * （ADMIN_ACTION_OPTIONS / ACTION_LABEL_CN / ACTION_TAG_TYPE），页面不得另立映射。
 */

import type { AuditLogRecord, ReviewLogQueryParams } from '@/types/audit-log'

/** 管理日志项：与通用记录同构（entity_type=USER）。 */
export type AdminLogItem = AuditLogRecord

/** 查询参数（同一后端端点，entity_type 固定 USER）。 */
export type AdminLogQueryParams = ReviewLogQueryParams
