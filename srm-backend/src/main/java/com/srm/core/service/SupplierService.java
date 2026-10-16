package com.srm.core.service;

import com.srm.core.common.PageResult;
import com.srm.core.dto.supplier.SupplierAuditDecisionRequest;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierExportRow;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierSubmitRequest;
import com.srm.core.dto.supplier.SupplierUpdateRequest;

import java.util.List;

/**
 * 供应商主链服务（契约 §1.1–§1.5 模块一 + §2.1/§2.2 模块二状态机，
 * docs/接口契约文档-供应商与绩效-20261013.md）。
 *
 * 本接口覆盖建档 / 编辑 / 列表 / 详情 / 逻辑删除，以及<b>状态机前两道门</b>：
 * §2.1 提交准入审核、§2.2 准入审核决策（连带批复资质）。契约模块二 L253 铁律：
 * 本模块是<b>唯一能改 supplier.status 的入口</b>，故 submit/audit 与 §1.2 update 之间
 * 不存在任何"直接 PUT 状态"的旁路——SupplierUpdateRequest 物理上没有 status 字段。
 * 存续期/淘汰类迁移（NORMAL⇄SUSPENDED→ELIMINATED）归 LifecycleRequestService 两步流，不由本服务开放。
 *
 * 审计：§1.1–§1.5 契约未要求留痕（用户裁决 2026-10-14 16:15 不落）；§2.1/§2.2 属关键状态操作，
 * 契约 §2.1 L297-302、§2.2 L354-360 明确要求写 AuditLog（含 supplier_id 冗余列）。
 */
public interface SupplierService {

    /** §1.1 建档：STAFF 专属，创建即 DRAFT、version=1、created_by=当前用户；税号冲突 40902 */
    SupplierResponse create(SupplierCreateRequest request);

    /** §1.2 编辑：STAFF 且仅本人、仅 DRAFT/RETURNED；Partial 语义；版本不匹配 40901 */
    SupplierResponse update(Long id, SupplierUpdateRequest request);

    /** §1.3 列表：后端强制 §0 行级隔离；status/keyword 过滤拼进查询；分页 page/page_size */
    PageResult<SupplierResponse> list(String status, String keyword, int page, int pageSize);

    /**
     * GET /suppliers/export 档案导出查询（需求定稿 2026-10-16，新增只读能力）：
     * status/keyword 语义与 §1.3 完全一致，且<b>数据可见范围必须与 list 同源</b>——
     * 实现上复用 list 的同一套 §0 行级隔离查询条件工厂（STAFF 仅"公有区 + 本人 DRAFT/RETURNED"，
     * AUDITOR/ADMIN 全量，裁决 2026-10-14 晚），导出不是隔离规则的旁路。
     * 命中超过 5000 行时截断至前 5000 行（防内存/性能风险，不报错）；
     * 码值→中文由 SupplierExportRow 完成；只读能力，不写 AuditLog。
     * 二进制 xlsx 流的写出归 Controller（本方法不感知 HttpServletResponse）。
     */
    List<SupplierExportRow> exportRows(String status, String keyword);

    /** §1.4 详情：不存在/已逻辑删除 40401；命中他人私有在途区 40302 */
    SupplierResponse get(Long id);

    /** §1.5 逻辑删除：STAFF 且仅本人 DRAFT，deleted=1（非物理删除） */
    void delete(Long id);

    /**
     * §2.1 提交准入审核：STAFF 且仅本人，{@code DRAFT/RETURNED → PENDING_REVIEW}。
     * 前置硬门槛=名下至少一份未过期 BUSINESS_LICENSE 资质（{@code expiry_date >= 今天}），否则 40001；
     * 状态非目标态 40302；version 缺失/不匹配 40901；成功写审计 SUBMIT/SUPPLIER。
     */
    SupplierResponse submit(Long id, SupplierSubmitRequest request);

    /**
     * §2.2 准入审核决策（连带批复资质）：AUDITOR，只允许 {@code PENDING_REVIEW}，否则 40302。
     * APPROVE → NORMAL、REJECT → RETURNED；<b>同事务</b>把该供应商全部 PENDING 资质连带置
     * APPROVED/REJECTED 并回写 reviewed_by/reviewed_at（REJECT 必带 comment，否则 40001）；
     * version 缺失/不匹配 40901；审计只落 SUPPLIER 域一笔（方案甲，连带批复不另落资质留痕）。
     */
    SupplierResponse audit(Long id, SupplierAuditDecisionRequest request);
}
