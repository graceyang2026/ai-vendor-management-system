package com.srm.core.service;

import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationReviewRequest;

import java.util.List;

/**
 * 模块一 §1.7/§1.8 + 模块二 §2.3 供应商资质服务
 * （契约 §1.7/§1.8/§2.3，docs/api-spec.md §2 资质小节与接口表 #9/#10/#11）。
 *
 * 资质生效的两条路径（裁决 20261012）在此闭合：
 * - <b>准入期件</b>：随 {@code POST /suppliers/{id}/audit}（SupplierService#audit）连带批复，
 *   本服务不提供单独入口，也不落 SUPPLIER_QUALIFICATION 域留痕（归 SUPPLIER 域一笔）；
 * - <b>存续期补传件</b>：走本服务的 {@link #review(Long, QualificationReviewRequest)} 单独审批，
 *   落 REVIEW_APPROVE/REVIEW_REJECT 留痕。
 */
public interface QualificationService {

    /**
     * §1.7 上传资质：STAFF 且仅建档本人；供应商状态允许集 DRAFT/RETURNED/NORMAL（裁决 20261011）；
     * 新记录一律 status=PENDING、version=1，不直接生效（裁决 20261012）。
     * 供应商不存在/已逻辑删除 40401；非本人/状态不允许 40302。
     */
    QualificationResponse create(Long supplierId, QualificationCreateRequest request);

    /**
     * §1.8 资质列表：默认 APPROVED + PENDING；REJECTED 仅建档本人追溯（STAFF 非本人过滤，
     * AUDITOR/ADMIN 全量含 REJECTED——裁决 2026-10-14 晚 AUDITOR 行级隔离放宽与契约组合口径）。
     * 每行携带 version（§2.3 入参取值链）与 expired（expiry_date &lt; 今天 同表派生）。
     * 越权访问他人私有在途区供应商 40302；供应商不存在 40401。
     */
    List<QualificationResponse> list(Long supplierId);

    /**
     * §2.3 存续期资质单独审批（AUDITOR）：资质必须 {@code PENDING} 且其供应商必须 {@code NORMAL}，
     * 否则 40302（准入期件禁走本接口，须随 §2.2 连带批复）。
     * APPROVE → {@code APPROVED}（同类型旧已批件逻辑退位；生效位并发竞态由 DB 唯一索引兜底 → 40902）；
     * REJECT → {@code REJECTED}（旧件保持生效位，供应商 NORMAL 不受影响），且 review_comment 必填否则 40001。
     * 两分支均回写 reviewed_by/reviewed_at/review_comment，version 自增，并写
     * {@code REVIEW_APPROVE}/{@code REVIEW_REJECT} 审计（entity_type=SUPPLIER_QUALIFICATION，冗余 supplier_id）。
     * 资质记录不存在 40401；version 缺失/不匹配 40901。
     */
    QualificationResponse review(Long id, QualificationReviewRequest request);
}
