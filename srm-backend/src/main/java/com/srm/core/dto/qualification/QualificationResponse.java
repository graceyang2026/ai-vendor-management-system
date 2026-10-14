package com.srm.core.dto.qualification;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 资质响应体（契约附录 A2 —— <b>13 字段一个不能少</b>，表 supplier_qualification）。
 *
 * 与"归二期"的 SupplierResponse 派生三枚不同：{@code expired} 是<b>同表字段比较</b>
 * （expiry_date < 今天）的契约必备派生位，本批即实现（用户口径 2026-10-14），
 * 豁免仅针对跨表派生的 business_license_url / latest_performance_grade / risk_warning。
 *
 * version 为 §2.3 存续期单独审批入参 {@code QualificationReviewRequest.version} 的唯一取值链，
 * 每行必须携带（契约 §1.8 业务约束）。
 *
 * 日期时间一律 String 化输出（yyyy-MM-dd / yyyy-MM-dd'T'HH:mm:ss），与契约 TS 侧 string 对齐；
 * JSON 字段物理 snake_case：逐字段显式 @JsonProperty，不依赖全局 SNAKE_CASE 配置。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class QualificationResponse {

    /** 资质记录 ID（§2.3 路径参数来源） */
    @JsonProperty("id")
    private Long id;

    @JsonProperty("supplier_id")
    private Long supplierId;

    /** BUSINESS_LICENSE / OTHER */
    @JsonProperty("doc_type")
    private String docType;

    @JsonProperty("file_url")
    private String fileUrl;

    @JsonProperty("effective_date")
    private String effectiveDate;

    @JsonProperty("expiry_date")
    private String expiryDate;

    /** 资质三态：PENDING/APPROVED/REJECTED（裁决 20261012） */
    @JsonProperty("status")
    private String status;

    /** 乐观锁版本（DDL④），§2.3 入参取值链 */
    @JsonProperty("version")
    private Integer version;

    /** 派生位：expiry_date < 今天（同表比较，本批实现）；仅 APPROVED 行参与 C 维度算分 */
    @JsonProperty("expired")
    private Boolean expired;

    @JsonProperty("created_at")
    private String createdAt;

    /** 审核人（§2.2 连带/§2.3 单独审批回写；§1.7 新件恒 null） */
    @JsonProperty("reviewed_by")
    private Long reviewedBy;

    @JsonProperty("reviewed_at")
    private String reviewedAt;

    @JsonProperty("review_comment")
    private String reviewComment;
}
