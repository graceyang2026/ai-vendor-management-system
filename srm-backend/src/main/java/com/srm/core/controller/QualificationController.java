package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationReviewRequest;
import com.srm.core.service.QualificationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模块一 §1.7 / §1.8 供应商资质接口（契约 §1.7、§1.8，docs/api-spec.md §2 资质小节）。
 *
 * 与 SupplierController 同一分层惯例：本类只做<b>入参层一次校验</b>（@Valid → 格式失败 40001 随
 * HTTP 200 回传，用户裁决 2026-10-14：不动 GlobalExceptionHandler 的传输口径）与角色粗粒度拦截
 * （@PreAuthorize → Security 层 403/40301）；归属、状态门槛、§0 行级隔离与 REJECTED 可见性
 * 全部由 QualificationServiceImpl + RoleGuard 二验兜底，@PreAuthorize 不是唯一防线。
 *
 * 路径单列成 QualificationController 而非并入 SupplierController：§2.3「存续期单独审批」
 * （契约 §2.3 L377 / docs/api-spec.md 接口表 #11：<b>PUT /api/v1/qualifications/{id}/review</b>，
 * 路径参数是资质记录 ID 而非供应商 ID——资质行自带 supplier_id，无须再经供应商路径定位）
 * 与 §1.7/§1.8 的 qualifications 路径族同属本类，归属清晰，SupplierController 保持主链职责单一。
 */
@RestController
@RequestMapping("/api/v1")
public class QualificationController {

    private final QualificationService qualificationService;

    public QualificationController(QualificationService qualificationService) {
        this.qualificationService = qualificationService;
    }

    /** §1.7 POST /suppliers/{id}/qualifications — 上传资质，新记录一律 PENDING + version=1（STAFF 专属） */
    @PostMapping("/suppliers/{id}/qualifications")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<QualificationResponse> create(@PathVariable Long id,
                                                    @RequestBody @Valid QualificationCreateRequest request) {
        return ApiResponse.success(qualificationService.create(id, request));
    }

    /** §1.8 GET /suppliers/{id}/qualifications — 资质列表，三登录角色可读，行级可见性由 Service 裁定 */
    @GetMapping("/suppliers/{id}/qualifications")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<List<QualificationResponse>> list(@PathVariable Long id) {
        return ApiResponse.success(qualificationService.list(id));
    }

    /**
     * §2.3 PUT /qualifications/{id}/review — 存续期资质单独审批（AUDITOR 专属）。
     * 入参层只拦 action 值域与 version 格式（40001）；「供应商必须 NORMAL + 资质必须 PENDING」
     * 这两道 40302 闸门、review_comment 条件必填与乐观锁一律在 Service 判定（契约 §2.3 L382/L383）。
     */
    @PutMapping("/qualifications/{id}/review")
    @PreAuthorize("hasRole('AUDITOR')")
    public ApiResponse<QualificationResponse> review(@PathVariable Long id,
                                                     @RequestBody @Valid QualificationReviewRequest request) {
        return ApiResponse.success(qualificationService.review(id, request));
    }
}
