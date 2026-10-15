package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.performance.PerformanceValidationPatterns.FACT_INVALID_MSG;
import static com.srm.core.dto.performance.PerformanceValidationPatterns.MODE_REGEX;
import static com.srm.core.dto.performance.PerformanceValidationPatterns.PARAM_INVALID_MSG;

/**
 * §3.1 发起评价请求体（契约 §3.1 L623-651，docs/api-spec.md §4.2 L412-418，共 5 字段）。
 *
 * 防篡改要点：本类<b>物理上不含</b> {@code status}/{@code version}/{@code result} 任何形态——
 * 状态由后端定死 {@code PENDING_REVIEW}、版本定死 1、分数由算分引擎产出（"新建无旧版本"，
 * 契约 §3.1 业务约束 L614 明文本接口不接收 version）。前端若夹带 {@code total_score}/{@code grade}
 * 这类结果字段，由 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 静默丢弃（验收 §2.1），
 * 落库值仍只可能来自 PerformanceScoreCalculator。
 *
 * 分层判序（与模块二同一风格：入参层拦格式，Service 拦业务）：
 * <ul>
 *   <li>入参层：三枚必填标量缺失 / 日期语法 / mode 码值 → 40001；</li>
 *   <li>Service 层：周期起止倒挂、fact_record 条件必填与 8 字段值域（交适配器）、
 *       供应商存在性、同周期在途唯一 → 40001/40401/40902。</li>
 * </ul>
 * {@code fact_record} 的"mode=manual 时必填"是<b>条件必填</b>，入参层单字段注解表达不了，
 * 由 ManualFactInputAdapter 判定（契约 §3.1 L611 即把这条规则写在适配器名下）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerformanceEvaluationCreateRequest {

    /** 被评供应商 ID（须存在且未 ELIMINATED，Service 层判定 → 40401「供应商不存在」） */
    @NotNull(message = PARAM_INVALID_MSG)
    @JsonProperty("supplier_id")
    private Long supplierId;

    /** 考核周期起 yyyy-MM-dd；契约 §3.1 矩阵把"周期区间非法"归 40001「事实数据校验失败」，故本条文案取该原文 */
    @NotBlank(message = FACT_INVALID_MSG)
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = FACT_INVALID_MSG)
    @JsonProperty("period_start")
    private String periodStart;

    /** 考核周期止 yyyy-MM-dd，必须晚于 period_start（Service 层判定） */
    @NotBlank(message = FACT_INVALID_MSG)
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = FACT_INVALID_MSG)
    @JsonProperty("period_end")
    private String periodEnd;

    /** 取数模式：manual（默认，缺省即此）/ mock；缺失放行，非法码值 40001「参数校验失败」 */
    @Pattern(regexp = MODE_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("mode")
    private String mode;

    /** 客观事实 8 字段：mode=manual 必填（适配器判定），mode=mock 传了也被忽略且不报错（验收 §2.2） */
    @JsonProperty("fact_record")
    private PerformanceFactRecord factRecord;
}
