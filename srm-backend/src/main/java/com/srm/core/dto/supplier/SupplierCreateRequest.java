package com.srm.core.dto.supplier;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import static com.srm.core.dto.supplier.SupplierValidationPatterns.CONTACT_EMAIL_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.CONTACT_NAME_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.CONTACT_PHONE_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.DATE_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.DATE_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.EMAIL_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.NAME_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.NAME_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.PHONE_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.TAX_NO_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.TAX_NO_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.TYPE_MSG;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.TYPE_REGEX;

/**
 * 供应商建档请求体（docs/api-spec.md §2 SupplierCreateRequest，契约 §1.1）。
 *
 * 校验分工（契约 L172 铁律）：格式校验在 <b>Controller 入参层一次完成</b>，Service 不重复校验；
 * 唯一性（税号 40902）在 Service，故"40001 先行、40902 在后"天然成立，无需额外排序代码。
 * 日期字段用 String 承载：@Pattern 管语法、@AssertTrue 管语义（到期必须晚于生效），
 * 两者 message 同为契约固定文案 DATE_MSG；Service 侧只对 Partial 合并后的日期做同一语义复核。
 * JSON 字段物理 snake_case：逐字段显式 @JsonProperty，不依赖全局命名策略配置。
 */
@Data
public class SupplierCreateRequest {

    @NotBlank(message = NAME_MSG)
    @Pattern(regexp = NAME_REGEX, message = NAME_MSG)
    @JsonProperty("name")
    private String name;

    @NotBlank(message = TAX_NO_MSG)
    @Pattern(regexp = TAX_NO_REGEX, message = TAX_NO_MSG)
    @JsonProperty("tax_no")
    private String taxNo;

    @NotBlank(message = TYPE_MSG)
    @Pattern(regexp = TYPE_REGEX, message = TYPE_MSG)
    @JsonProperty("type")
    private String type;

    @NotBlank(message = CONTACT_NAME_MSG)
    @Pattern(regexp = NAME_REGEX, message = CONTACT_NAME_MSG)
    @JsonProperty("contact_name")
    private String contactName;

    @NotBlank(message = CONTACT_PHONE_MSG)
    @Pattern(regexp = PHONE_REGEX, message = CONTACT_PHONE_MSG)
    @JsonProperty("contact_phone")
    private String contactPhone;

    /** 选填：填则必须合法（null 由 @Pattern 放行，空串由正则首支放行） */
    @Pattern(regexp = EMAIL_REGEX, message = CONTACT_EMAIL_MSG)
    @JsonProperty("contact_email")
    private String contactEmail;

    /** 选填，无格式校验 */
    @JsonProperty("address")
    private String address;

    /** 选填，无格式校验 */
    @JsonProperty("remark")
    private String remark;

    @NotBlank(message = DATE_MSG)
    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("effective_date")
    private String effectiveDate;

    @NotBlank(message = DATE_MSG)
    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("expiry_date")
    private String expiryDate;

    /**
     * 跨字段语义校验：expiry_date 必须<b>晚于</b> effective_date；
     * 语法合法但语义非法的日期（如 2026-02-30）在此被拦下，不得让解析异常冒成 50001。
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
