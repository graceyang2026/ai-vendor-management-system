package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.performance.PerformanceValidationPatterns.DECISION_REGEX;
import static com.srm.core.dto.performance.PerformanceValidationPatterns.PARAM_INVALID_MSG;

/**
 * §3.3 绩效评价复核请求体（契约 §3.3 L766-780，docs/api-spec.md §4.3 L459-463，三字段）。
 *
 * 契约字段名以本类为准，<b>不是模板样例的 {@code action}/{@code review_comments}</b>
 * （契约文末《裁决记录与样例差异》二.2 点名：绩效复核入参叫 {@code decision} / {@code comment}；
 * 叫 action/review_comment 的是 §2.3 资质审批，两套不可混用）。
 *
 * 分层判序（与 §2.2/§2.6 决策类接口完全同口径）：
 * <ul>
 *   <li>入参层：decision 码值非法（含小写 approve、裸 PASS）→ 40001「参数校验失败」；
 *       version 为 0/负数 → 40001「版本号不合法（必须为正整数）」；</li>
 *   <li>Service 层：REJECT 缺 comment → 40001「驳回时必须填写意见」（契约 §3.3 矩阵原文，
 *       与 §2.2 准入审核同文案同规则，跨域引用同一常量源）；version 缺失 → 40901；
 *       非 PENDING_REVIEW → 40302；本域<b>没有</b> APPROVE 的前置硬门槛之外的业务字段。</li>
 * </ul>
 *
 * 本类<b>不含</b>任何分数/评级/状态字段：归档后写回供应商的 {@code latest_performance_grade}/
 * {@code risk_warning} 一律取库内算分结果，前端无处覆盖（防篡改铁律）。
 * {@code comment} 在 APPROVE 分支为选填，原样回写 review_comment 并落审计。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerformanceReviewDecisionRequest {

    /** 复核动作：APPROVE（归档）/ REJECT（退回），别的码值一律 40001 */
    @NotBlank(message = PARAM_INVALID_MSG)
    @Pattern(regexp = DECISION_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("decision")
    private String decision;

    /** 复核意见：REJECT 必填（Service 判定），APPROVE 选填 */
    @JsonProperty("comment")
    private String comment;

    /** 评价单乐观锁版本；缺失/不匹配 → 40901 */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
