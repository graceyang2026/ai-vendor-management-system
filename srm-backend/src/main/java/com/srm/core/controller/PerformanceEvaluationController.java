package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.common.PageResult;
import com.srm.core.dto.performance.PerformanceEvaluationCreateRequest;
import com.srm.core.dto.performance.PerformanceEvaluationResponse;
import com.srm.core.dto.performance.PerformanceEvaluationUpdateRequest;
import com.srm.core.dto.performance.PerformanceReviewDecisionRequest;
import com.srm.core.service.PerformanceEvaluationService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块三·绩效评价接口（契约 §3.1–§3.5，docs/api-spec.md §4.3，Base {@code /api/v1}）。
 *
 * 职责边界（口径同 SupplierController / LifecycleRequestController）：
 * 本类只做<b>入参层一次校验</b>（{@code @Valid} + Bean Validation：mode/decision 值域、
 * 周期形状、version 下限；失败 40001 随 HTTP 200 回传）与<b>角色粗粒度拦截</b>
 * （{@code @PreAuthorize} → Security 层 403/40301）；
 * 归属、状态机、乐观锁、同周期在途唯一等细粒度规则全部由 Service 层 RoleGuard 二验兜底
 * （rules.md §4.1 前后端双重校验铁律，@PreAuthorize 不是唯一防线）。
 *
 * 防篡改铁律的接口层体现（验收 §2.1）：三个写方法的入参类型
 * （{@link PerformanceEvaluationCreateRequest}/{@link PerformanceEvaluationUpdateRequest}/
 * {@link PerformanceReviewDecisionRequest}）物理上都没有分数/评级字段，
 * 且 DTO 的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 会把前端夹带的
 * {@code total_score}/{@code grade} 等字段<b>静默丢弃</b>（契约 A4.1 注记），
 * 因此本类不存在任何可被外部写入分数的入口。
 *
 * 统一响应：一律 HTTP 200 + {@code ApiResponse}（项目后端规范），
 * 业务错误码由 GlobalExceptionHandler 映射 HTTP 状态。
 */
@RestController
@RequestMapping("/api/v1")
public class PerformanceEvaluationController {

    private final PerformanceEvaluationService performanceEvaluationService;

    public PerformanceEvaluationController(PerformanceEvaluationService performanceEvaluationService) {
        this.performanceEvaluationService = performanceEvaluationService;
    }

    /**
     * §3.1 POST /performance/evaluations — 一步发起评价（仅 STAFF，AUDITOR 不得代提 → 40301）。
     * 无草稿态：直接算分落 {@code PENDING_REVIEW}。
     */
    @PostMapping("/performance/evaluations")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<PerformanceEvaluationResponse> create(
            @RequestBody @Valid PerformanceEvaluationCreateRequest request) {
        return ApiResponse.success(performanceEvaluationService.create(request));
    }

    /**
     * §3.2 PUT /performance/evaluations/{id} — 退回后改事实重提（仅 STAFF 创建本人、仅 RETURNED）。
     * 沿用同一条记录重新算分，不新建；成功写审计 {@code SUBMIT}。
     */
    @PutMapping("/performance/evaluations/{id}")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<PerformanceEvaluationResponse> update(@PathVariable Long id,
                                                             @RequestBody @Valid PerformanceEvaluationUpdateRequest request) {
        return ApiResponse.success(performanceEvaluationService.update(id, request));
    }

    /**
     * §3.3 POST /performance/evaluations/{id}/review — 复核（仅 AUDITOR，STAFF 不得自审 → 40301）。
     * APPROVE 归档并联动供应商评级/风险标；REJECT 退回，comment 必填。
     */
    @PostMapping("/performance/evaluations/{id}/review")
    @PreAuthorize("hasRole('AUDITOR')")
    public ApiResponse<PerformanceEvaluationResponse> review(
            @PathVariable Long id,
            @RequestBody @Valid PerformanceReviewDecisionRequest request) {
        return ApiResponse.success(performanceEvaluationService.review(id, request));
    }

    /** §3.4 GET /performance/evaluations — 分页列表；§0 行级隔离由后端强制拼查询条件 */
    @GetMapping("/performance/evaluations")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<PageResult<PerformanceEvaluationResponse>> list(
            @RequestParam(name = "supplier_id", required = false) Long supplierId,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(performanceEvaluationService.list(supplierId, status, page, pageSize));
    }

    /** §3.5 GET /performance/evaluations/{id} — 详情（只读）；越权 40302，不存在 40401 */
    @GetMapping("/performance/evaluations/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<PerformanceEvaluationResponse> detail(@PathVariable Long id) {
        return ApiResponse.success(performanceEvaluationService.get(id));
    }
}
