package com.srm.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.srm.core.entity.Supplier;

/**
 * 供应商主表 Mapper。分页/逻辑删除/乐观锁均由 MybatisPlusConfig 注册的拦截器
 * + 实体注解驱动，本批无复杂 SQL，不需要 mapper/*.xml。
 */
public interface SupplierMapper extends BaseMapper<Supplier> {
}
