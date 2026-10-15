package com.srm.core.calculator;

import com.srm.core.dto.performance.PerformanceFactRecord;

import java.time.LocalDate;

/**
 * 绩效评价<b>取数适配器</b>抽象（docs/api-spec.md §7 适配器命名唯一实现依据，rules.md §4.4）。
 *
 * 职责边界（P6 只取数、绝不算分）：实现类只负责产出一枚客观事实
 * {@link PerformanceFactRecord}，加权算分与评级映射一律交唯一的
 * {@link PerformanceScoreCalculator}（api-spec §7 L543"任何 adapter 都必须把结果交给它计算，
 * 不允许各自实现加权逻辑"）。因此本接口<b>不声明</b>任何返回分数/评级的方法。
 *
 * 装配方式（api-spec §7 L543 与 rules.md §4.4 明文禁令）：实现类以 <b>bean name = mode 码值</b>
 * 注册（{@code @Component("manual")} / {@code @Component("mock")}），Service 按请求里的
 * {@code mode} 从 {@code Map<String, PerformanceMetricProvider>} 取 bean，
 * <b>禁止用 if/else 判断"是否为 mock 模式"</b>。二期接 ERP/WMS 时新增
 * {@code ExternalSystemMetricAdapter}（api-spec §7 L541 预留，本期不实现），
 * 只需多注册一个 bean，取数链零改动（对扩展开放、对修改封闭）。
 */
public interface PerformanceMetricProvider {

    /**
     * 取指定供应商、指定考核周期的客观事实。
     *
     * @param supplierId  被评供应商 ID（演示数据按此取种子，保证同一供应商同一周期可复现）
     * @param periodStart 考核周期起（已由 Service 层完成合法性判定）
     * @param periodEnd   考核周期止
     * @param inbound     前端提交的 {@code fact_record}：manual 模式校验并直通；
     *                    mock 模式<b>一律忽略且不报错</b>（验收 §2.2，防借 mock 夹带自定义分数）
     * @return 服务端认可的客观事实，交由唯一算分引擎计算
     */
    PerformanceFactRecord fetchFacts(Long supplierId, LocalDate periodStart, LocalDate periodEnd,
                                     PerformanceFactRecord inbound);
}
