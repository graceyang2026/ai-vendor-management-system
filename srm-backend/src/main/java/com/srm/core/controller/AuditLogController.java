package com.srm.core.controller;

import com.srm.core.common.ApiResponse;
import com.srm.core.common.PageResult;
import com.srm.core.dto.auditlog.AuditLogResponse;
import com.srm.core.service.AuditLogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计日志查询接口（docs/api-spec.md 第 5 节）。
 * 权限：允许所有已登录角色访问（SecurityConfig anyRequest().authenticated() 覆盖），
 * 后端不做行级隔离，前端按业务范围过滤展示。
 */
@RestController
@RequestMapping("/api/v1")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResult<AuditLogResponse>> list(
            @RequestParam(name = "entity_type", required = false) String entityType,
            @RequestParam(name = "entity_id", required = false) Long entityId,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "page_size", defaultValue = "10") int pageSize) {
        return ApiResponse.success(auditLogService.list(entityType, entityId, page, pageSize));
    }
}
