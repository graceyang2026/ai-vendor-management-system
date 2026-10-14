package com.srm.core.service;

import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;

import java.util.List;

/**
 * 模块一 §1.7/§1.8：供应商资质服务（契约 §1.7/§1.8，docs/api-spec.md §2 资质小节）。
 * 批复路径（§2.2 连带 / §2.3 单独审批）非本批范围；本服务只提供"上传即 PENDING"与"列表读"。
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
}
