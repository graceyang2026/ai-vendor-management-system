package com.srm.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商实体，映射 supplier 表（schema.sql §2，契约附录 A1）。
 *
 * 并发与删除保护：
 * - version 必须标注 {@link Version}——乐观锁拦截器（MybatisPlusConfig）早已注册，
 *   若零实体标注则 40901 永无触发基础（已知坑，契约《裁决记录与样例差异》一.2 实现约束）。
 * - deleted 标注 {@link TableLogic}，逻辑删除行自动从所有查询中隐身，
 *   DB 生成列 tax_no_active 随之置 NULL 释放税号唯一占位（验收 §1.6）。
 *
 * 有意不映射的列：
 * - tax_no_active：DB 生成列，唯一索引载体，不对业务代码暴露（schema.sql 注释同口径）。
 *
 * latest_performance_grade / risk_warning 两列（模块三 §3.3 归档联动新增映射，2026-10-15 裁决）：
 * - <b>写侧已闭合</b>：绩效评价 APPROVE 归档时由 PerformanceEvaluationServiceImpl 同事务回写
 *   （评级 + 风险标，只打标不改供应商状态）。
 * - <b>读侧已开启（用户裁决 2026-10-16）</b>：SupplierServiceImpl.toResponse 直接从本实体
 *   存量列取值透出，不再硬编码 null（原 2026-10-14 16:15"归二期"裁决对本两列已失效，
 *   仅 business_license_url 维持二期口径）。注意：新建行经 DB DEFAULT 0 读出 risk_warning=false，
 *   建档当场回显仍为内存实体的 null（insert 不回读），两者口径不同属已知事实。
 * - 列可空性：latest_performance_grade 无默认值（NULL 表示从未归档评价），risk_warning DDL 为
 *   {@code NOT NULL DEFAULT 0}，但实体侧刻意用包装类型 {@link Boolean}（其余 TINYINT(1) 列同族范式），
 *   以区分"未计算"与"计算过且为 false"。MyBatis-Plus 默认非空插入策略下，
 *   既有建档 INSERT 语句不会带上这两列，存量行为零扰动。
 */
@Data
@TableName("supplier")
public class Supplier {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 统一社会信用代码，活跃行全局唯一（uk_tax_no_active 生成列索引兜底） */
    private String taxNo;

    /** MANUFACTURING / SERVICE_OUTSOURCING / DISTRIBUTOR（存码显中文） */
    private String type;

    private String contactName;

    private String contactPhone;

    private String contactEmail;

    private String address;

    private String remark;

    /** 企业合作契约周期起（与 supplier_qualification 同名列不同义） */
    private LocalDate effectiveDate;

    /** 企业合作契约周期止；MVP 内到期不触发任何自动动作 */
    private LocalDate expiryDate;

    /** DRAFT/PENDING_REVIEW/NORMAL/RETURNED/SUSPENDED/ELIMINATED（VARCHAR 存码，不用枚举列） */
    private String status;

    private Long createdBy;

    /**
     * 最近一次<b>归档</b>（APPROVED）评价的评级 A/B/C/D；NULL = 从未有归档评价。
     * 唯一写入点：模块三 §3.3 APPROVE 分支（非读侧派生，见类头说明）。
     */
    private String latestPerformanceGrade;

    /**
     * 风险预警打标：最近一次归档评级为 C/D，或存在过期已批资质 → true。
     * <b>只打标，绝不改 status</b>（停用/淘汰必须走 §2.4/§2.6 LifecycleRequest 两步流程）。
     */
    private Boolean riskWarning;

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
