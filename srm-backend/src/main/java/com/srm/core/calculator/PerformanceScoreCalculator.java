package com.srm.core.calculator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.srm.core.dto.performance.PerformanceEvaluationResult;
import com.srm.core.dto.performance.PerformanceFactRecord;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.mapper.SupplierQualificationMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 绩效算分<b>唯一引擎</b>（docs/api-spec.md §4.1 L344-363 逐字实现；rules.md §4.3 防篡改铁律的落点）。
 *
 * 铁律（P6 + api-spec §7 L543）：全系统<b>只有这里</b>把客观事实翻译成分数与评级。
 * 任何 {@link PerformanceMetricProvider} 实现只准取数、不准算分；任何 Controller/Service
 * 也不得自行加权——权重、扣分值、评级阈值全部是本类的私有常量，不可配置、
 * 不可由入参影响（入参类型 {@link PerformanceFactRecord} 物理上没有分数字段）。
 *
 * 公式对照（§4.1 L354-358 逐条，一条一行，便于人工复核）：
 * <pre>
 *   Q = MAX(0, 100 - (qc_failed_batches / qc_total_batches * 100 * 1.5) - major_accidents * 50)
 *   D = MAX(0, 100 - (delayed_batches   / total_batches     * 100)       - avg_delay_days    * 2)
 *   P = MAX(0, 100 - price_deviation_rate * 2)
 *   S = MAX(0, 100 - complaint_overtime_count * 10)
 *   C = 存在过期已批资质 ? 0 : 100          （PENDING/REJECTED 资质不参与）
 *   总分 = Q*30% + D*25% + P*20% + S*15% + C*10%
 *   评级：A ≥ 90；80 ≤ B &lt; 90；70 ≤ C &lt; 80；D &lt; 70
 * </pre>
 *
 * 免考（§4.1 L363 裁决，验收 §2.3）：{@code qc_total_batches = 0} → Q=100 且
 * {@code quality_exempt = true}；{@code total_batches = 0} → D=100 并<b>复用同一枚
 * {@code quality_exempt} 标识</b>（MVP 不加 {@code delivery_exempt} 列），
 * 两个分支同时封住公式里两处分母的除零缺口。
 *
 * BigDecimal 全程：DB 列为 DECIMAL(5,2)，用 double 会让"总分恰好等于加权和"这类
 * 验收断言产生二进制漂移。中间除法取 10 位 HALF_UP，维度分与总分收敛到 2 位 HALF_UP，
 * 且总分是<b>已取整的维度分</b>再加权——先取整再加权与先加权再取整在边界上会差 0.01，
 * 本类选择前者，与页面"看到的维度分乘权重等于总分"的直觉一致（可复核、可复算）。
 */
