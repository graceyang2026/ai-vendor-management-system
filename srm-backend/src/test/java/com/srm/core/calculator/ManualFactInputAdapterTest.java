package com.srm.core.calculator;

import com.srm.core.common.BusinessException;
import com.srm.core.dto.performance.PerformanceFactRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 手工录入取数适配器单元测试（契约 §3.1 L611 四条校验 + 错误码矩阵 40001「事实数据校验失败」）。
 *
 * 本类只锁<b>取数与校验</b>，不锁分数——适配器职责边界是"只取数不算分"（api-spec §7 L543），
 * 通过校验的事实对象必须<b>原样直通</b>（同一实例引用），以此证明适配器没有加工数值的机会。
 */
@ExtendWith(MockitoExtension.class)
class ManualFactInputAdapterTest {

    private final ManualFactInputAdapter adapter = new ManualFactInputAdapter();

    private static final Long SUPPLIER_ID = 1001L;
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);
    private static final String MSG_FACT_INVALID = "事实数据校验失败";

    private PerformanceFactRecord facts(Integer total, Integer delayed, BigDecimal avgDelay,
                                        Integer qcTotal, Integer qcFailed, Integer accidents,
                                        BigDecimal priceDeviation, Integer complaintOvertime) {
        return new PerformanceFactRecord(total, delayed, avgDelay, qcTotal, qcFailed,
                accidents, priceDeviation, complaintOvertime);
    }

    /** 契约 §3.1 样例事实：全部合规 */
    private PerformanceFactRecord valid() {
        return facts(40, 3, new BigDecimal("2.5"), 20, 1, 0, new BigDecimal("4.2"), 1);
    }

    private void expectFactInvalid(PerformanceFactRecord inbound) {
        assertThatThrownBy(() -> adapter.fetchFacts(SUPPLIER_ID, START, END, inbound))
                .isInstanceOf(BusinessException.class)
                .hasMessage(MSG_FACT_INVALID)
                .extracting(ex -> ((BusinessException) ex).getCode().getValue())
                .isEqualTo(40001);
    }

    // ==================== 通过链 ====================

    /** 合规事实必须<b>原样直通</b>：返回同一实例，字段一毫不变（否则就是适配器越权加工） */
    @Test
    void passesValidFactsThroughUnchanged() {
        PerformanceFactRecord inbound = valid();

        PerformanceFactRecord out = adapter.fetchFacts(SUPPLIER_ID, START, END, inbound);

        assertThat(out).isSameAs(inbound);
        assertThat(out.getTotalBatches()).isEqualTo(40);
        assertThat(out.getDelayedBatches()).isEqualTo(3);
        assertThat(out.getAvgDelayDays()).isEqualByComparingTo("2.5");
        assertThat(out.getQcTotalBatches()).isEqualTo(20);
        assertThat(out.getQcFailedBatches()).isEqualTo(1);
        assertThat(out.getMajorAccidents()).isZero();
        assertThat(out.getPriceDeviationRate()).isEqualByComparingTo("4.2");
        assertThat(out.getComplaintOvertimeCount()).isEqualTo(1);
    }

    /** 全零事实（零业务）不属校验失败——免考是算分层的规则，适配器不得拦（验收 §2.3 的前提） */
    @Test
    void acceptsAllZeroFacts() {
        PerformanceFactRecord zero = facts(0, 0, BigDecimal.ZERO, 0, 0, 0, BigDecimal.ZERO, 0);

        assertThat(adapter.fetchFacts(SUPPLIER_ID, START, END, zero)).isSameAs(zero);
    }

    /** 子集恰好等于全集（qc_failed=qc_total、delayed=total）是合法边界，不得误拦 */
    @Test
    void acceptsSubsetEqualToSuperset() {
        PerformanceFactRecord boundary = facts(10, 10, new BigDecimal("3"), 10, 10, 0,
                new BigDecimal("12.5"), 2);

        assertThat(adapter.fetchFacts(SUPPLIER_ID, START, END, boundary)).isSameAs(boundary);
    }

    // ==================== 条件必填 ====================

    /** mode=manual 缺 fact_record → 40001（契约 §3.1 矩阵第一行） */
    @Test
    void rejectsMissingFactRecord() {
        expectFactInvalid(null);
    }

    /** 8 枚字段任一为 null 都属"必填缺失"，一律 40001，不得默默按 0 处理 */
    @ParameterizedTest
    @MethodSource("eachFieldNull")
    void rejectsAnyNullField(PerformanceFactRecord inbound) {
        expectFactInvalid(inbound);
    }

    static Stream<Arguments> eachFieldNull() {
        BigDecimal two = new BigDecimal("2.5");
        return Stream.of(
                Arguments.of(factsStatic(null, 3, two, 20, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, null, two, 20, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, null, 20, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, null, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, null, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, 1, null, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, 1, 0, null, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, 1, 0, two, null))
        );
    }

    // ==================== 非负 ====================

    /** 6 枚整型计数任一为负 → 40001 */
    @ParameterizedTest
    @MethodSource("eachIntegerNegative")
    void rejectsNegativeIntegerFacts(PerformanceFactRecord inbound) {
        expectFactInvalid(inbound);
    }

    static Stream<Arguments> eachIntegerNegative() {
        BigDecimal two = new BigDecimal("2.5");
        return Stream.of(
                Arguments.of(factsStatic(-1, 0, two, 20, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, -1, two, 20, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, -1, 1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, -1, 0, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, 1, -1, two, 1)),
                Arguments.of(factsStatic(40, 3, two, 20, 1, 0, two, -1))
        );
    }

    /** 两枚小数事实（平均逾期天数、价格偏离率）为负同样 40001 */
    @Test
    void rejectsNegativeDecimalFacts() {
        expectFactInvalid(facts(40, 3, new BigDecimal("-0.5"), 20, 1, 0, new BigDecimal("4.2"), 1));
        expectFactInvalid(facts(40, 3, new BigDecimal("2.5"), 20, 1, 0, new BigDecimal("-1"), 1));
    }

    // ==================== 子集 ≤ 全集 ====================

    /** qc_failed_batches > qc_total_batches → 40001（质检不合格数不可能超过质检总数） */
    @Test
    void rejectsQcFailedGreaterThanQcTotal() {
        expectFactInvalid(facts(40, 3, new BigDecimal("2.5"), 20, 21, 0, new BigDecimal("4.2"), 1));
    }

    /** delayed_batches > total_batches → 40001（逾期批次不可能超过交付总数） */
    @Test
    void rejectsDelayedGreaterThanTotal() {
        expectFactInvalid(facts(40, 41, new BigDecimal("2.5"), 20, 1, 0, new BigDecimal("4.2"), 1));
    }

    /**
     * 校验失败<b>不写任何数据、不返回半成品</b>：异常即出口，
     * 周期与供应商入参只是查询条件，适配器对它们不做任何副作用。
     */
    @Test
    void doesNotMutateInboundOnFailure() {
        PerformanceFactRecord inbound = facts(40, 41, new BigDecimal("2.5"), 20, 1, 0,
                new BigDecimal("4.2"), 1);

        assertThatThrownBy(() -> adapter.fetchFacts(SUPPLIER_ID, START, END, inbound))
                .isInstanceOf(BusinessException.class);

        assertThat(inbound.getDelayedBatches()).isEqualTo(41);
        assertThat(inbound.getTotalBatches()).isEqualTo(40);
    }

    private static PerformanceFactRecord factsStatic(Integer total, Integer delayed, BigDecimal avgDelay,
                                                     Integer qcTotal, Integer qcFailed, Integer accidents,
                                                     BigDecimal priceDeviation, Integer complaintOvertime) {
        return new PerformanceFactRecord(total, delayed, avgDelay, qcTotal, qcFailed,
                accidents, priceDeviation, complaintOvertime);
    }
}
