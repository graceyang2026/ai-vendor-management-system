package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditLog;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.PageResult;
import com.srm.core.dto.auditlog.AuditLogResponse;
import com.srm.core.entity.User;
import com.srm.core.mapper.AuditLogMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计日志服务实现（模块 4）。
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    public AuditLogServiceImpl(AuditLogMapper auditLogMapper, UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    /**
     * 【核心隔离铁律】整个写入用 try-catch 完全包裹：
     * 任何 Exception（DB 插入失败、连接超时等）只打 error 日志，
     * 绝对禁止向上抛出，防止影响审核/提交/决策等主业务事务。
     */
    @Override
    public void record(EntityType entityType, Long entityId, UserPrincipal operator,
                       String action, String oldStatus, String newStatus,
                       AuditResult result, String comment) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setEntityType(entityType == null ? null : entityType.name());
            auditLog.setEntityId(entityId);
            if (operator != null) {
                auditLog.setOperatorId(operator.getId());
                auditLog.setOperatorRole(operator.getRole() == null ? null : operator.getRole().name());
            }
            auditLog.setAction(action);
            auditLog.setOldStatus(oldStatus);
            auditLog.setNewStatus(newStatus);
            auditLog.setResult(result == null ? AuditResult.SUCCESS.name() : result.name());
            auditLog.setComment(comment);
            auditLog.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(auditLog);
        } catch (Exception ex) {
            log.error("写入审计日志失败: ", ex);
        }
    }

    @Override
    public PageResult<AuditLogResponse> list(String entityType, Long entityId, int page, int pageSize) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(entityType)) {
            wrapper.eq(AuditLog::getEntityType, entityType);
        }
        if (entityId != null) {
            wrapper.eq(AuditLog::getEntityId, entityId);
        }
        wrapper.orderByDesc(AuditLog::getCreatedAt);

        Page<AuditLog> resultPage = auditLogMapper.selectPage(new Page<>(page, pageSize), wrapper);
        List<AuditLog> records = resultPage.getRecords();
        Map<Long, String> operatorNames = resolveOperatorNames(records);

        List<AuditLogResponse> items = records.stream()
                .map(row -> AuditLogResponse.builder()
                        .id(row.getId())
                        .entityType(row.getEntityType())
                        .entityId(row.getEntityId())
                        .operatorId(row.getOperatorId())
                        .operatorName(operatorNames.getOrDefault(row.getOperatorId(), ""))
                        .operatorRole(row.getOperatorRole())
                        .action(row.getAction())
                        .oldStatus(row.getOldStatus())
                        .newStatus(row.getNewStatus())
                        .result(row.getResult())
                        .comment(row.getComment())
                        .createdAt(row.getCreatedAt())
                        .build())
                .toList();

        return new PageResult<>(items, resultPage.getTotal(), page, pageSize);
    }

    /** audit_log 表只存 operator_id，姓名需批量反查 sys_user.real_name（缺失回退 username） */
    private Map<Long, String> resolveOperatorNames(List<AuditLog> records) {
        Set<Long> operatorIds = records.stream()
                .map(AuditLog::getOperatorId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (operatorIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(operatorIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername(),
                        (first, duplicate) -> first));
    }
}
