package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * 绩效评价<b>出站算分结果</b>（契约附录 A4.3 全 9 字段 ↔ DB 平铺 9 列，
 * docs/api-spec.md §4.2 L379-389）。
 *
 * 本类是 {@code PerformanceScoreCalculator} 的<b>唯一输出形态</b>：所有字段都由服务端算出，
 * 不存在任何入站通道（入站形态见 {@link PerformanceFactRecord}，两者物理隔离即防篡改铁律的
 * 结构体现）。嵌套在 PerformanceEvaluationResponse.result 下输出——嵌套 vs 平铺是口径差异
 * 而非错位（契约 A4.2 注记）。
 *
 * 分数一律 {@code BigDecimal} 保留两位小数（DB 列 DECIMAL(5,2)）：用 double 会让
 * "总分严格等于加权和"这条验收断言（§2.3）浮点漂移，故算分与出参全链 BigDecimal。
 * JSON 字段物理 snake_case：逐字段显式 {@code @JsonProperty}，不依赖全局 SNAKE_CASE 配置。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class PerformanceEvaluationResult {

    /** Q 质量得分（权重 30%）：qc_total_batches=0 → 100 且免考 */
    @JsonProperty("score_quality")
    private BigDecimal scoreQuality;

    /** D 交付得分（权重 25%）：total_batches=0 → 100，复用同一免考标识 */
    @JsonProperty("score_delivery")
    private BigDecimal scoreDelivery;

    /** P 价格得分（权重 20%） */
    @JsonProperty("score_price")
    private BigDecimal scorePrice;

    /** S 服务得分（权重 15%） */
    @JsonProperty("score_service")
    private BigDecimal scoreService;

    /** C 合规得分（权重 10%）：后端自动读 APPROVED 资质有效期，前端不传 */
    @JsonProperty("score_compliance")
    private BigDecimal scoreCompliance;

    /** 综合得分 = Q*0.30 + D*0.25 + P*0.20 + S*0.15 + C*0.10 */
    @JsonProperty("total_score")
    private BigDecimal totalScore;

    /** 评级 A/B/C/D（契约名 grade，非模板样例的 rating） */
    @JsonProperty("grade")
    private String grade;

    /** 当期无业务整体免考标识（历史列名；total_batches=0 复用本标识，不拆 delivery_exempt） */
    @JsonProperty("quality_exempt")
    private Boolean qualityExempt;

    /** 风险预警：存在过期已批资质 → true（api-spec §4.1 只把本字段绑定 C 维度） */
    @JsonProperty("risk_warning")
    private Boolean riskWarning;
}
