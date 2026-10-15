package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.common.PageResult;
import com.srm.core.dto.lifecycle.LifecycleDecisionRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestCreateRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestResponse;
import com.srm.core.dto.lifecycle.LifecycleRequestTodoItem;
import com.srm.core.service.LifecycleRequestService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模块二 §2.4/§2.5/§2.6/§2.7 生命周期申请接口（契约 §2.4-§2.7 + 附录 A3，docs/api-spec.md §3）。
 *
 * 与 SupplierController / QualificationController 同一分层惯例：本类只做<b>入参层一次校验</b>
 * （{@code @Valid} → 单字段格式失败折 40001，随 HTTP 200 回传，用户裁决 2026-10-14 不动
 * GlobalExceptionHandler 的传输口径）与角色粗粒度拦截（{@code @PreAuthorize} → Security 层
 * 403/40301）；类型-状态门槛、在途唯一、乐观锁与 §0 行级隔离全部由 LifecycleRequestServiceImpl
 * + RoleGuard 二验兜底，@PreAuthorize 不是唯一防线。
 *
 * 两步流程在路由层就是三道互不相通的门：
 * <ul>
 *   <li>§2.4 {@code POST /suppliers/{id}/lifecycle-requests}（STAFF）：只新建 PENDING 申请单，
 *       <b>请求体里没有 status、也没有 version</b>，物理上不存在"绕过申请直接改状态"的入口；</li>
 *   <li>§2.5 {@code GET /suppliers/{id}/lifecycle-requests}（三登录角色，只读）：按申请时间倒序，
 *       不写审计；每行携带的 version 是 §2.6 入参 version 的唯一合法取值链；</li>
 *   <li>§2.6 {@code POST /lifecycle-requests/{id}/decision}（AUDITOR）：路径参数是<b>申请单 ID</b>
 *       而非供应商 ID（申请单自带 supplier_id，无须二次定位；也避免前端拿供应商版本误判）。</li>
 * </ul>
 *
 * §2.7（裁决 20261015 增项）{@code GET /lifecycle-requests}（AUDITOR 专属，只读聚合）：
 * 跨供应商终审待办队列，与 §2.5 单供应商历史<b>共存不互代</b>；query 过滤/status/type/supplier_id
 * 的枚举码值合法性与角色二验全部在 Service 判定（40001/40301），路由层只声明角色闸与
 * 分页参数默认值（page=1、page_size=20，上限收敛口径与 §1.3 同一范式，不在路由层私设）。
 */
@RestController
@RequestMapping("/api/v1")
public class LifecycleRequestController {

    private final LifecycleRequestService lifecycleRequestService;

    public LifecycleRequestController(LifecycleRequestService lifecycleRequestService) {
        this.lifecycleRequestService = lifecycleRequestService;
    }

    /** §2.4 POST /suppliers/{id}/lifecycle-requests — 提申请（STAFF），绝不动供应商状态 */
    @PostMapping("/suppliers/{id}/lifecycle-requests")
    @PreAuthorize("hasRole('STAFF')")
    public ApiResponse<LifecycleRequestResponse> create(
            @PathVariable Long id,
            @RequestBody @Valid LifecycleRequestCreateRequest request) {
        return ApiResponse.success(lifecycleRequestService.create(id, request));
    }

    /** §2.5 GET /suppliers/{id}/lifecycle-requests — 申请历史（只读、不写审计），按申请时间倒序 */
    @GetMapping("/suppliers/{id}/lifecycle-requests")
    @PreAuthorize("hasAnyRole('ADMIN', 'STAFF', 'AUDITOR')")
    public ApiResponse<List<LifecycleRequestResponse>> list(@PathVariable Long id) {
        return ApiResponse.success(lifecycleRequestService.listBySupplier(id));
    }

    /**
     * §2.6 POST /lifecycle-requests/{id}/decision — 终审决策（AUDITOR 专属）。
     * 入参层只拦 decision 值域与 version 格式（40001）；「申请单必须 PENDING」「供应商已被并发
     * 淘汰」这两道 40302 闸门与 version 缺失/不匹配的 40901 一律在 Service 判定（契约 §2.6 L554-555）。
     */
    @PostMapping("/lifecycle-requests/{id}/decision")
    @PreAuthorize("hasRole('AUDITOR')")
    public ApiResponse<LifecycleRequestResponse> decide(
            @PathVariable Long id,
            @RequestBody @Valid LifecycleDecisionRequest request) {
        return ApiResponse.success(lifecycleRequestService.decide(id, request));
    }

    /**
     * §2.7 GET /lifecycle-requests — 跨供应商聚合查询（终审待办队列，AUDITOR 专属，只读、不写审计）。
     * query 全部可选：status/type 缺省不过滤（非法码值由 Service 折 40001「参数校验失败」，
     * 未知值绝不静默当成不过滤），supplier_id 收敛单供应商；分页口径与 §1.3 同一范式。
     * 返回 PageResult&lt;LifecycleRequestTodoItem&gt;（附录 A3 全 13 字段 + supplier_name/supplier_status），
     * 按 applied_at 倒序；每行 version 仍是 §2.6 决策入参的合法取值源（与 §2.5 同口径）。
     */
    @GetMapping("/lifecycle-requests")
    @PreAuthorize("hasRole('AUDITOR')")
    public ApiResponse<PageResult<LifecycleRequestTodoItem>> listTodo(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "supplier_id", required = false) Long supplierId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(lifecycleRequestService.listTodo(status, type, supplierId, page, pageSize));
    }
}
