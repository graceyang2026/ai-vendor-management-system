package com.srm.core.audit;

/**
 * 审计日志业务对象类型。
 * 契约源：docs/api-spec.md 第 0 节「目标态 DDL 增量」表 ③（权威源）、第 5 节 entity_type 取值规则。
 * 裁决 20261010（api-spec §4.1）：绩效评价改为一步式提交、不设草稿，PERFORMANCE_EVALUATION_DRAFT 已移除。
 */
public enum EntityType {

    SUPPLIER,
    PERFORMANCE_EVALUATION,
    LIFECYCLE_REQUEST,
    USER,
    /**
     * 供应商资质实体。裁决 20261012（api-spec §0 增量表 ③）：仅作为**存续期补传资质单独审批**
     * （`PUT /qualifications/{id}/review`，api-spec §2 接口 #10）的审计日志对象；
     * 准入期资质随 `POST /suppliers/{id}/audit` 连带批复的留痕归 {@link #SUPPLIER} 域，不使用本枚举值。
     */
    SUPPLIER_QUALIFICATION
}
