package com.srm.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.srm.core.entity.PerformanceEvaluation;

/**
 * 绩效评价表 Mapper（模块三 §3.1–§3.5，docs/api-spec.md §4.3）。
 *
 * 分页、逻辑删除、乐观锁全部由 MybatisPlusConfig 拦截器 + 实体注解驱动；
 * 本域只有单表 insert / selectPage / selectCount / selectById / updateById，
 * 无自定义 SQL，不需要 mapper/*.xml（与 LifecycleRequestMapper 同族口径）。
 *
 * "同供应商同周期仅一张在途评价单"的并发兜底走 DB 唯一索引 uk_supplier_active_period
 * （生成列 active_period_key），由 Service 捕获 DuplicateKeyException 转译 40902，
 * <b>不在本接口手写 SQL</b>——算分引擎与取数适配器也不得越过本 Mapper 直连库。
 */
public interface PerformanceEvaluationMapper extends BaseMapper<PerformanceEvaluation> {
}
