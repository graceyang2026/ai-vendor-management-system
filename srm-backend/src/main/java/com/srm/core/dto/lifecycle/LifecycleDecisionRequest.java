package com.srm.core.dto.lifecycle;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.lifecycle.LifecycleValidationPatterns.DECISION_REGEX;
import static com.srm.core.dto.lifecycle.LifecycleValidationPatterns.PARAM_INVALID_MSG;

/**
 * §2.6 生命周期申请决策请求体（契约 §2.6 L521-535，三字段 {@code decision} / {@code comment} /
 * {@code version}，docs/api-spec.md §3 L315-319）。
 *
 * 分层与 §2.2 供应商审核决策完全同口径：
 * <ul>
 *   <li>{@code decision}：APPROVE/REJECT 二值，非法码值属格式失败 → 入参层 40001「参数校验失败」
 *       （契约 §2.6 L552 原文）；</li>
 *   <li>{@code comment}：契约字段表标注<b>非必填</b>（与 §2.2/§2.3"驳回必须给理由"不同——本域
 *       生命周期终审的 REJECT 不强制意见），两分支原样回写 {@code decision_comment}；</li>
 *   <li>{@code version}：<b>申请单</b>乐观锁版本，取 §2.5 列表返回的
 *       {@code LifecycleRequestResponse.version}（裁决 20261013 候选 A 补齐的取值链）。
 *       0/负数 → 入参层 40001；<b>缺失</b> → Service 侧 40901：契约 §2.6 业务约束 L512 与
 *       api-spec §3 L318 都明文"不回传或版本不匹配 → 40901"，而 L552 错误码矩阵把"version 缺失"
 *       与"decision 非法"并列进 40001 —— 两处自相矛盾，按契约文档 L950「口径冲突一律以
 *       docs/api-spec.md 为权威」取 40901，与 §2.1/§2.2 同批对齐（差异已在会话留痕上报待裁决）。</li>
 * </ul>
 * 本请求体不含目标状态字段：SUSPEND→SUSPENDED / RESUME→NORMAL / ELIMINATE→ELIMINATED
 * 的联动映射由 Service 硬编码（状态机唯一入口铁律）。
 */
@Data
public class LifecycleDecisionRequest {

    /** 决策：APPROVE / REJECT */
    @NotBlank(message = PARAM_INVALID_MSG)
    @Pattern(regexp = DECISION_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("decision")
    private String decision;

    /** 决策意见（选填），回写 lifecycle_request.decision_comment 并落审计 comment */
    @JsonProperty("comment")
    private String comment;

    /** 申请单乐观锁版本；缺失/不匹配 → 40901 */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
