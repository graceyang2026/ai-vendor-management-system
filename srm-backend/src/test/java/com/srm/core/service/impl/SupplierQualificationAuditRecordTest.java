package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.srm.core.audit.AuditLog;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.enums.Role;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * 契约源：docs/api-spec.md §0「目标态 DDL 增量」③（裁决 20261012 增 SUPPLIER_QUALIFICATION）
 * 与 §2 接口 #10 `PUT /qualifications/{id}/review`。
 * 锁定：存续期补传资质单独审批的留痕以 SUPPLIER_QUALIFICATION 为审计对象，
 * 且落库 wire 值必须等于枚举 name()（§5 的 entity_type 过滤按该字符串等值/IN 匹配）。
 */
@ExtendWith(MockitoExtension.class)
class SupplierQualificationAuditRecordTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @Mock
    private UserMapper userMapper;

    private AuditLogServiceImpl auditLogService;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), AuditLog.class);
    }

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogServiceImpl(auditLogMapper, userMapper);
    }

    private UserPrincipal auditor() {
        return new UserPrincipal(3L, "auditor01", "n/a", "审核员李四", Role.AUDITOR, true);
    }

    @Test
    void standaloneQualificationReviewIsRecordedUnderSupplierQualificationDomain() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        // 存续期补传件单独审批通过：PENDING → APPROVED（api-spec §2 #10）
        auditLogService.record(EntityType.SUPPLIER_QUALIFICATION, 501L, auditor(), "REVIEW_APPROVE",
                "PENDING", "APPROVED", AuditResult.SUCCESS, "营业执照补传件核验通过");

        verify(auditLogMapper).insert(captor.capture());
        AuditLog saved = captor.getValue();
        assertThat(saved.getEntityType()).isEqualTo("SUPPLIER_QUALIFICATION");
        assertThat(saved.getEntityId()).isEqualTo(501L);
        assertThat(saved.getOperatorRole()).isEqualTo("AUDITOR");
        assertThat(saved.getOldStatus()).isEqualTo("PENDING");
        assertThat(saved.getNewStatus()).isEqualTo("APPROVED");
    }

    /**
     * 语义分界（裁决 20261012）：准入期资质随 `POST /suppliers/{id}/audit` 连带批复的留痕归 SUPPLIER 域，
     * 不得占用 SUPPLIER_QUALIFICATION；两个枚举值彼此独立，防止后续实现把留痕写串。
     */
    @Test
    void onboardingQualificationApprovalStayUnderSupplierDomain() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);

        auditLogService.record(EntityType.SUPPLIER, 77L, auditor(), "AUDIT_APPROVE",
                "PENDING_REVIEW", "NORMAL", AuditResult.SUCCESS, "准入审核通过（含随件资质连带批复）");

        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getEntityType()).isEqualTo("SUPPLIER");
        assertThat(EntityType.SUPPLIER).isNotEqualTo(EntityType.SUPPLIER_QUALIFICATION);
    }
}
