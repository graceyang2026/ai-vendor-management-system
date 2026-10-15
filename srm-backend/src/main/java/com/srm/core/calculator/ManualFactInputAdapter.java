package com.srm.core.calculator;

import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.dto.performance.PerformanceFactRecord;
import com.srm.core.dto.performance.PerformanceValidationPatterns;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 手工录入取数适配器（api-spec §7 L539 命名唯一依据；rules.md §4.4"生产环境默认实现"）。
 *
 * bean name 即 mode 码值 {@code manual}：Service 用 {@code Map<String, PerformanceMetricProvider>}
 * 按 mode 直取本 bean，<b>全程零 if/else 判模式</b>（api-spec §7 L543 禁令）。
 *
 * 职责＝契约 §3.1 L611 逐字列举的四条校验，失败一律 40001「事实数据校验失败」
 * （文案取契约 §3.1 错误码矩阵 Message 列原文，常量源 PerformanceValidationPatterns）：
 * <ol>
 *   <li>条件必填：{@code mode=manual} 缺 {@code fact_record}（或任一事实字段为 null）；</li>
 *   <li>非负：8 枚事实全部 &ge; 0；</li>
 *   <li>子集 ≤ 全集：{@code qc_failed_batches ≤ qc_total_batches}、
 *       {@code delayed_batches ≤ total_batches}；</li>
 *   <li>通过后<b>原样直通</b>入参对象——适配器不加工数值、更不算分。</li>
 * </ol>
 *
 * 关于"周期区间非法"（任务书 B1 把它列在本适配器名下、契约 §3.1 矩阵同格也写着 40001）：
 * 本实现把<b>周期解析与 {@code period_end > period_start} 判定放在 Service 层</b>，理由是
 * 契约对周期<b>不分 mode</b>——{@code mode=mock} 传非法周期同样要拦（§3.1 请求参数表 period
 * 两列是"必填 + 晚于 start"的硬约束，与取数模式无关），留在 manual 分支里就等于给 mock
 * 开了一个"周期非法也放行"的洞。校验规则本身（码值、文案）零改动，只挪了收口位置，
 * 差异已在会话留痕登记待复核。
 */
@Component("manual")
public class ManualFactInputAdapter implements PerformanceMetricProvider {

    @Override
    public PerformanceFactRecord fetchFacts(Long supplierId, LocalDate periodStart, LocalDate periodEnd,
                                            PerformanceFactRecord inbound) {
        if (inbound == null) {
            // 契约 §3.1 矩阵第一行：mode=manual 缺 fact_record → 40001
            throw factInvalid();
        }
        requireNonNegative(inbound.getTotalBatches());
        requireNonNegative(inbound.getDelayedBatches());
        requireNonNegative(inbound.getQcTotalBatches());
        requireNonNegative(inbound.getQcFailedBatches());
        requireNonNegative(inbound.getMajorAccidents());
        requireNonNegative(inbound.getComplaintOvertimeCount());
        requireNonNegative(inbound.getAvgDelayDays());
        requireNonNegative(inbound.getPriceDeviationRate());

        if (inbound.getQcFailedBatches() > inbound.getQcTotalBatches()) {
            throw factInvalid();
        }
        if (inbound.getDelayedBatches() > inbound.getTotalBatches()) {
            throw factInvalid();
        }
        return inbound;
    }

    /**
     * 非负 + 必填一体判定：null 与负值同码同文案（都属"事实数据校验失败"，
     * 契约未给两者分列不同 message，故不臆造）。整型与小数两枚重载各自成闸。
     */
    private void requireNonNegative(Integer value) {
        if (value == null || value < 0) {
            throw factInvalid();
        }
    }

    private void requireNonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw factInvalid();
        }
    }

    private BusinessException factInvalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                PerformanceValidationPatterns.FACT_INVALID_MSG);
    }
}
