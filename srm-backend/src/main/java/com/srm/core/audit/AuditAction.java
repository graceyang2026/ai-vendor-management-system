package com.srm.core.audit;

/**
 * 审计操作类型常量（docs/api-spec.md 第 5 节注释，完整枚举见数据库设计文档第 8 节）。
 * 业务模块（提交/审核/复核/决策）与管理员用户管理（创建/更新/启用/停用用户）调用 AuditLogService.record 时引用本枚举 name()。
 */
public enum AuditAction {

    SUBMIT,
    AUDIT_APPROVE,
    AUDIT_REJECT,
    REVIEW_APPROVE,
    REVIEW_REJECT,
    DECISION_APPROVE,
    DECISION_REJECT,
    // 管理员用户管理动作（entity_type=USER）
    CREATE_USER,
    UPDATE_USER,
    ENABLE_USER,
    DISABLE_USER
}
