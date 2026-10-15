package com.srm.core.dto.qualification;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.qualification.QualificationValidationPatterns.ACTION_REGEX;
import static com.srm.core.dto.qualification.QualificationValidationPatterns.PARAM_INVALID_MSG;

/**
 * §2.3 存续期资质单独审批请求体（契约 §2.3 L393-407，docs/api-spec.md §2 专节 L211-219）。
 *
 * <b>字段名定稿（用户裁决 2026-10-12）</b>：三字段 {@code action} / {@code review_comment} /
 * {@code version}。注意入参叫 {@code action} 而<b>不叫</b> {@code decision}（§2.2 供应商审核才叫
 * decision）；且 {@code review_comment} <b>无复数 s</b>（模板样例的 review_comments 属上游差异，契约为准）。
 *
 * <b>术语区分铁律（契约 §2.3 L384）</b>：本类的 {@code action} 是入参二值 APPROVE/REJECT，
 * 与审计日志的 {@code action} 字段是两个层——落审计必须映射成 {@code REVIEW_APPROVE}/{@code REVIEW_REJECT}，
 * 禁止把裸 {@code APPROVE} 写进 audit_log（由 Service 侧映射，集成测试逐条锁定）。
 *
 * 校验分工与模块二供应商域 DTO 完全同口径：
 * <ul>
 *   <li>{@code action}：值域非法属<b>格式</b>失败 → 入参层 40001「参数校验失败」；</li>
 *   <li>{@code review_comment}：条件必填（action=REJECT 时）无法由单字段注解表达，
 *       交 Service 折 40001「驳回时必须填写意见」（契约 §2.3 错误码矩阵 L425）；</li>
 *   <li>{@code version}：取 §1.8 资质列表返回的 {@code QualificationResponse.version}（DDL④ 唯一取值链）；
 *       0/负数 → 入参层 40001，<b>缺失</b> → Service 侧 40901（照 §2.1 候选 B 与 api-spec
 *       「不回传或版本不匹配 → 40901」的乐观锁语义，刻意不加 {@code @NotNull}）。</li>
 * </ul>
 * 本请求体不含任何状态字段：PENDING→APPROVED/REJECTED 的映射由 Service 硬编码（状态机唯一入口铁律）。
 */
@Data
public class QualificationReviewRequest {

    /** 裁决动作：APPROVE / REJECT（值域与 §2.2 decision 同码，单一事实源引用） */
    @NotBlank(message = PARAM_INVALID_MSG)
    @Pattern(regexp = ACTION_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("action")
    private String action;

    /** 审批意见：action=REJECT 时必填，回写 supplier_qualification.review_comment */
    @JsonProperty("review_comment")
    private String reviewComment;

    /** 乐观锁版本，取 §1.8 列表返回的 version；缺失/不匹配 → 40901 */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
