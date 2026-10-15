package com.srm.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.srm.core.entity.LifecycleRequest;

/**
 * 生命周期申请表 Mapper（模块二 §2.4/§2.5/§2.6）。
 *
 * 分页、逻辑删除、乐观锁全部由 MybatisPlusConfig 拦截器 + 实体注解驱动；
 * 本域只有单表 insert / selectList / selectCount / updateById，无自定义 SQL，不需要 mapper/*.xml。
 * "同供应商同类型仅一张在途申请"的并发兜底走 DB 唯一索引 uk_supplier_active_lifecycle
 * （生成列 active_key），由 Service 捕获 DuplicateKeyException 转译 40902，不在本接口手写 SQL。
 */
public interface LifecycleRequestMapper extends BaseMapper<LifecycleRequest> {
}
