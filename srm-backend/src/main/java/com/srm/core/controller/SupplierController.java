package com.srm.core.controller;

import com.alibaba.excel.EasyExcel;
import com.srm.core.common.ApiResponse;
import com.srm.core.common.PageResult;
import com.srm.core.dto.supplier.SupplierAuditDecisionRequest;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierExportRow;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierSubmitRequest;
import com.srm.core.dto.supplier.SupplierUpdateRequest;
import com.srm.core.service.SupplierService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 供应商主链 + 状态机前两道门接口（契约 §1.1–§1.5、§2.1–§2.2，docs/api-spec.md §2）。
 *
 * 职责边界：本类只做<b>入参层一次校验</b>（@Valid + Bean Validation，格式失败 40001 随 HTTP 200 回传）
 * 与角色粗粒度拦截（@PreAuthorize → Security 层 403/40301）；
 * 唯一性、归属、状态、乐观锁等细粒度规则全部由 Service 层 RoleGuard 二验兜底，
 * 符合"前后端双重校验铁律"，@PreAuthorize 不是唯一防线。
 * 不提供任何直接改 status 的旁路接口（状态迁移只能走模块二 submit/audit/decision）。
 *
 * 例外（需求定稿 2026-10-16）：GET /suppliers/export 是<b>唯一绕过「HTTP 200 + JSON ApiResponse」
 * 全局约定</b>的端点——成功时直接写 xlsx 二进制流；失败仍由全局兜底折回 JSON（先查数后写流，
 * 不存在半截二进制）。行级隔离与只读口径由 Service 复用 §1.3 同一查询条件工厂保证。
 */
@RestController
@RequestMapping("/api/v1")
public class SupplierController {

    /** xlsx（OOXML 工作簿）标准 MIME，导出流专用 */
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    /** §1.1 POST /suppliers — 建档，创建即 DRAFT（STAFF 专属，ADMIN/AUDITOR → 40301） */
    @PostMapping("/suppliers")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<SupplierResponse> create(@RequestBody @Valid SupplierCreateRequest request) {
        return ApiResponse.success(supplierService.create(request));
    }

    /** §1.2 PUT /suppliers/{id} — 编辑本人 DRAFT/RETURNED 档案，Partial 语义，version 必带 */
    @PutMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<SupplierResponse> update(@PathVariable Long id,
                                                @RequestBody @Valid SupplierUpdateRequest request) {
        return ApiResponse.success(supplierService.update(id, request));
    }

    /** §1.3 GET /suppliers — 分页列表，§0 行级隔离由后端强制拼装查询条件 */
    @GetMapping("/suppliers")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<PageResult<SupplierResponse>> list(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(supplierService.list(status, keyword, page, pageSize));
    }

    /**
     * GET /suppliers/export — 按查询条件导出供应商档案 xlsx（需求定稿 2026-10-16，只读）。
     * 入参 status/keyword 与 §1.3 同语义（无 page/page_size）；角色闸与 §1.3 逐字一致；
     * §0 行级隔离由 Service 复用 list 同一查询条件工厂，导出不是旁路；命中超 5000 行截断不报错；
     * 不写 AuditLog。成功路径是唯一绕过 ApiResponse 统一口径的端点：
     * 先取数（任何异常都在写流之前，失败仍走全局 JSON），再设头、写流。
     */
    @GetMapping("/suppliers/export")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public void export(@RequestParam(name = "status", required = false) String status,
                       @RequestParam(name = "keyword", required = false) String keyword,
                       HttpServletResponse response) throws IOException {
        List<SupplierExportRow> rows = supplierService.exportRows(status, keyword);
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=suppliers_export.xlsx");
        EasyExcel.write(response.getOutputStream(), SupplierExportRow.class)
                .sheet("供应商档案")
                .doWrite(rows);
    }

    /** §1.4 GET /suppliers/{id} — 详情；他人私有在途区 40302，不存在/已删除 40401 */
    @GetMapping("/suppliers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<SupplierResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(supplierService.get(id));
    }

    /**
     * §2.1 POST /suppliers/{id}/submit — DRAFT/RETURNED → PENDING_REVIEW（STAFF 仅本人）。
     * 请求体只有 version（裁决 20261013 候选 B）；营业执照硬门槛/状态闸/乐观锁全部在 Service 判定。
     */
    @PostMapping("/suppliers/{id}/submit")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<SupplierResponse> submit(@PathVariable Long id,
                                                @RequestBody @Valid SupplierSubmitRequest request) {
        return ApiResponse.success(supplierService.submit(id, request));
    }

    /**
     * §2.2 POST /suppliers/{id}/audit — 准入审核决策（AUDITOR 专属）：
     * APPROVE → NORMAL、REJECT → RETURNED，并在同一事务内连带批复该供应商全部 PENDING 资质。
     * data 回传整态 SupplierResponse（新 version 供前端后续操作取值）。
     */
    @PostMapping("/suppliers/{id}/audit")
    @PreAuthorize("hasRole('AUDITOR')")
    public ApiResponse<SupplierResponse> audit(@PathVariable Long id,
                                              @RequestBody @Valid SupplierAuditDecisionRequest request) {
        return ApiResponse.success(supplierService.audit(id, request));
    }

    /** §1.5 DELETE /suppliers/{id} — 仅本人 DRAFT 逻辑删除，成功 data=null */
    @DeleteMapping("/suppliers/{id}")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
        return ApiResponse.success(null);
    }
}