@Component
public class PerformanceScoreCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal FULL_SCORE = new BigDecimal("100");

    /** §4.1 扣分系数 */
    private static final BigDecimal QC_FAIL_RATE_PENALTY = new BigDecimal("1.5");
    private static final BigDecimal MAJOR_ACCIDENT_PENALTY = new BigDecimal("50");
    private static final BigDecimal DELAY_DAY_PENALTY = new BigDecimal("2");
    private static final BigDecimal PRICE_DEVIATION_PENALTY = new BigDecimal("2");
    private static final BigDecimal COMPLAINT_OVERTIME_PENALTY = new BigDecimal("10");

    /** §4.1 L346-352 固定权重，不可配置 */
    private static final BigDecimal WEIGHT_QUALITY = new BigDecimal("0.30");
    private static final BigDecimal WEIGHT_DELIVERY = new BigDecimal("0.25");
    private static final BigDecimal WEIGHT_PRICE = new BigDecimal("0.20");
    private static final BigDecimal WEIGHT_SERVICE = new BigDecimal("0.15");
    private static final BigDecimal WEIGHT_COMPLIANCE = new BigDecimal("0.10");

    /** 评级阈值（§4.1 L360） */
    private static final BigDecimal GRADE_A_FLOOR = new BigDecimal("90");
    private static final BigDecimal GRADE_B_FLOOR = new BigDecimal("80");
    private static final BigDecimal GRADE_C_FLOOR = new BigDecimal("70");

    /** 资质三态之一（裁决 20261012），只有 APPROVED 件参与 C 维度判定 */
    private static final String QUAL_APPROVED = "APPROVED";

    /** 除法中间精度：10 位足够把 1/3 这类无限小数稳住，最终仍收口到 2 位 */
    private static final int DIVIDE_SCALE = 10;

    /** 分数存储精度（DECIMAL(5,2)） */
    private static final int SCORE_SCALE = 2;

    private final SupplierQualificationMapper qualificationMapper;

    public PerformanceScoreCalculator(SupplierQualificationMapper qualificationMapper) {
        this.qualificationMapper = qualificationMapper;
    }

    /**
     * 唯一算分入口：8 枚客观事实 + 被评供应商 ID → 9 枚结果字段。
     *
     * @param facts      经适配器认可的客观事实（非空、字段非空、非负、子集≤全集）
     * @param supplierId 被评供应商，仅用于 C 维度自动读取资质有效期，不参与任何算式
     * @return 服务端算出的结果，调用方原样落库，不得二次加工
     */
    public PerformanceEvaluationResult calculate(PerformanceFactRecord facts, Long supplierId) {
        boolean exempt = false;

        BigDecimal quality;
        if (facts.getQcTotalBatches() == 0) {
            // §4.1 零业务免考：无质检样本 → 计满 100 并打免考标，避开除零
            quality = scale2(FULL_SCORE);
            exempt = true;
        } else {
            BigDecimal failPenalty = ratio(facts.getQcFailedBatches(), facts.getQcTotalBatches())
                    .multiply(FULL_SCORE)
                    .multiply(QC_FAIL_RATE_PENALTY);
            BigDecimal accidentPenalty = BigDecimal.valueOf(facts.getMajorAccidents().longValue())
                    .multiply(MAJOR_ACCIDENT_PENALTY);
            quality = scale2(FULL_SCORE.subtract(failPenalty).subtract(accidentPenalty));
        }

        BigDecimal delivery;
        if (facts.getTotalBatches() == 0) {
            // 同一枚免考标识（不拆 delivery_exempt）：无交付批次 → D 计满并让 delayed/total 不再除零
            delivery = scale2(FULL_SCORE);
            exempt = true;
        } else {
            BigDecimal delayRatePenalty = ratio(facts.getDelayedBatches(), facts.getTotalBatches())
                    .multiply(FULL_SCORE);
            BigDecimal delayDaysPenalty = facts.getAvgDelayDays().multiply(DELAY_DAY_PENALTY);
            delivery = scale2(FULL_SCORE.subtract(delayRatePenalty).subtract(delayDaysPenalty));
        }

        BigDecimal price = scale2(FULL_SCORE
                .subtract(facts.getPriceDeviationRate().multiply(PRICE_DEVIATION_PENALTY)));

        BigDecimal service = scale2(FULL_SCORE
                .subtract(BigDecimal.valueOf(facts.getComplaintOvertimeCount().longValue())
                        .multiply(COMPLAINT_OVERTIME_PENALTY)));

        // C 维度由后端自动读资质有效期（前端不传，也无法影响）；PENDING/REJECTED 件不参与
        boolean riskWarning = hasExpiredApprovedQualification(supplierId);
        BigDecimal compliance = riskWarning ? scale2(ZERO) : scale2(FULL_SCORE);

        BigDecimal totalScore = scale2(quality.multiply(WEIGHT_QUALITY)
                .add(delivery.multiply(WEIGHT_DELIVERY))
                .add(price.multiply(WEIGHT_PRICE))
                .add(service.multiply(WEIGHT_SERVICE))
                .add(compliance.multiply(WEIGHT_COMPLIANCE)));

        return PerformanceEvaluationResult.builder()
                .scoreQuality(quality)
                .scoreDelivery(delivery)
                .scorePrice(price)
                .scoreService(service)
                .scoreCompliance(compliance)
                .totalScore(totalScore)
                .grade(gradeOf(totalScore))
                .qualityExempt(exempt)
                .riskWarning(riskWarning)
                .build();
    }

    /**
     * 评级映射（§4.1 L360 四档，边界归入高档：90→A、80→B、70→C）。
     */
    private String gradeOf(BigDecimal totalScore) {
        if (totalScore.compareTo(GRADE_A_FLOOR) >= 0) {
            return "A";
        }
        if (totalScore.compareTo(GRADE_B_FLOOR) >= 0) {
            return "B";
        }
        if (totalScore.compareTo(GRADE_C_FLOOR) >= 0) {
            return "C";
        }
        return "D";
    }

    /**
     * 是否存在<b>已批准且已过期</b>的资质：C 维度与 risk_warning 的唯一判定依据。
     * {@code isNotNull(expiryDate)} 有意保留——未填有效期视为"长期有效"，不得误判为过期。
     */
    private boolean hasExpiredApprovedQualification(Long supplierId) {
        Long count = qualificationMapper.selectCount(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, supplierId)
                .eq(SupplierQualification::getStatus, QUAL_APPROVED)
                .isNotNull(SupplierQualification::getExpiryDate)
                .lt(SupplierQualification::getExpiryDate, LocalDate.now()));
        // MyBatis-Plus 的 COUNT 装箱返回理论可为 null（无结果集场景），null 按"无过期件"处理，
        // 绝不允许因框架返回 null 就把供应商错打成风险预警
        return count != null && count > 0;
    }

    /**
     * 子集 / 全集：先取 10 位精度再参与算式；分母为 0 的两种情况已由调用方免考分支拦截。
     */
    private BigDecimal ratio(Integer numerator, Integer denominator) {
        return BigDecimal.valueOf(numerator.longValue())
                .divide(BigDecimal.valueOf(denominator.longValue()), DIVIDE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 落库精度收口：先按 §4.1 的 MAX(0, …) 把负分折 0，再取 2 位 HALF_UP。
     * 顺序不可颠倒——先取整可能出现 {@code -0.00} 这种既非 0 也非负的怪值。
     */
    private BigDecimal scale2(BigDecimal rawScore) {
        BigDecimal clamped = rawScore.signum() < 0 ? ZERO : rawScore;
        return clamped.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }
}
