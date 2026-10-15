package com.srm.core.calculator;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.dto.performance.PerformanceEvaluationResult;
import com.srm.core.dto.performance.PerformanceFactRecord;
import com.srm.core.mapper.SupplierQualificationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 唯一算分引擎单元测试（docs/api-spec.md §4.1 L344-363 逐字对账，契约附录 A4.3）。
 *
 * 为什么必须在单元层逐条锁公式：绩效评价的分数是全系统<b>不可由前端影响</b>的量，
 * 一旦公式实现走样（权重写反、MAX(0,…) 漏了、免考分支除零），集成测试只能看到"有分数"
 * 而看不出"分数错"。故本类按公式五行逐条给定<b>手算可复核</b>的期望值，
 * 并把四类边界（负分折 0、两枚免考、评级四档闭区间、C 维度三种返回）全部单列。
 *
 * C 维度只依赖"是否存在过期已批资质"这一枚布尔，故用 mock 的 {@code selectCount}
 * 返回 1L/0L/null 三态覆盖（null 是 MyBatis-Plus COUNT 装箱返回的理论空值，
 * 必须按"无过期件"处理，绝不因框架返回 null 就错打风险标）。
 */
@ExtendWith(MockitoExtension.class)
class PerformanceScoreCalculatorTest {

    @Mock
    private SupplierQualificationMapper qualificationMapper;

    private PerformanceScoreCalculator calculator;

    private static final long SUPPLIER_ID = 1001L;

