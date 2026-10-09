package com.srm.core.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（docs/backend-interface-design.md 第 10 节）：
 * 注册分页拦截器（PaginationInnerInterceptor）与乐观锁拦截器（OptimisticLockerInnerInterceptor），
 * 前者使 selectPage 真正下发 LIMIT/OFFSET 并执行 COUNT，后者配合实体 @Version 字段实现并发控制。
 * 顺序：MP 官方建议分页拦截器在前、乐观锁在后。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
