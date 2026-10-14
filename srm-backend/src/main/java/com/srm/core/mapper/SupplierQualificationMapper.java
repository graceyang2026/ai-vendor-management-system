package com.srm.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.srm.core.entity.SupplierQualification;

/**
 * 供应商资质表 Mapper。分页/逻辑删除/乐观锁均由 MybatisPlusConfig 拦截器 + 实体注解驱动；
 * 本批 §1.7/§1.8 只有单表 insert 与条件 selectList，无复杂 SQL，不需要 mapper/*.xml。
 */
public interface SupplierQualificationMapper extends BaseMapper<SupplierQualification> {
}
