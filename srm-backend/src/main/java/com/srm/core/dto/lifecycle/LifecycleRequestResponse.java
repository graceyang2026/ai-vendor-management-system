package com.srm.core.dto.lifecycle;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 生命周期申请响应体（契约附录 A3 —— <b>13 字段一个不能少</b>，表 lifecycle_request）。
 *
 * 两枚派生名（{@code applied_by_name} / {@code decided_by_name}）本表<b>无物理列</b>，
 * 由 UserMapper 关联 sys_user 反查拼装（展示名优先 real_name，缺失回退 username，查不到回退空串——
 * 与 SupplierResponse.created_by_name 同一口径，绝不 NPE）。
 *
 * {@code version} 是裁决 20261013 候选 A 补入的<b>§2.6 入参唯一取值链</b>：
 * 前端必须从 §2.5 申请历史列表读到的 version 回传给决策接口，缺了它 40901 就成了死码。
 * 未决单的 decided_* 三枚恒 null（契约类型为可选）。
 *
 * 日期时间一律 String 化输出（yyyy-MM-dd'T'HH:mm:ss），与契约 TS 侧 string 对齐；
 * JSON 字段物理 snake_case：逐字段显式 {@code @JsonProperty}，不依赖全局 SNAKE_CASE 配置。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class LifecycleRequestResponse {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("supplier_id")
    private Long supplierId;

    /** SUSPEND / RESUME / ELIMINATE */
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

    /** 乐观锁版本：§2.6 入参 version 的唯一合法取值链 */
    @JsonProperty("version")
    private Integer version;
}
