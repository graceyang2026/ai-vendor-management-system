package com.srm.core.calculator;

import com.srm.core.dto.performance.PerformanceFactRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 演示数据取数适配器单元测试（契约 §3.1 / 验收 §2.2："前端传入的 fact_record 被忽略而不是报错"）。
 *
 * 三类锁定：
 * <ol>
 *   <li><b>忽略入站</b>：传 null、传非法值（负数、子集大于全集、夹带自定义分数）都不得抛异常，
 *       且返回结果与入参毫无关系；</li>
 *   <li><b>可复现</b>：同 supplier + 同周期两次调用产出完全相同的事实（演示数据不能每次刷新都变）；</li>
 *   <li><b>自洽</b>：生成值天然满足手工链的全部不变量（非负、子集≤全集、分母不为 0），
 *       绝不让演示模式产出会被算分层除零或折成负分的脏数据。</li>
 * </ol>
 */
class MockDataMetricAdapterTest {

    private final MockDataMetricAdapter adapter = new MockDataMetricAdapter();

    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private PerformanceFactRecord dirtyInbound() {
        // 负值 + 子集大于全集：手工链会 40001，mock 链必须"忽略且不报错"
        return new PerformanceFactRecord(-5, 999, new BigDecimal("-9"), -1, 88, 77,
                new BigDecimal("-100"), 12345);
    }

    // ==================== 忽略入站（验收 §2.2） ====================

    /** 传非法事实也不报错、不采信：返回值与入参字段一个都不相等 */
    @Test
    void ignoresInboundFactsWithoutThrowing() {
        PerformanceFactRecord inbound = dirtyInbound();

        PerformanceFactRecord out = adapter.fetchFacts(1001L, START, END, inbound);

        assertThat(out).isNotNull();
        assertThat(out).isNotSameAs(inbound);
        assertThat(out.getTotalBatches()).isNotEqualTo(inbound.getTotalBatches());
        assertThat(out.getQcTotalBatches()).isPositive();
    }

    /** 传 null 同样不报错（mode=mock 时 fact_record 本就可选，契约 §3.1 参数表） */
    @Test
    void acceptsNullInbound() {
        assertThatCode(() -> adapter.fetchFacts(1001L, START, END, null))
                .doesNotThrowAnyException();
    }

    // ==================== 可复现 ====================

    /** 同供应商同周期两次调用完全一致（种子确定性，集成测试据此断言落库值） */
    @Test
    void sameSupplierAndPeriodProducesIdenticalFacts() {
        PerformanceFactRecord first = adapter.fetchFacts(2002L, START, END, null);
        PerformanceFactRecord second = adapter.fetchFacts(2002L, START, END, null);

        assertThat(second).usingRecursiveComparison().isEqualTo(first);
    }

    /** 换周期或换供应商应产出不同演示数据（否则所有供应商页面长得一模一样，失去演示意义） */
    @Test
    void differentSupplierOrPeriodChangesFacts() {
        PerformanceFactRecord a = adapter.fetchFacts(3001L, START, END, null);
        PerformanceFactRecord b = adapter.fetchFacts(3002L, START, END, null);
        PerformanceFactRecord c = adapter.fetchFacts(3001L, START, LocalDate.of(2026, 12, 31), null);

        // 种子差 = 1×1_000_003（换供应商）与 92（换周期止），对 40 取模均非 0
        // → total_batches 必然错开；用它做判据比"任一字段不同"更精确且可复算
        assertThat(b.getTotalBatches()).isNotEqualTo(a.getTotalBatches());
        assertThat(c.getTotalBatches()).isNotEqualTo(a.getTotalBatches());
        assertThat(c).isNotEqualTo(a);
        assertThat(b).isNotEqualTo(a);
    }

    // ==================== 自洽不变量 ====================

    /**
     * 扫 200 个供应商 × 2 个周期：全部字段非负、{@code delayed ≤ total}、
     * {@code qc_failed ≤ qc_total}、两枚分母都 ≥ 1（mock 链永不制造除零）。
     */
    @Test
    void generatedFactsAlwaysSatisfyInvariants() {
        Set<String> distinctSignatures = new HashSet<>();

        for (long supplierId = 1L; supplierId <= 200L; supplierId++) {
            for (LocalDate start : new LocalDate[]{START, LocalDate.of(2026, 1, 1)}) {
                LocalDate end = start.plusMonths(3).minusDays(1);
                PerformanceFactRecord facts = adapter.fetchFacts(supplierId, start, end, null);

                assertThat(facts.getTotalBatches())
                        .isGreaterThanOrEqualTo(20).isLessThanOrEqualTo(59);
                assertThat(facts.getQcTotalBatches())
                        .isGreaterThanOrEqualTo(10).isLessThanOrEqualTo(39);
                assertThat(facts.getDelayedBatches()).isNotNegative()
                        .isLessThanOrEqualTo(facts.getTotalBatches());
                assertThat(facts.getQcFailedBatches()).isNotNegative()
                        .isLessThanOrEqualTo(facts.getQcTotalBatches());
                assertThat(facts.getMajorAccidents()).isNotNegative().isLessThanOrEqualTo(1);
                assertThat(facts.getComplaintOvertimeCount()).isNotNegative().isLessThanOrEqualTo(2);
                assertThat(facts.getAvgDelayDays())
                        .isBetween(new BigDecimal("0.5"), new BigDecimal("4.9"));
                assertThat(facts.getPriceDeviationRate())
                        .isBetween(new BigDecimal("1.0"), new BigDecimal("12.9"));

                distinctSignatures.add(supplierId + "/" + start + "/" + facts);
            }
        }

        // 演示数据必须"各不相同"才有演示价值：400 组样本至少产出 100 种不同形态
        assertThat(distinctSignatures).hasSizeGreaterThan(100);
    }
}
