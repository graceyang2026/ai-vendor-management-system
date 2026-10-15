package com.srm.core.dto.supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.supplier.SupplierValidationPatterns.DECISION_REGEX;
import static com.srm.core.dto.supplier.SupplierValidationPatterns.PARAM_INVALID_MSG;

/**
 * §2.2 准入审核决策请求体（契约 §2.2 L334-348，三字段 {@code version}/{@code decision}/{@code comment}）。
 *
 * 分层与 {@link SupplierSubmitRequest} 完全同口径：
 * <ul>
 *   <li>{@code decision}：值域 APPROVE/REJECT，非法码值属格式失败 → 入参层 40001
 *       （契约 §2.2 字段表「枚举/校验规则」列；文案采模块二统一的「参数校验失败」）；</li>
 *   <li>{@code comment}：<b>条件必填</b>（decision=REJECT 时必填）无法由 Bean Validation 单字段表达，
 *       故入参层不加 @NotBlank，由 Service 折 40001「驳回时必须填写意见」；</li>
 *   <li>{@code version}：缺失 → 40901（Service 侧，§0 乐观锁四实体铁律 + api-spec §2 接口表
 *       「不回传或版本不匹配 → 40901」为权威源；契约 §2.2 矩阵不单列缺失行，
 *       与 §2.1 候选 B 同批对齐，见本轮会话留痕「契约内部不一致」备案）；0/负数 → 40001。</li>
 * </ul>
 * 本请求体不含任何状态字段：APPROVE→NORMAL、REJECT→RETURNED 的迁移映射由 Service 硬编码，
 * 前端无法指定目标态（状态机唯一入口铁律，契约模块二 L253）。
 */
@Data
public class SupplierAuditDecisionRequest {

    /** 乐观锁版本，取 SupplierResponse.version；不匹配 40901 */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;

    /** 审核决策：APPROVE / REJECT */
    @NotBlank(message = PARAM_INVALID_MSG)
    @Pattern(regexp = DECISION_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("decision")
    private String decision;

    /** 驳回原因：decision=REJECT 时必填，落供应商域审计 comment 并连带回写资质 review_comment */
    @JsonProperty("comment")
    private String comment;
}
