package com.srm.core.calculator;

import com.srm.core.dto.performance.PerformanceFactRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 演示数据取数适配器（api-spec §7 L540 命名唯一依据；mode 码值 {@code mock}）。
 *
 * bean name 即 {@code mock}，Service 按 mode 从 {@code Map<String, PerformanceMetricProvider>}
 * 直取本 bean——<b>全程零 if/else 判模式</b>（api-spec §7 L543 禁令）。
 *
 * 防篡改要害（验收 §2.2 逐字）：入站 {@code fact_record} <b>一律忽略且不报错</b>。
 * 即使前端传负数、传超集、传夹带的分数字段，本适配器也既不校验也不抛异常，
 * 直接返回服务端生成的种子事实——落库分数必然来自种子链，与客户端提交值无关。
 * 这条规则的目的在于堵住"借 mock 模式夹带自定义分数"的绕行通道
 * （api-spec §4.3 L443 原文）。
 *
 * 种子设计（同供应商同周期可复现，不同供应商/周期出数不同）：
 * 以 {@code supplierId} 与周期两端 epochDay 混合成单一 long 种子，再用
 * <b>无分支取模算术</b>逐字段展开——刻意不用 {@code Random}/{@code ThreadLocalRandom}，
 * 因为随机数会让集成测试的落库断言不可复现、也让演示页面每次刷新都变。
 * 生成值天然满足手工链的全部不变量（非负、子集 ≤ 全集），因此无需校验。
 */
@Component("mock")
public class MockDataMetricAdapter implements PerformanceMetricProvider {

    /** 质数步长：避免 supplierId 相邻时种子撞车 */
    private static final long SEED_MULTIPLIER = 1_000_003L;

    /** 周期两端在种子中的混合宽度（天数跨度上限远小于此，不会串味） */
    private static final long PERIOD_MIX_FACTOR = 31L;

    @Override
    public PerformanceFactRecord fetchFacts(Long supplierId, LocalDate periodStart, LocalDate periodEnd,
                                            PerformanceFactRecord inbound) {
        // inbound 有意不读：验收 §2.2 要求"传入也被忽略且不报错"，参数只作为形态占位
        long seed = seedOf(supplierId, periodStart, periodEnd);

        int totalBatches = (int) (20 + mod(seed, 40));
        int delayedBatches = (int) mod(seed / 41, 5);
        BigDecimal avgDelayDays = BigDecimal.valueOf(5 + mod(seed / 61, 45), 1);
        int qcTotalBatches = (int) (10 + mod(seed / 83, 30));
        int qcFailedBatches = (int) mod(seed / 97, 4);
        int majorAccidents = (int) mod(seed / 101, 2);
        BigDecimal priceDeviationRate = BigDecimal.valueOf(10 + mod(seed / 103, 120), 1);
        int complaintOvertimeCount = (int) mod(seed / 107, 3);

        return new PerformanceFactRecord(
                totalBatches, delayedBatches, avgDelayDays,
                qcTotalBatches, qcFailedBatches, majorAccidents,
                priceDeviationRate, complaintOvertimeCount);
    }

    /**
     * 混合种子：supplierId × 质数 + 周期两端 epochDay。同供应商同周期结果恒定可复现。
     * 三个入参的非空性由 Service 层前置校验保证（supplier_id {@code @NotNull}、
     * period 两列 {@code @NotBlank} + 日期解析闸），本方法不再写 null 兜底分支——
     * 那种分支在生产中不可达，只会留下一条永远测不到的死路。
     */
    private long seedOf(Long supplierId, LocalDate periodStart, LocalDate periodEnd) {
        return supplierId * SEED_MULTIPLIER
                + periodStart.toEpochDay() * PERIOD_MIX_FACTOR
                + periodEnd.toEpochDay();
    }

    /**
     * 非负取模：种子经除法衰减后仍可能因 long 溢出为负，
     * 用 Math.floorMod 统一收敛到 [0, m)，杜绝"演示数据出现负批次"的资损型缺陷。
     */
    private long mod(long value, long m) {
        return Math.floorMod(value, m);
    }
}
