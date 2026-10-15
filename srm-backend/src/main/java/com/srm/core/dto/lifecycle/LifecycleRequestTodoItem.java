package com.srm.core.dto.lifecycle;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 模块二 §2.7 跨供应商聚合查询（终审待办队列）专用响应视图
 * （契约 §2.7 L585 + docs/api-spec.md §3 {@code LifecycleRequestTodoItem extends LifecycleRequestResponse}，
 * 裁决 20261015）。
 *
 * 结构 = <b>附录 A3 全 13 字段</b>（与 {@link LifecycleRequestResponse} 逐字段同名同形，version 仍是
 * §2.6 决策入参的合法取值源之一）+ 供应商侧冗余两列：
 * <ul>
 *   <li>{@code supplier_name}：关联 supplier.name；</li>
 *   <li>{@code supplier_status}：关联 supplier.status，即<b>当前合作状态</b>——与申请目标 {@code type}
 *       是两个不同概念（"现在是什么"vs"申请改成什么"），契约明文两者都回传、区分展示。</li>
 * </ul>
 * 目的是终审列表一次拿到"哪个供应商、当前什么状态"，前端不再 fan-out 回查 §1.4。
 *
 * 供应商<b>已被逻辑删除或不存在</b>（历史脏 FK）时，这两枚冗余字段按契约回 null、不报错、
 * 不裁剪申请单本体（AUDITOR 全量终审视图，口径见契约 §2.7 L585 与本轮任务书第 5 条）。
 *
 * 采用平铺 15 字段而非继承：与 LifecycleRequestResponse 同一 Lombok 范式，
 * JSON 字段物理 snake_case——逐字段显式 {@code @JsonProperty}，不依赖全局 SNAKE_CASE 配置
 * （本轮任务书红线："DTO 逐字段显式 @JsonProperty"）。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class LifecycleRequestTodoItem {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("supplier_id")
    private Long supplierId;

    /** 申请目标类型 SUSPEND / RESUME / ELIMINATE */
    @JsonProperty("type")
    private String type;

    @JsonProperty("reason")
    private String reason;

    /** 申请单三态 PENDING / APPROVED / REJECTED */
    @JsonProperty("status")
    private String status;

    @JsonProperty("applied_by")
    private Long appliedBy;

    /** 关联 sys_user 派生（非物理列） */
    @JsonProperty("applied_by_name")
    private String appliedByName;

    @JsonProperty("applied_at")
    private String appliedAt;

    @JsonProperty("decided_by")
    private Long decidedBy;

    /** 关联 sys_user 派生（非物理列）；未决为 null */
    @JsonProperty("decided_by_name")
    private String decidedByName;

    @JsonProperty("decided_at")
    private String decidedAt;

    @JsonProperty("decision_comment")
    private String decisionComment;

    /** 乐观锁版本：§2.6 入参 version 的合法取值源之一（与 §2.5 同口径，裁决 20261015） */
    @JsonProperty("version")
    private Integer version;

    /** 关联 supplier.name；供应商已逻辑删除/不存在时为 null（契约 §2.7 L585） */
    @JsonProperty("supplier_name")
    private String supplierName;

    /** 关联 supplier.status（当前合作状态，与申请目标 type 区分）；供应商已逻辑删除/不存在时为 null */
    @JsonProperty("supplier_status")
    private String supplierStatus;
}
