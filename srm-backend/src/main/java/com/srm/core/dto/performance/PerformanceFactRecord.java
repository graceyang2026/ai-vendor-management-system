package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 绩效评价<b>入站客观事实</b>（契约附录 A4.1 全 8 字段，docs/api-spec.md §4.2 L368-377，
 * rules.md §4.3 防篡改铁律的唯一入参形态）。
 *
 * 防篡改硬约束（验收 §2.1）：本类<b>物理上不存在</b>任何 {@code score}/{@code grade}/
 * {@code exempt}/{@code risk} 字段——分数只可能来自 PerformanceScoreCalculator；
 * 前端在 fact_record 里夹带的分数字段由 {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * 静默丢弃（契约 A4.1 注记明文"只能被静默丢弃"，不是 40001，也不是报错）。
 *
 * 类型口径（A4.1 逐行）：批次/事故/客诉计数为 {@code Integer}，
 * {@code avg_delay_days} 与 {@code price_deviation_rate} 为 {@code BigDecimal}
 * （契约样例 2.5 / 4.2 带小数，用 double 会引入二进制误差污染算分）。
 *
 * 校验不在本类做：非负、子集≤全集这类业务规则由 ManualFactInputAdapter 判定
 * （§3.1 明文"由 ManualFactInputAdapter 校验"），折 40001「事实数据校验失败」。
 * 字段是否必填同样由适配器按 mode 判定——mode=mock 时本类实例可为 null（服务端生成）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PerformanceFactRecord {

    /** 交付总批次（D 维度专用，=0 触发交付零业务免考） */
    @JsonProperty("total_batches")
    private Integer totalBatches;

    /** 逾期批次数（必须 ≤ total_batches） */
    @JsonProperty("delayed_batches")
    private Integer delayedBatches;

    /** 平均逾期天数（非负） */
    @JsonProperty("avg_delay_days")
    private BigDecimal avgDelayDays;

    /** 质检总批次（Q 维度专用，=0 触发质量免考 quality_exempt=true） */
    @JsonProperty("qc_total_batches")
    private Integer qcTotalBatches;

    /** 质检不合格批数（必须 ≤ qc_total_batches） */
    @JsonProperty("qc_failed_batches")
    private Integer qcFailedBatches;

    /** 重大事故数（非负，每次扣 50） */
    @JsonProperty("major_accidents")
    private Integer majorAccidents;

    /** 价格偏离率 %（非负，每 1% 扣 2 分） */
    @JsonProperty("price_deviation_rate")
    private BigDecimal priceDeviationRate;

    /** 客诉超时次数（非负，每次扣 10） */
    @JsonProperty("complaint_overtime_count")
    private Integer complaintOvertimeCount;
}
