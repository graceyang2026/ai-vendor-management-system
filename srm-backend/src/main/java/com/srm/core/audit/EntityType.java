package com.srm.core.audit;

/**
 * 审计日志业务对象类型。
 * 契约源：docs/SRM 后端数据库表设计.md §8 entity_type（权威源），docs/api-spec.md 第 5 节。
 * 裁决 20261010（DB §6）：绩效评价改为一步式提交、不设草稿，PERFORMANCE_EVALUATION_DRAFT 已移除。
 */
public enum EntityType {

    SUPPLIER,
    PERFORMANCE_EVALUATION,
    LIFECYCLE_REQUEST,
    USER
}
