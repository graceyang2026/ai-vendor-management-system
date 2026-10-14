package com.srm.core.dto.supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 供应商响应体（docs/api-spec.md §2 SupplierResponse，契约附录 A1 —— <b>20 字段一个不能少</b>）。
 *
 * 派生字段归二期（用户裁决 2026-10-14 16:15）：{@code business_license_url}、
 * {@code latest_performance_grade}、{@code risk_warning} 三枚字段<b>保留在契约形态里但本批恒为 null</b>，
 * 后端不写任何派生计算，也绝不从存量列抄值冒充派生结果（Supplier 实体相应地不映射这两列）。
 *
 * 日期时间一律 String 化输出（yyyy-MM-dd / yyyy-MM-dd'T'HH:mm:ss），与契约 TS 侧 string 对齐；
 * JSON 字段物理 snake_case：逐字段显式 @JsonProperty，不依赖全局 SNAKE_CASE 配置。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class SupplierResponse {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("tax_no")
    private String taxNo;

    /** SupplierType 三码之一（存码显中文，中文由前端字典映射） */
    @JsonProperty("type")
    private String type;

    @JsonProperty("contact_name")
    private String contactName;

    @JsonProperty("contact_phone")
    private String contactPhone;

    @JsonProperty("contact_email")
    private String contactEmail;

    @JsonProperty("address")
    private String address;

    @JsonProperty("remark")
    private String remark;

    /** 派生字段，归二期，本批恒 null（裁决 2026-10-14 16:15） */
    @JsonProperty("business_license_url")
    private String businessLicenseUrl;

    @JsonProperty("effective_date")
    private String effectiveDate;

    @JsonProperty("expiry_date")
    private String expiryDate;

    /** SupplierStatus：DRAFT/PENDING_REVIEW/NORMAL/RETURNED/SUSPENDED/ELIMINATED */
    @JsonProperty("status")
    private String status;

    /** 乐观锁版本号，前端下次 PUT 必须回传本字段 */
    @JsonProperty("version")
    private Integer version;

    @JsonProperty("created_by")
    private Long createdBy;

    @JsonProperty("created_by_name")
    private String createdByName;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    /** 派生字段，归二期，本批恒 null（裁决 2026-10-14 16:15） */
    @JsonProperty("latest_performance_grade")
    private String latestPerformanceGrade;

    /** 派生字段，归二期，本批恒 null（Boolean 而非 boolean，保住"未计算"与"false"的语义区分） */
    @JsonProperty("risk_warning")
    private Boolean riskWarning;
}
