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
 * - latest_performance_grade / risk_warning：派生字段归二期（用户裁决 2026-10-14 16:15），
 *   本批 SupplierResponse 对应字段恒返回 null，实体不读取列值，杜绝"抄存量值当派生结果"的假象。
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

    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
