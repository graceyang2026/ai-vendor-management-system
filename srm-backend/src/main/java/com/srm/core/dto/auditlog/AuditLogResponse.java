package com.srm.core.dto.auditlog;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 审计日志响应 DTO（docs/api-spec.md 第 5 节 AuditLogResponse）。
 * 所有字段显式 @JsonProperty 下划线格式，不依赖全局 SNAKE_CASE 配置。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;

    @JsonProperty("entity_type")
    private String entityType;

    @JsonProperty("entity_id")
    private Long entityId;

    @JsonProperty("operator_id")
    private Long operatorId;

    /** audit_log 表不存姓名，查询时经 sys_user.real_name 反查填充 */
    @JsonProperty("operator_name")
    private String operatorName;

    @JsonProperty("operator_role")
    private String operatorRole;

    private String action;

    @JsonProperty("old_status")
    private String oldStatus;

    @JsonProperty("new_status")
    private String newStatus;

    private String result;

    private String comment;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
