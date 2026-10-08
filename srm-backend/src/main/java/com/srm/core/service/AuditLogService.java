package com.srm.core.service;

import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.PageResult;
import com.srm.core.dto.auditlog.AuditLogResponse;
import com.srm.core.security.UserPrincipal;

/**
 * 审计日志服务（模块 4，docs/api-spec.md 第 5 节）。
 */
public interface AuditLogService {

    /**
     * 【核心隔离铁律】写入审计日志。本方法绝不向调用方抛出任何异常：
     * 即使 DB 插入失败/超时也只记系统 error 日志，禁止导致主业务事务回滚或报错。
     */
    void record(EntityType entityType, Long entityId, UserPrincipal operator,
                String action, String oldStatus, String newStatus,
                AuditResult result, String comment);

    /**
     * 分页查询审计日志：entityType/entityId 可选动态过滤，created_at 降序。
     */
    PageResult<AuditLogResponse> list(String entityType, Long entityId, int page, int pageSize);
}