    /** 单元层无 Spring 容器，MyBatis-Plus 的 lambda 缓存需手工注册，否则 LambdaQueryWrapper 取不到列名 */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierQualification.class);
    }

    @BeforeEach
    void setUp() {
        calculator = new PerformanceScoreCalculator(qualificationMapper);
    }

    // ==================== 夹具 ====================

    private PerformanceFactRecord facts(int total, int delayed, String avgDelayDays,
                                        int qcTotal, int qcFailed, int accidents,
                                        String priceDeviation, int complaintOvertime) {
        return new PerformanceFactRecord(total, delayed, new BigDecimal(avgDelayDays),
                qcTotal, qcFailed, accidents, new BigDecimal(priceDeviation), complaintOvertime);
    }

    /** 默认"无过期已批资质"桩：C=100、risk_warning=false */
    private void noExpiredQualification() {
        when(qualificationMapper.selectCount(any())).thenReturn(0L);
    }

    private void expiredQualification(long count) {
        when(qualificationMapper.selectCount(any())).thenReturn(count);
    }

    // ==================== §4.1 逐条公式 ====================

    /**
     * 契约 §3.1 样例事实（total 40 / delayed 3 / avg 2.5 / qc 20 中 1 不合格 / 0 事故 /
     * 偏离 4.2% / 客诉超时 1 次）的<b>公式实算</b>：
     * Q=100-1/20×100×1.5=92.50；D=100-3/40×100-2.5×2=87.50；P=100-4.2×2=91.60；
     * S=100-1×10=90.00；C=100.00 → 总分 91.45（A）。
     * <b>注</b>：契约 §3.1 成功样例里的 95.00/92.50/88.00/92.60 与本公式实算不符（属样例数字
     * 与公式自相矛盾），已按"公式为权威、样例数字待校正"上报，本断言锁公式。
     */
    @Test
    void calculatesFiveDimensionsPerApiSpecFormula() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(40, 3, "2.5", 20, 1, 0, "4.2", 1), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("92.50");
        assertThat(result.getScoreDelivery()).isEqualByComparingTo("87.50");
        assertThat(result.getScorePrice()).isEqualByComparingTo("91.60");
        assertThat(result.getScoreService()).isEqualByComparingTo("90.00");
        assertThat(result.getScoreCompliance()).isEqualByComparingTo("100.00");
        assertThat(result.getTotalScore()).isEqualByComparingTo("91.45");
        assertThat(result.getGrade()).isEqualTo("A");
        assertThat(result.getQualityExempt()).isFalse();
        assertThat(result.getRiskWarning()).isFalse();
    }

    /** 全 100 的干净事实 → 总分恰好 100.00，验证权重之和为 1 且 BigDecimal 无浮点尾巴 */
    @Test
    void perfectFactsYieldExactlyHundred() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "0", 0), SUPPLIER_ID);

        assertThat(result.getTotalScore()).isEqualByComparingTo("100.00");
        assertThat(result.getGrade()).isEqualTo("A");
    }

    /** Q 维度：不合格率与重大事故双扣叠加，且事故一次就扣满 50 */
    @Test
    void qualitySubtractsFailRateAndAccidents() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 40, 3, 0, "0", 0), SUPPLIER_ID);
        // 3/40×100×1.5 = 11.25 → Q = 88.75
        assertThat(result.getScoreQuality()).isEqualByComparingTo("88.75");

        PerformanceEvaluationResult withAccident = calculator.calculate(
                facts(10, 0, "0", 40, 0, 1, "0", 0), SUPPLIER_ID);
        // 0 不合格但 1 次重大事故 → 100 - 50 = 50.00
        assertThat(withAccident.getScoreQuality()).isEqualByComparingTo("50.00");
    }

    /**
     * MAX(0, …) 折底：五维任一维扣穿 0 都必须停在 0.00，
     * 不得出现负分（负分会把总分拖成契约外的负值，也会让 DECIMAL(5,2) 出现"负绩效"歧义）。
     */
    @Test
    void clampsEveryDimensionAtZeroAndNeverGoesNegative() {
        expiredQualification(1);

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 10, "0", 10, 10, 0, "60", 10), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("0.00");
        assertThat(result.getScoreDelivery()).isEqualByComparingTo("0.00");
        assertThat(result.getScorePrice()).isEqualByComparingTo("0.00");
        assertThat(result.getScoreService()).isEqualByComparingTo("0.00");
        assertThat(result.getScoreCompliance()).isEqualByComparingTo("0.00");
        assertThat(result.getTotalScore()).isEqualByComparingTo("0.00");
        assertThat(result.getGrade()).isEqualTo("D");
        assertThat(result.getRiskWarning()).isTrue();
    }

    /** 扣分为 0 的边界（delayed=0 且事故=0）不得因为减法链路而掉分 */
    @Test
    void zeroPenaltiesKeepFullMarks() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(50, 0, "0.0", 30, 0, 0, "0.00", 0), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("100.00");
        assertThat(result.getScoreDelivery()).isEqualByComparingTo("100.00");
        assertThat(result.getScorePrice()).isEqualByComparingTo("100.00");
        assertThat(result.getScoreService()).isEqualByComparingTo("100.00");
    }

    // ==================== 零业务免考（验收 §2.3） ====================

    /** qc_total_batches=0 → Q=100 且 quality_exempt=true，绝不触发除零 */
    @Test
    void qualityExemptionWhenNoQcBatches() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(40, 3, "2.5", 0, 0, 0, "4.2", 1), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("100.00");
        assertThat(result.getQualityExempt()).isTrue();
        // 交付维度仍按公式算：D 有批次 → 87.50，证明"免考只封 Q 的分母，不牵动他维"
        assertThat(result.getScoreDelivery()).isEqualByComparingTo("87.50");
    }

    /**
     * total_batches=0 → D=100，且<b>复用同一枚 quality_exempt</b>（api-spec §4.1 L363 裁决：
     * 不新增 delivery_exempt 列）；同时封住 delayed/total 的除零缺口。
     */
    @Test
    void deliveryExemptionReusesSameExemptionFlagWithoutDivisionByZero() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(0, 0, "0", 20, 1, 0, "4.2", 1), SUPPLIER_ID);

        assertThat(result.getScoreDelivery()).isEqualByComparingTo("100.00");
        assertThat(result.getQualityExempt()).isTrue();
        // Q 仍按公式扣分：1/20 → 92.50
        assertThat(result.getScoreQuality()).isEqualByComparingTo("92.50");
    }

    /** 两枚分母同时为 0（当期完全无业务）→ Q/D 双 100 且免考标只有一枚 */
    @Test
    void bothZeroBatchesExemptWithSingleFlag() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(0, 0, "0", 0, 0, 0, "0", 0), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("100.00");
        assertThat(result.getScoreDelivery()).isEqualByComparingTo("100.00");
        assertThat(result.getTotalScore()).isEqualByComparingTo("100.00");
        assertThat(result.getGrade()).isEqualTo("A");
        assertThat(result.getQualityExempt()).isTrue();
    }

    // ==================== C 维度：自动读资质（前端不传） ====================

    /** 存在过期已批资质 → C=0 且 risk_warning=true（§4.1 L358） */
    @Test
    void complianceZeroAndRiskFlaggedWhenApprovedQualificationExpired() {
        expiredQualification(2);

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "0", 0), SUPPLIER_ID);

        assertThat(result.getScoreCompliance()).isEqualByComparingTo("0.00");
        assertThat(result.getRiskWarning()).isTrue();
        // 其余四维满分 → 总分 90.00，恰好落在 A 的下边界（评级边界与 C=0 联动的实算证明）
        assertThat(result.getTotalScore()).isEqualByComparingTo("90.00");
        assertThat(result.getGrade()).isEqualTo("A");
    }

    /** COUNT 装箱返回 null（框架层理论空值）按"无过期件"处理，绝不错打风险标 */
    @Test
    void nullCountFromMapperTreatedAsNoExpiredQualification() {
        when(qualificationMapper.selectCount(any())).thenReturn(null);

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "0", 0), SUPPLIER_ID);

        assertThat(result.getScoreCompliance()).isEqualByComparingTo("100.00");
        assertThat(result.getRiskWarning()).isFalse();
    }

    /**
     * C 维度取数条件必须只卡 <b>APPROVED + 有效期非空 + 有效期早于今天</b>
     * （api-spec §4.1 L358 明文 PENDING/REJECTED 不参与；未填有效期不得误判过期）。
     * 断言 SQL 段与绑定值，而非把实现细节抄成桩。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void complianceQueryFiltersApprovedOnlyAndRequiresExpiryDate() {
        noExpiredQualification();
        calculator.calculate(facts(10, 0, "0", 10, 0, 0, "0", 0), SUPPLIER_ID);

        ArgumentCaptor<Wrapper<SupplierQualification>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(qualificationMapper).selectCount(captor.capture());
        LambdaQueryWrapper<SupplierQualification> wrapper =
                (LambdaQueryWrapper<SupplierQualification>) captor.getValue();

        assertThat(wrapper.getSqlSegment())
                .contains("supplier_id =")
                .contains("status =")
                .contains("expiry_date IS NOT NULL")
                .contains("expiry_date <");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(SUPPLIER_ID, "APPROVED");
    }

    // ==================== 评级四档（A≥90 / 80≤B<90 / 70≤C<80 / D<70） ====================

    /** 90.00 → A（见 complianceZeroAndRiskFlagged... 已证）；80.00 → B 恰落下界 */
    @Test
    void gradeBBoundaryAtExactlyEighty() {
        noExpiredQualification();

        // price_dev=50 → P=0；Q/D/S/C 满分 → 30+25+0+15+10 = 80.00
        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "50", 0), SUPPLIER_ID);

        assertThat(result.getTotalScore()).isEqualByComparingTo("80.00");
        assertThat(result.getGrade()).isEqualTo("B");
    }

    /** 79.99 以下 → C：客诉超时 1 次（S=90）叠加 P=0 → 78.50 */
    @Test
    void gradeCBandAtSeventyEight() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "50", 1), SUPPLIER_ID);

        assertThat(result.getTotalScore()).isEqualByComparingTo("78.50");
        assertThat(result.getGrade()).isEqualTo("C");
    }

    /** 70.00 → C 恰落下界：P=0 且 C=0（过期资质）→ 30+25+0+15+0 = 70.00 */
    @Test
    void gradeCBoundaryAtExactlySeventy() {
        expiredQualification(1);

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 0, "50", 0), SUPPLIER_ID);

        assertThat(result.getTotalScore()).isEqualByComparingTo("70.00");
        assertThat(result.getGrade()).isEqualTo("C");
    }

    /** 69.99 以下 → D：一次重大事故（Q=50）+ P=0 + S=90 → 63.50 */
    @Test
    void gradeDBandBelowSeventy() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 10, 0, 1, "50", 1), SUPPLIER_ID);

        assertThat(result.getTotalScore()).isEqualByComparingTo("63.50");
        assertThat(result.getGrade()).isEqualTo("D");
    }

    // ==================== 精度与不可变性 ====================

    /**
     * 无限小数分母（3/7）必须走 HALF_UP 收敛，最终落 2 位：
     * Q 维度 qc_failed=3 / qc_total=7 → 3/7×100×1.5 = 64.2857…→ 100-64.2857 = 35.7142… → 35.71
     */
    @Test
    void repeatingDecimalDivisorRoundsHalfUpToTwoPlaces() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(10, 0, "0", 7, 3, 0, "0", 0), SUPPLIER_ID);

        assertThat(result.getScoreQuality()).isEqualByComparingTo("35.71");
    }

    /** 总分必须是"已取整维度分"的加权和，人工复算可闭合（避免双重舍入造成的 0.01 漂移） */
    @Test
    void totalScoreEqualsWeightedSumOfRoundedDimensions() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(40, 3, "2.5", 20, 1, 0, "4.2", 1), SUPPLIER_ID);
        BigDecimal expected = result.getScoreQuality().multiply(new BigDecimal("0.30"))
                .add(result.getScoreDelivery().multiply(new BigDecimal("0.25")))
                .add(result.getScorePrice().multiply(new BigDecimal("0.20")))
                .add(result.getScoreService().multiply(new BigDecimal("0.15")))
                .add(result.getScoreCompliance().multiply(new BigDecimal("0.10")));

        assertThat(result.getTotalScore()).isEqualByComparingTo(expected.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** 各维分数一律 2 位小数（DECIMAL(5,2) 落库形态），不带浮点尾巴 */
    @Test
    void everyScoreCarriesTwoDecimalPlaces() {
        noExpiredQualification();

        PerformanceEvaluationResult result = calculator.calculate(
                facts(40, 3, "2.5", 20, 1, 0, "4.2", 1), SUPPLIER_ID);

        assertThat(result.getScoreQuality().scale()).isEqualTo(2);
        assertThat(result.getScoreDelivery().scale()).isEqualTo(2);
        assertThat(result.getScorePrice().scale()).isEqualTo(2);
        assertThat(result.getScoreService().scale()).isEqualTo(2);
        assertThat(result.getScoreCompliance().scale()).isEqualTo(2);
        assertThat(result.getTotalScore().scale()).isEqualTo(2);
    }
}
