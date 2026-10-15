package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import static com.srm.core.dto.performance.PerformanceValidationPatterns.FACT_INVALID_MSG;

/**
 * §3.2 退回后修改事实重提请求体（契约 §3.2 L718-730，docs/api-spec.md §4.2 L420-423，
 * <b>只有 fact_record + version 两枚字段</b>）。
 *
 * 三条铁律在入参形态上的体现：
 * <ul>
 *   <li><b>不入参 mode</b>（契约 §3.2 L709"不得借重提切换取数模式"）：重提沿用库内既有 mode
 *       对应的适配器，manual 单重生成、mock 单仍由服务端重新生成种子事实；</li>
 *   <li><b>不入参 status</b>：重提后的状态回 {@code PENDING_REVIEW} 由 Service 定死，
 *       状态机不允许前端指定；</li>
 *   <li><b>全量覆盖事实</b>（契约 §3.2 字段表"全量覆盖"）：8 字段必须整体提交，不做 Partial
 *       合并——半套事实会让算分口径与上一轮混在一起，无法审计。</li>
 * </ul>
 *
 * {@code version} 取 {@link PerformanceEvaluationResponse#getVersion()}；0/负数属格式失败
 * → 入参层 40001，<b>缺失</b>与不匹配一律 40901「数据已被修改，请刷新后重试」
 * （§0 乐观锁全量四实体 + 模块二 §2.1/§2.6 既有口径，非本域新造规则）。
 * 前端夹带的分数/评级字段同样被 ignoreUnknown 丢弃（验收 §2.1 对 PUT 与 POST 同口径）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerformanceEvaluationUpdateRequest {

    /** 修正后的 8 字段客观事实（全量覆盖）；mode=manual 单值域非法 → 40001「事实数据校验失败」 */
    @NotNull(message = FACT_INVALID_MSG)
    @JsonProperty("fact_record")
    private PerformanceFactRecord factRecord;

    /** 乐观锁版本；缺失/不匹配 → 40901 */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
