package com.srm.core.audit;

/**
 * 审计日志业务对象类型（docs/api-spec.md 第 5 节 AuditLogResponse.entity_type）。
 */
public enum EntityType {

    SUPPLIER,
    PERFORMANCE_EVALUATION,
    PERFORMANCE_EVALUATION_DRAFT,
    LIFECYCLE_REQUEST
}
