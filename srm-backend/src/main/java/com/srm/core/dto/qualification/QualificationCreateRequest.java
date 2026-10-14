package com.srm.core.dto.qualification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static com.srm.core.dto.qualification.QualificationValidationPatterns.DATE_MSG;
import static com.srm.core.dto.qualification.QualificationValidationPatterns.DATE_REGEX;
import static com.srm.core.dto.qualification.QualificationValidationPatterns.DOC_TYPE_MSG;
import static com.srm.core.dto.qualification.QualificationValidationPatterns.DOC_TYPE_REGEX;
import static com.srm.core.dto.qualification.QualificationValidationPatterns.FILE_URL_MSG;

/**
 * 资质上传请求体（契约 §1.7 / docs/api-spec.md §2 资质 Qualification，4 字段）。
 *
 * 校验分工与 SupplierCreateRequest 完全同构（rules.md §1"格式校验在 Controller 入参层一次完成"）：
 * - doc_type：值域只有 BUSINESS_LICENSE / OTHER 两码（契约 §1.7 字段表），中文/小写/外来类型一律 40001；
 * - file_url：必填，取值必须是 §1.6 文件上传返回的 URL；
 * - 两个日期：String 承载，@Pattern 管 yyyy-MM-dd 语法、@AssertTrue 管"expiry 晚于 effective"语义，
 *   同名字段与供应商合作周期<b>不同义、互不联动</b>（契约附录 A2 备注），message 同为 DATE_MSG。
 * 语义非法日期（2026-02-30 这类能过正则但过不了 parse 的值）在此被拦下，绝不冒 50001；
 * Service 侧仍做一次 String→LocalDate 解析兜底（内部调用/绕过入参层的场景）。
 *
 * JSON 字段物理 snake_case：逐字段显式 @JsonProperty，不依赖全局命名策略配置。
 */
@Data
public class QualificationCreateRequest {

    /** BUSINESS_LICENSE / OTHER */
    @NotBlank(message = DOC_TYPE_MSG)
    @Pattern(regexp = DOC_TYPE_REGEX, message = DOC_TYPE_MSG)
    @JsonProperty("doc_type")
    private String docType;

    /** §1.6 文件上传返回的 URL */
    @NotBlank(message = FILE_URL_MSG)
    @JsonProperty("file_url")
    private String fileUrl;

    /** 证照生效日 yyyy-MM-dd */
    @NotBlank(message = DATE_MSG)
    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("effective_date")
    private String effectiveDate;

    /** 证照到期日 yyyy-MM-dd，必须晚于生效日（相等即非法，契约用词"晚于"） */
    @NotBlank(message = DATE_MSG)
    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("expiry_date")
    private String expiryDate;

    /**
     * 跨字段语义校验：expiry_date 必须<b>晚于</b> effective_date。
     * 任一日期为空时返回 true —— 必填缺失由 @NotBlank 负责报错，避免同一请求刷两条重复文案。
     */
    @JsonIgnore
    @AssertTrue(message = DATE_MSG)
    public boolean isDateRangeValid() {
        if (effectiveDate == null || expiryDate == null) {
            return true;
        }
        try {
            return LocalDate.parse(expiryDate).isAfter(LocalDate.parse(effectiveDate));
        } catch (DateTimeParseException ex) {
            return false;
        }
    }
}
