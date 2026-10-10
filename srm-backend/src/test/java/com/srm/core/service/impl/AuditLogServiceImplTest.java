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

    /**
     * 兜底分支：entityType / operator / result 全为 null 时（系统触发、无上下文的操作），
     * 必须仍写入一条记录，且 result 缺省补 SUCCESS，不得 NPE 也不得静默丢弃。
     */
    @Test
    void recordToleratesNullEntityTypeOperatorAndResult() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        auditLogService.record(null, 55L, null, "EXPORT", null, null, null, "无操作者上下文");

        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityType()).isNull();
        assertThat(saved.getEntityId()).isEqualTo(55L);
        assertThat(saved.getOperatorId()).isNull();
        assertThat(saved.getOperatorRole()).isNull();
        // result=null 被缺省补成 SUCCESS：调用方漏传时会留下"成功"假象，此处锁定该既有行为
        assertThat(saved.getResult()).isEqualTo("SUCCESS");
    }

    /** 操作者有对象但 role 为空（历史数据/JWT 未带角色）：operator_role 落 null，显式 result 不被覆盖 */
    @Test
    void recordHandlesOperatorWithoutRoleAndKeepsExplicitResult() {
        UserPrincipal operatorWithoutRole =
                new UserPrincipal(9L, "system", null, "系统", null, true);
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        auditLogService.record(EntityType.SUPPLIER, 12L, operatorWithoutRole, "AUDIT_REJECT",
                "PENDING_AUDIT", "RETURNED", AuditResult.REJECTED, "资质过期");

        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getOperatorId()).isEqualTo(9L);
        assertThat(saved.getOperatorRole()).isNull();
        assertThat(saved.getResult()).isEqualTo("REJECTED");
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

    /** entity_type 多值用例的公共夹具：返回空页，只关心下推的查询条件 */
    private void stubEmptyPage() {
        when(auditLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AuditLog> page = invocation.getArgument(0);
            page.setRecords(List.of());
            page.setTotal(0L);
            return page;
        });
    }

    /** 取回实际传给 selectPage 的 wrapper，便于断言下推条件 */
    @SuppressWarnings("unchecked")
    private AbstractWrapper<AuditLog, ?, ?> captureUsedWrapper() {
        ArgumentCaptor<Wrapper<AuditLog>> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(auditLogMapper).selectPage(any(), wrapperCaptor.capture());
        return (AbstractWrapper<AuditLog, ?, ?>) wrapperCaptor.getValue();
    }

    /**
     * 缺陷回归：审计员【操作与审批日志】页不得混入系统管理员（USER 域）日志。
     * 契约（api-spec §5）：entity_type 逗号分隔多值下推为 IN 过滤；
     * 空白/纯逗号解析后为空集则不加任何 entity_type 条件（退化为全量）。
     */
    @Test
    void listSupportsCommaSeparatedEntityTypesAsInFilter() {
        stubEmptyPage();

        // 前端审核员页实发值 SUPPLIER,PERFORMANCE_EVALUATION,LIFECYCLE_REQUEST，此处故意带空格以验证 trim
        auditLogService.list("SUPPLIER, PERFORMANCE_EVALUATION ,LIFECYCLE_REQUEST", null, 1, 10);

        AbstractWrapper<AuditLog, ?, ?> usedWrapper = captureUsedWrapper();
        // 先触发 getSqlSegment 使惰性参数物化（MP paramNameValuePairs 延迟填充），再断言内容
        String sqlSegment = usedWrapper.getSqlSegment();

        // IN 必须落在 entity_type 列上，且入参只经 #{} 占位符下推（SQL 文本内不得出现字面量，杜绝拼接注入面）
        assertThat(sqlSegment).containsPattern("entity_type\\s+IN\\s*\\(");
        assertThat(sqlSegment).containsPattern("#\\{ew\\.paramNameValuePairs\\.MPGENVAL\\d+}");
        assertThat(sqlSegment).doesNotContain("'");
        // 三个逗号值全部作为 IN 参数下推（含 trim），USER 域不在其中
        assertThat(usedWrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder("SUPPLIER", "PERFORMANCE_EVALUATION", "LIFECYCLE_REQUEST")
                .doesNotContain("USER");
    }

    @Test
    void listIgnoresBlankCommaOnlyEntityTypeFilter() {
        stubEmptyPage();

        auditLogService.list(" , ", null, 1, 10);

        AbstractWrapper<AuditLog, ?, ?> usedWrapper = captureUsedWrapper();
        // 纯逗号解析为空集：不得追加任何 entity_type 条件（既不 eq 也不 IN，更不能生成非法的 IN ()）；先物化 segment
        String blankSegment = usedWrapper.getSqlSegment();
        assertThat(blankSegment).doesNotContain("entity_type");
        assertThat(blankSegment).doesNotContain("IN");
        assertThat(usedWrapper.getParamNameValuePairs()).isEmpty();
    }

    /**
     * 零回归：引入逗号多值后，单值仍必须是 entity_type 等值过滤而非 IN，
     * 管理员日志页传 USER、业务员页传 SUPPLIER 的既有行为不受影响。
     */
    @Test
    void listKeepsSingleValueTypeAsEqualityFilter() {
        stubEmptyPage();

        auditLogService.list("USER", null, 1, 10);

        AbstractWrapper<AuditLog, ?, ?> usedWrapper = captureUsedWrapper();
        String sqlSegment = usedWrapper.getSqlSegment();

        assertThat(sqlSegment).containsPattern("entity_type\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.MPGENVAL\\d+}");
        assertThat(sqlSegment).doesNotContain("IN");
        assertThat(usedWrapper.getParamNameValuePairs().values()).containsExactly("USER");
    }

    /**
     * 反查操作人姓名的两条兜底：real_name 为空必须回退 username；
     * 批量结果出现同一 id 的重复行时保留第一条，不得抛 IllegalStateException。
     */
    @Test
    void listFallsBackToUsernameAndKeepsFirstOnDuplicateOperatorId() {
        when(auditLogMapper.selectPage(any(), any())).thenAnswer(invocation -> {
            Page<AuditLog> page = invocation.getArgument(0);
            page.setRecords(List.of(dbRow(1L, 3L), dbRow(2L, 4L), dbRow(3L, 3L)));
            page.setTotal(3L);
            return page;
        });
        when(userMapper.selectBatchIds(any())).thenAnswer(invocation -> List.of(
                namedUser(3L, "审核员李四", "lisi"),
                namedUser(3L, "重复脏数据", "lisi-dup"),
                namedUser(4L, "", "wangwu")));

        PageResult<AuditLogResponse> result = auditLogService.list("SUPPLIER", 9L, 1, 10);

        assertThat(result.getList()).hasSize(3);
        // id 冲突保留第一条
        assertThat(result.getList().get(0).getOperatorName()).isEqualTo("审核员李四");
        // real_name 为空字符串时回退 username
        assertThat(result.getList().get(1).getOperatorName()).isEqualTo("wangwu");
        assertThat(result.getList().get(2).getOperatorName()).isEqualTo("审核员李四");
    }

    private static User namedUser(Long id, String realName, String username) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        user.setUsername(username);
        return user;
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
