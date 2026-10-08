package com.srm.core.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体，映射 audit_log 表（schema.sql 第 7 节，只插入不更新）。
 * 注意：表无 operator_name 列，操作人姓名在查询响应时经 sys_user 反查（AuditLogResponse.operatorName）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** EntityType 枚举的 name() */
    private String entityType;

    private Long entityId;

    private Long operatorId;

    /** Role 枚举的 name()，如 ADMIN/STAFF/AUDITOR */
    private String operatorRole;

    /** AuditAction 枚举的 name()，如 SUBMIT/AUDIT_APPROVE */
    private String action;

    private String oldStatus;

    private String newStatus;

    /** AuditResult 枚举的 name()：SUCCESS/REJECTED */
    private String result;

    private String comment;

    private LocalDateTime createdAt;
}
