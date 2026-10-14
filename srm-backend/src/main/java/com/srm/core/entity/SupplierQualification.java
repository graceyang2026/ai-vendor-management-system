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
 * 供应商资质实体，映射 supplier_qualification 表（schema.sql §3 L72 建表实文，契约附录 A2）。
 *
 * 并发与删除保护（§0 乐观锁全量四实体裁决 20261013）：
 * - version 标注 {@link Version}——DDL④ 已落（DEFAULT 1），#11 存续期单独审批的 40901
 *   与 §2.2 连带批复都以本列为基础；本批 §1.7 写入一律初始 1，不做更新路径。
 * - deleted 标注 {@link TableLogic}，逻辑删除行自动从所有查询隐身；
 *   DB 生成列 approved_key 随 deleted/status 计算，不映射进实体（与 Supplier.tax_no_active 同口径）。
 *
 * 有意不映射的列：
 * - approved_key：生成列，"同供应商同证照类型仅一条 APPROVED 现行版"唯一索引载体
 *   （uk_supplier_approved_doc），§2.3 批复竞态的 DB 兜底，不对业务代码暴露。
 *
 * 注意：本表<b>没有 created_by 列</b>——契约 §1.8 "REJECTED 仅供本人追溯"的"本人"
 * 经归属链指向<b>供应商建档人</b>（supplier.created_by），资质行本身不记录建档人；
 * 上传入口 §1.7 已用 requireSelf(supplier.created_by) 保证资质必为建档人所传。
 */
@Data
@TableName("supplier_qualification")
public class SupplierQualification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属供应商（supplier.id） */
    private Long supplierId;

    /** BUSINESS_LICENSE / OTHER（VARCHAR 存码，值域校验在 DTO 入参层） */
    private String docType;

    /** §1.6 文件上传返回的 URL */
    private String fileUrl;

    /** 单项证照有效期起（与 supplier 同名列不同义、互不联动） */
    private LocalDate effectiveDate;

    /** 单项证照有效期止；expired 派生位与 C 维度算分的唯一依据 */
    private LocalDate expiryDate;

    /** PENDING/APPROVED/REJECTED（资质三态裁决 20261012，勿与供应商/绩效的 PENDING_REVIEW 混用） */
    private String status;

    /** 审核人（§2.2 连带/§2.3 单独审批回写；本批只读，恒 NULL） */
    private Long reviewedBy;

    /** 审核时间（同上，本批只读） */
    private LocalDateTime reviewedAt;

    /** 驳回意见（同上，本批只读） */
    private String reviewComment;

    /** 乐观锁版本（DDL④）；§1.8 每行携带，为 §2.3 入参 version 的唯一取值链 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
