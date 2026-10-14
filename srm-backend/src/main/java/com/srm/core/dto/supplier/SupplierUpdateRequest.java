package com.srm.core.dto.supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

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
 * 供应商编辑请求体（docs/api-spec.md §2 {@code Partial<SupplierCreateRequest>}，契约 §1.2）。
 *
 * Partial 语义（契约 L172）：沿用建档同一套正则，但<b>只对提交了的字段生效</b>——
 * 未提交的字段为 null，@Pattern 对 null 放行，绝不因"没带上其他必填字段"而报 40001；
 * null 字段在 Service 侧保持存量值，不做清空。
 *
 * version 必带且 &gt;= 1（§0 乐观锁铁律）：缺失/非法 → 40001 在入参层拦下；
 * 与库内版本不匹配 → 40901（Service 侧）。日期的先后语义无法在"只见局部字段"的
 * 入参层判定，交由 Service 与存量值合并后按同一条 DATE_MSG 文案复核。
 */
@Data
public class SupplierUpdateRequest {

    @Pattern(regexp = NAME_REGEX, message = NAME_MSG)
    @JsonProperty("name")
    private String name;

    @Pattern(regexp = TAX_NO_REGEX, message = TAX_NO_MSG)
    @JsonProperty("tax_no")
    private String taxNo;

    @Pattern(regexp = TYPE_REGEX, message = TYPE_MSG)
    @JsonProperty("type")
    private String type;

    @Pattern(regexp = NAME_REGEX, message = CONTACT_NAME_MSG)
    @JsonProperty("contact_name")
    private String contactName;

    @Pattern(regexp = PHONE_REGEX, message = CONTACT_PHONE_MSG)
    @JsonProperty("contact_phone")
    private String contactPhone;

    @Pattern(regexp = EMAIL_REGEX, message = CONTACT_EMAIL_MSG)
    @JsonProperty("contact_email")
    private String contactEmail;

    @JsonProperty("address")
    private String address;

    @JsonProperty("remark")
    private String remark;

    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("effective_date")
    private String effectiveDate;

    @Pattern(regexp = DATE_REGEX, message = DATE_MSG)
    @JsonProperty("expiry_date")
    private String expiryDate;

    @NotNull(message = "版本号不能为空")
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
