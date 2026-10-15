package com.srm.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生命周期申请实体，映射 lifecycle_request 表（schema.sql §6 L165 建表实文，契约附录 A3）。
 *
 * 三件套范式与 Supplier / SupplierQualification 完全一致（模块二状态机的落库基础）：
 * - {@code @Version}：DDL⑤（裁决 20261013 候选 A，schema.sql L171 实文 {@code version INT NOT NULL DEFAULT 1}）
 *   对应 §2.6 终审入参 {@code LifecycleDecisionRequest.version} 的唯一取值源；
 *   不标注则乐观锁拦截器不生效、40901 永无触发基础（契约《裁决记录与样例差异》一.2 点名的已知坑）。
 * - {@code @TableLogic}：申请单一律逻辑删除；生成列 active_key 随之置 NULL，自动释放"在途唯一"占位。
 * - {@code @TableName}：物理表名单数下划线，与既有实体同风格。
 *
 * 有意不映射的列：
 * - active_key：DB 生成列（{@code CASE WHEN deleted=0 AND status='PENDING' THEN CONCAT(supplier_id,'_',type)}），
 *   唯一索引 uk_supplier_active_lifecycle 的载体，即"同供应商同类型仅一张在途申请"的 DB 兜底，
 *   不对业务代码暴露（schema.sql L182 注释同口径）。
 * - applied_by_name / decided_by_name：契约附录 A3 的这两列是<b>关联 sys_user 派生</b>，
 *   本表无对应物理列，只在 LifecycleRequestResponse 出参层由 UserMapper 反查拼装。
 *
 * 状态口径：{@code status} 为<b>申请单三态</b> PENDING/APPROVED/REJECTED（api-spec §3
 * LifecycleRequestStatus），与供应商六态、资质三态、绩效三态严格区分，四套枚举互不复用。
 */
@Data
@TableName("lifecycle_request")
public class LifecycleRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属供应商（supplier.id）；§0 规则 C 行级隔离与审计冗余列的数据源 */
    private Long supplierId;

    /** SUSPEND / RESUME / ELIMINATE（存码显中文，值域校验在 DTO 入参层） */
    private String type;

    /** 申请理由（必填：停用说原因 / 恢复说整改情况 / 淘汰说依据） */
    private String reason;

    /** 申请单三态 PENDING/APPROVED/REJECTED，勿与供应商 status 混用 */
    private String status;

    /** 申请人（=提申请时的 STAFF 登录用户） */
    private Long appliedBy;

    private LocalDateTime appliedAt;

    /** 决策人（§2.6 终审 AUDITOR；未决恒 NULL） */
    private Long decidedBy;

    private LocalDateTime decidedAt;

    /** 决策意见（§2.6 comment 回写；契约未要求 REJECT 必填） */
    private String decisionComment;

    /** 乐观锁版本（DDL⑤）；§2.5 列表每行携带，是 §2.6 入参 version 的唯一取值链 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
