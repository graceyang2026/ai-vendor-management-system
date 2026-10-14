package com.srm.core.service;

import com.srm.core.common.PageResult;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierUpdateRequest;

/**
 * 模块一：供应商主链服务（契约 §1.1–§1.5，docs/接口契约文档-供应商与绩效-20261013.md）。
 *
 * 本批只覆盖建档 / 编辑 / 列表 / 详情 / 逻辑删除；送审 submit、审核 audit、
 * 资质与生命周期均非本批范围。状态迁移一律不由本服务直接开放 PATCH 旁路。
 * 关键操作是否落 AuditLog：契约 §1.1–§1.5 未要求（用户裁决 2026-10-14 16:15 本批不落审计）。
 */
public interface SupplierService {

    /** §1.1 建档：STAFF 专属，创建即 DRAFT、version=1、created_by=当前用户；税号冲突 40902 */
    SupplierResponse create(SupplierCreateRequest request);

    /** §1.2 编辑：STAFF 且仅本人、仅 DRAFT/RETURNED；Partial 语义；版本不匹配 40901 */
    SupplierResponse update(Long id, SupplierUpdateRequest request);

    /** §1.3 列表：后端强制 §0 行级隔离；status/keyword 过滤拼进查询；分页 page/page_size */
    PageResult<SupplierResponse> list(String status, String keyword, int page, int pageSize);

    /** §1.4 详情：不存在/已逻辑删除 40401；命中他人私有在途区 40302 */
    SupplierResponse get(Long id);

    /** §1.5 逻辑删除：STAFF 且仅本人 DRAFT，deleted=1（非物理删除） */
    void delete(Long id);
}
