package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditLog;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.auditlog.AuditLogResponse;
import com.srm.core.entity.User;
import com.srm.core.mapper.AuditLogMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.UserPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块 4 审计日志服务单元测试（契约：docs/api-spec.md 第 5 节）。
 * 核心锁定两条铁律：record 绝不向上抛异常；list 动态过滤 + created_at 降序 + 反查操作人姓名。
 */
@ExtendWith(MockitoExtension.class)
class AuditLogServiceImplTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private UserMapper userMapper;

    private AuditLogServiceImpl auditLogService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        // 纯单元测试没有 SqlSession，需手动初始化实体的 TableInfo 缓存，
        // 否则 LambdaQueryWrapper 解析 AuditLog::getXxx 列名时报 "can not find TableInfo"。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLog.class);
    }

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogServiceImpl(auditLogMapper, userMapper);
    }

    private UserPrincipal operator() {
        return new UserPrincipal(2L, "staff01", "n/a", "采购员张三", Role.STAFF, true);
    }

    /**
     * 【核心隔离铁律】Mapper insert 抛任何异常，record 必须静默吞掉并只打 error 日志，
     * 绝不允许把异常传给主业务导致审核/提交事务回滚。
     */
    @Test
    void recordNeverPropagatesMapperFailures() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> auditLogService.record(
                EntityType.SUPPLIER, 100L, operator(), "SUBMIT",
                "DRAFT", "PENDING_AUDIT", AuditResult.SUCCESS, "提交审核"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordPersistsAllContractFields() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        auditLogService.record(EntityType.LIFECYCLE_REQUEST, 77L, operator(), "AUDIT_APPROVE",
                "PENDING_AUDIT", "APPROVED", AuditResult.SUCCESS, "资料齐全，通过");

        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("LIFECYCLE_REQUEST");
        assertThat(saved.getEntityId()).isEqualTo(77L);
        assertThat(saved.getOperatorId()).isEqualTo(2L);
        assertThat(saved.getOperatorRole()).isEqualTo("STAFF");
        assertThat(saved.getAction()).isEqualTo("AUDIT_APPROVE");
        assertThat(saved.getOldStatus()).isEqualTo("PENDING_AUDIT");
        assertThat(saved.getNewStatus()).isEqualTo("APPROVED");
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
        assertThat(saved.getComment()).isEqualTo("资料齐全，通过");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    private AuditLog dbRow(long id, long operatorId) {
        AuditLog row = new AuditLog();
        row.setId(id);
        row.setEntityType("SUPPLIER");
        row.setEntityId(9L);
        row.setOperatorId(operatorId);
        row.setOperatorRole("AUDITOR");
        row.setAction("AUDIT_REJECT");
        row.setOldStatus("PENDING_AUDIT");
        row.setNewStatus("RETURNED");
        row.setResult("REJECTED");
        row.setComment("资质过期");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 8, 12, 0));
        return row;
    }

    @Test
    void listFiltersByEntityTypeAndIdAndSortsByCreatedAtDesc() {
        when(auditLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AuditLog> page = invocation.getArgument(0);
            page.setRecords(List.of(dbRow(2L, 3L), dbRow(1L, 3L)));
            page.setTotal(2L);
            return page;
        });
        when(userMapper.selectBatchIds(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> ids = invocation.getArgument(0);
            User user = new User();
            user.setId(3L);
            user.setRealName("审核员李四");
            return ids.contains(3L) ? List.of(user) : List.of();
        });

        PageResult<AuditLogResponse> result = auditLogService.list("SUPPLIER", 9L, 1, 10);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AuditLog>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(auditLogMapper).selectPage(any(), wrapperCaptor.capture());

        // 动态条件：entity_type / entity_id 均下推为等值条件，且按 created_at 降序
        AbstractWrapper<AuditLog, ?, ?> usedWrapper =
                (AbstractWrapper<AuditLog, ?, ?>) wrapperCaptor.getValue();
        assertThat(usedWrapper.getSqlSegment())
                .contains("ORDER BY")
                .contains("created_at");
        assertThat(usedWrapper.getParamNameValuePairs().values())
                .contains("SUPPLIER", 9L);

        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);
        AuditLogResponse first = result.getList().get(0);
        assertThat(first.getId()).isEqualTo(2L);
        assertThat(first.getEntityType()).isEqualTo("SUPPLIER");
        assertThat(first.getOperatorName()).isEqualTo("审核员李四");
        assertThat(first.getOperatorRole()).isEqualTo("AUDITOR");
        assertThat(first.getResult()).isEqualTo("REJECTED");
    }

    @Test
    void listWithoutFiltersSkipsUserLookupForEmptyPage() {
        when(auditLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AuditLog> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });

        PageResult<AuditLogResponse> result = auditLogService.list(null, null, 2, 5);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getPage()).isEqualTo(2);
        // 空结果不触发反查用户，且过滤条件为空时不生成 where 参数
        verifyNoInteractions(userMapper);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AuditLog>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(auditLogMapper).selectPage(any(), wrapperCaptor.capture());
        AbstractWrapper<AuditLog, ?, ?> emptyFilterWrapper =
                (AbstractWrapper<AuditLog, ?, ?>) wrapperCaptor.getValue();
        assertThat(emptyFilterWrapper.getParamNameValuePairs()).isEmpty();
    }
}
