package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationReviewRequest;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块一 §1.7/§1.8 QualificationService 单元测试
 * （契约：docs/接口契约文档-供应商与绩效-20261013.md §1.7/§1.8/附录 A2 + docs/api-spec.md §2 资质小节）。
 *
 * 锁定行为：
 * 1. create：角色 40301 → 存在性 40401 → 本人 40302（先于状态门槛，锁定判序）→ 状态门槛 40302
 *    （允许集 DRAFT/RETURNED/NORMAL，裁决 20261011）→ 日期语义 40001；
 *    新记录一律 status=PENDING、version=1（裁决 20261012，不直接生效）。
 * 2. create 出参 expired：expiry_date &lt; 今天 的同表派生（用户口径 2026-10-14：本批必实现，
 *    不在二期豁免——豁免仅指跨表三枚）。
 * 3. list：§0 隔离按裁决 2026-10-14 晚口径只卡 STAFF（他人私有在途区 40302；AUDITOR/ADMIN 全量）；
 *    REJECTED 行排除条件<b>下推 SQL 且仅对非建档本人的 STAFF 追加</b>
 *    （契约"REJECTED 仅供本人追溯" × 裁决 AUDITOR/ADMIN 全量的组合口径，见类尾说明）。
 * 4. 每行携带 version（§2.3 入参取值链）与 expired；审核痕迹三列原样映射（本批恒 null）。
 * 5. create 同类型在途顶替（裁决 20261015，契约 §1.7 业务约束新增行）：全部校验通过后、insert 前
 *    先逻辑删除同 supplier + 同 doc_type 的旧 PENDING 行；校验失败路径绝不触达顶替（delete 不得被调用）。
 *
 * 6.（模块二 §2.3 新增）review：判序 40301 → 资质 40401 → 供应商 40401 → 供应商非 NORMAL 40302
 *    → 资质非 PENDING 40302 → action 码值 40001 → REJECT 缺意见 40001 → version 缺失 40901
 *    → updateById 0 行 40901 / DuplicateKey 40902；APPROVE 分支才做同类型旧件退位，
 *    审计动作必带 REVIEW_ 前缀并冗余 supplier_id（契约 §2.3 L382-384/L413-419）。
 *
 * 单元层只能锁定"wrapper 是否追加了 status 排除条件"这类<b>决策</b>；
 * 真实行过滤结果、@Version 自增、生成列唯一索引竞态由集成测试在 H2 上端到端锁定。
 */
@ExtendWith(MockitoExtension.class)
class QualificationServiceImplTest {

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private SupplierQualificationMapper qualificationMapper;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private AuditLogService auditLogService;

    private QualificationServiceImpl service;

    /** 契约固定/自拟文案常量——断言逐字比对，不接受模糊匹配 */
    private static final String MSG_NOT_FOUND = "供应商不存在";
    private static final String MSG_NOT_SELF = "仅限本人操作";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_DATE = "日期格式不合法或失效日期早于生效日期";

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierQualification.class);
    }

    @BeforeEach
    void setUp() {
        // RoleGuard 用真实实例包裹 mock provider：40301/40302 语义走生产同一实现（照 B1 范式）
        service = new QualificationServiceImpl(supplierMapper, qualificationMapper,
                new RoleGuard(currentUserProvider), currentUserProvider, auditLogService);
    }

    // ==================== 夹具 ====================

    private static UserPrincipal staffOwner() {
        return new UserPrincipal(2L, "staff01", null, "张三", Role.STAFF, true);
    }

    private static UserPrincipal staffOther() {
        return new UserPrincipal(9L, "staff09", null, "王二", Role.STAFF, true);
    }

    private static UserPrincipal auditor() {
        return new UserPrincipal(3L, "auditor01", null, "李四", Role.AUDITOR, true);
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);
    }

    private static Supplier supplier(Long id, String status, Long createdBy) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName("杭州之江电镀有限公司");
        s.setTaxNo("91330100MA27XXXX8X");
        s.setType("MANUFACTURING");
        s.setStatus(status);
        s.setCreatedBy(createdBy);
        s.setVersion(1);
        s.setCreatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setDeleted(0);
        return s;
    }

    private static QualificationCreateRequest validRequest() {
        QualificationCreateRequest req = new QualificationCreateRequest();
        req.setDocType("BUSINESS_LICENSE");
        req.setFileUrl("/uploads/2026/10/license-abc.pdf");
        req.setEffectiveDate("2026-01-01");
        req.setExpiryDate("2028-12-31");
        return req;
    }

    private static SupplierQualification row(Long id, String status, String docType,
                                             String expiryDate, Integer version) {
        SupplierQualification q = new SupplierQualification();
        q.setId(id);
        q.setSupplierId(1001L);
        q.setDocType(docType);
        q.setFileUrl("/uploads/2026/10/" + docType.toLowerCase() + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(expiryDate == null ? null : LocalDate.parse(expiryDate));
        q.setStatus(status);
        q.setVersion(version);
        q.setCreatedAt(LocalDateTime.of(2026, 10, 14, 9, 30));
        q.setDeleted(0);
        return q;
    }

    /** §2.3 请求体夹具（字段名 action / review_comment / version，契约 §2.3 L393-407） */
    private static QualificationReviewRequest reviewRequest(String action, String comment, Integer version) {
        QualificationReviewRequest req = new QualificationReviewRequest();
        req.setAction(action);
        req.setReviewComment(comment);
        req.setVersion(version);
        return req;
    }

    private static SupplierQualification pendingQualOf(long supplierId) {
        SupplierQualification q = row(501L, "PENDING", "BUSINESS_LICENSE", "2028-12-31", 1);
        q.setSupplierId(supplierId);
        return q;
    }

    private static BusinessException asBiz(Throwable ex) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        return (BusinessException) ex;
    }

    // ==================== §1.7 create：判序与守卫 ====================

    @Test
    void createWritesPendingVersionOneWithNullReviewFields() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));
        when(qualificationMapper.insert(any(SupplierQualification.class))).thenAnswer(inv -> {
            inv.getArgument(0, SupplierQualification.class).setId(501L);
            return 1;
        });

        QualificationResponse resp = service.create(1001L, validRequest());

        assertThat(resp.getId()).isEqualTo(501L);
        assertThat(resp.getSupplierId()).isEqualTo(1001L);
        assertThat(resp.getDocType()).isEqualTo("BUSINESS_LICENSE");
        assertThat(resp.getFileUrl()).isEqualTo("/uploads/2026/10/license-abc.pdf");
        // 裁决 20261012：新记录一律 PENDING + version=1，不直接生效
        assertThat(resp.getStatus()).isEqualTo("PENDING");
        assertThat(resp.getVersion()).isEqualTo(1);
        assertThat(resp.getEffectiveDate()).isEqualTo("2026-01-01");
        assertThat(resp.getExpiryDate()).isEqualTo("2028-12-31");
        // expired 同表派生：到期日在未来 → false（本批必须计算，非二期豁免位）
        assertThat(resp.getExpired()).isFalse();
        // 批复痕迹归模块二写入点：§1.7 新件三列恒 null
        assertThat(resp.getReviewedBy()).isNull();
        assertThat(resp.getReviewedAt()).isNull();
        assertThat(resp.getReviewComment()).isNull();
        assertThat(resp.getCreatedAt()).isNotNull();

        ArgumentCaptor<SupplierQualification> captor =
                ArgumentCaptor.forClass(SupplierQualification.class);
        verify(qualificationMapper).insert(captor.capture());
        SupplierQualification saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getSupplierId()).isEqualTo(1001L);
        assertThat(saved.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(saved.getExpiryDate()).isEqualTo(LocalDate.of(2028, 12, 31));
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getDeleted()).isEqualTo(0);
    }

    @Test
    void createAllowedOnOwnNormalSupplierUnlocksPostOnboardingMaintenance() {
        // 裁决 20261011：NORMAL 存续期允许补传（解除"准入后资质无法维护"死锁），仍落 PENDING
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.insert(any(SupplierQualification.class))).thenAnswer(inv -> {
            inv.getArgument(0, SupplierQualification.class).setId(502L);
            return 1;
        });

        QualificationResponse resp = service.create(1001L, validRequest());

        assertThat(resp.getStatus()).isEqualTo("PENDING");
        assertThat(resp.getVersion()).isEqualTo(1);
    }

    @Test
    void createAllowedOnOwnReturnedSupplier() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "RETURNED", 2L));
        when(qualificationMapper.insert(any(SupplierQualification.class))).thenAnswer(inv -> {
            inv.getArgument(0, SupplierQualification.class).setId(503L);
            return 1;
        });

        assertThat(service.create(1001L, validRequest()).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createMissingSupplierThrows40401AndSkipsInsert() {
        // @TableLogic 使已逻辑删除行在 selectById 直接隐身 → 同一 40401 分支（集成层再验真库行为）
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(null);

        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.getMessage()).isEqualTo(MSG_NOT_FOUND);
                });

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createOnOthersSupplierChecksSelfBeforeStatusGateThrows40302() {
        // 他人 PENDING_REVIEW 档案：两道 40302 闸门同时命中，判序锁定"先归属后状态"（派单口径）
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "PENDING_REVIEW", 9L));

        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
                    assertThat(ex.getMessage()).isEqualTo(MSG_NOT_SELF);
                });

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createOnOwnPendingReviewSupplierThrows40302ContractMessage() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "PENDING_REVIEW", 2L));

        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
                    // §1.7 错误码矩阵无 Message 列，取契约 §2.1 已给的 40302 全局固定文案
                    assertThat(ex.getMessage()).isEqualTo(MSG_STATUS_NOT_ALLOWED);
                });
    }

    @Test
    void createOnSuspendedOrEliminatedSupplierThrows40302() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "SUSPENDED", 2L));
        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "ELIMINATED", 2L));
        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createByNonStaffRoleThrows40301BeforeAnyDbAccess() {
        when(currentUserProvider.require()).thenReturn(auditor());

        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo("无权限");
                });

        verifyNoInteractions(supplierMapper);
        verifyNoInteractions(qualificationMapper);
    }

    // ==================== §1.7 create：日期语义（Controller 层后的第二道闸） ====================

    @Test
    void createSemanticInvalidDateThrows40001NotLeaking50001() {
        // 2026-02-30 能过 yyyy-MM-dd 正则，但 LocalDate.parse 抛异常——必须折 40001 契约文案
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        QualificationCreateRequest req = validRequest();
        req.setExpiryDate("2026-02-30");

        assertThatThrownBy(() -> service.create(1001L, req))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo(MSG_DATE);
                });

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createMalformedOrBlankDateThrows40001() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        QualificationCreateRequest malformed = validRequest();
        malformed.setEffectiveDate("2026/01/01");
        assertThatThrownBy(() -> service.create(1001L, malformed))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        QualificationCreateRequest blank = validRequest();
        blank.setExpiryDate("  ");
        assertThatThrownBy(() -> service.create(1001L, blank))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createExpiryNotStrictlyAfterEffectiveThrows40001() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        // 契约 §1.7："expiry_date 晚于生效"——相等也是非法（非"不早于"）
        QualificationCreateRequest same = validRequest();
        same.setEffectiveDate("2028-12-31");
        same.setExpiryDate("2028-12-31");
        assertThatThrownBy(() -> service.create(1001L, same))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo(MSG_DATE);
                });

        QualificationCreateRequest reversed = validRequest();
        reversed.setEffectiveDate("2028-12-31");
        reversed.setExpiryDate("2026-01-01");
        assertThatThrownBy(() -> service.create(1001L, reversed))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createWithPastExpiryStillMarksExpiredTrueForTraceability() {
        // 上传历史证照补录：expiry_date < 今天 → expired=true（派生只看日期比较，与 PENDING 状态无关）
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.insert(any(SupplierQualification.class))).thenAnswer(inv -> {
            inv.getArgument(0, SupplierQualification.class).setId(504L);
            return 1;
        });

        QualificationCreateRequest req = validRequest();
        req.setEffectiveDate("2024-01-01");
        req.setExpiryDate("2025-01-01");

        assertThat(service.create(1001L, req).getExpired()).isTrue();
    }

    // ==================== §1.7 create：同类型在途顶替（裁决 20261015，契约 §1.7 业务约束） ====================

    @Test
    void createReplacesSameDocTypePendingRowBeforeInsert() {
        // 顶替判序不可逆：delete(旧同类型 PENDING) 严格先于 insert(新行)；
        // @TableLogic 下 BaseMapper.delete(wrapper) 即 UPDATE deleted=1（物理行保留），集成层在真库上实证
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.insert(any(SupplierQualification.class))).thenAnswer(inv -> {
            inv.getArgument(0, SupplierQualification.class).setId(505L);
            return 1;
        });

        service.create(1001L, validRequest());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SupplierQualification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        InOrder inOrder = inOrder(qualificationMapper);
        inOrder.verify(qualificationMapper).delete(captor.capture());
        inOrder.verify(qualificationMapper).insert(any(SupplierQualification.class));

        LambdaQueryWrapper<SupplierQualification> wrapper = captor.getValue();
        String sql = wrapper.getSqlSegment(); // 触发惰性物化（照 list 侧实测范式）
        assertThat(sql).contains("supplier_id");
        assertThat(sql).contains("doc_type");
        assertThat(sql).contains("status");
        // 三 eq 参数钉死：本供应商 + 请求 doc_type + 仅 PENDING——APPROVED/REJECTED 不在匹配集内
        assertThat(wrapper.getParamNameValuePairs().values())
                .containsExactlyInAnyOrder(1001L, "BUSINESS_LICENSE", "PENDING");
    }

    @Test
    void createDoesNotReplaceWhenDateSemanticsInvalid() {
        // 40001（日期语义）必须先于顶替失败：坏报文绝不损毁旧在途件
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        QualificationCreateRequest reversed = validRequest();
        reversed.setEffectiveDate("2028-12-31");
        reversed.setExpiryDate("2026-01-01");
        assertThatThrownBy(() -> service.create(1001L, reversed))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(qualificationMapper, never()).delete(any());
        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    @Test
    void createDoesNotReplaceWhenStatusGateOrOwnershipRejects() {
        // 40302（状态门槛）同样必须先于顶替失败；归属拒绝的 never-delete 由
        // createOnOthersSupplierChecksSelfBeforeStatusGateThrows40302 + 本批 never() 语义共同兜底
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "PENDING_REVIEW", 2L));
        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 9L));
        assertThatThrownBy(() -> service.create(1001L, validRequest()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
                    assertThat(ex.getMessage()).isEqualTo(MSG_NOT_SELF);
                });

        verify(qualificationMapper, never()).delete(any());
        verify(qualificationMapper, never()).insert(any(SupplierQualification.class));
    }

    // ==================== §1.8 list：隔离判据与 REJECTED 排除决策 ====================

    private LambdaQueryWrapper<SupplierQualification> capturedListWrapper() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<SupplierQualification>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(qualificationMapper).selectList(captor.capture());
        captor.getValue().getSqlSegment(); // 触发惰性物化（照 B1 实测范式）
        return captor.getValue();
    }

    @Test
    void listScopesQueryToRequestedSupplier() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.list(1001L);

        assertThat(capturedListWrapper().getSqlSegment()).contains("supplier_id");
    }

    @Test
    void listNonOwnerStaffPushesRejectedExclusionIntoSql() {
        // 契约 §1.8"默认 APPROVED+PENDING、REJECTED 仅建档本人"×裁决"AUDITOR/ADMIN 全量"：
        // 非本人 STAFF → 后端把 status IN(APPROVED,PENDING) 下推 SQL，禁止依赖前端过滤
        when(currentUserProvider.require()).thenReturn(staffOther());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.list(1001L);

        String segment = capturedListWrapper().getSqlSegment();
        assertThat(segment).contains("status");
        assertThat(segment).contains("IN");
    }

    @Test
    void listOwnerStaffGetsNoRejectedExclusion() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.list(1001L);

        assertThat(capturedListWrapper().getSqlSegment()).doesNotContain("status");
    }

    @Test
    void listAuditorAndAdminGetFullVisibilityIncludingRejectedWithoutRuleAGate() {
        // 裁决 2026-10-14 晚：AUDITOR 不受 §0 规则 A 约束 → 他人私有在途区也放行且不排 REJECTED；
        // ADMIN 同享（api-spec §2 "AUDITOR/ADMIN 为全量"）
        for (UserPrincipal principal : List.of(auditor(), admin())) {
            org.mockito.Mockito.reset(qualificationMapper);
            when(currentUserProvider.require()).thenReturn(principal);
            when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));
            when(qualificationMapper.selectList(any())).thenReturn(Collections.emptyList());

            service.list(1001L); // 不得抛 40302

            assertThat(capturedListWrapper().getSqlSegment()).doesNotContain("status");
        }
    }

    @Test
    void listOtherStaffOnPrivateInFlightSupplierThrows40302() {
        when(currentUserProvider.require()).thenReturn(staffOther());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        assertThatThrownBy(() -> service.list(1001L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
                    assertThat(ex.getMessage()).isEqualTo(MSG_NOT_SELF);
                });

        verify(qualificationMapper, never()).selectList(any());
    }

    @Test
    void listOtherStaffOnReturnedSupplierAlsoThrows40302() {
        when(currentUserProvider.require()).thenReturn(staffOther());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "RETURNED", 2L));

        assertThatThrownBy(() -> service.list(1001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));
    }

    @Test
    void listMissingSupplierThrows40401() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(null);

        assertThatThrownBy(() -> service.list(1001L))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(ex.getMessage()).isEqualTo(MSG_NOT_FOUND);
                });
    }

    // ==================== §1.8 list：行映射（version/expired/审核痕迹） ====================

    @Test
    void listMapsAllContractFieldsIncludingVersionAndExpiredDerivation() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        SupplierQualification approvedExpired = row(501L, "APPROVED", "BUSINESS_LICENSE",
                LocalDate.now().minusDays(1).toString(), 2);
        approvedExpired.setReviewedBy(3L);
        approvedExpired.setReviewedAt(LocalDateTime.of(2026, 10, 2, 15, 0));
        approvedExpired.setReviewComment("证照模糊无法辨识有效期，请重新上传。");
        SupplierQualification pendingValid = row(502L, "PENDING", "OTHER",
                LocalDate.now().plusYears(1).toString(), 1);
        when(qualificationMapper.selectList(any())).thenReturn(List.of(approvedExpired, pendingValid));

        List<QualificationResponse> rows = service.list(1001L);

        assertThat(rows).hasSize(2);
        QualificationResponse first = rows.get(0);
        // expired 同表派生：昨天到期 → true；APPROVED 行的审核痕迹三列原样输出
        assertThat(first.getExpired()).isTrue();
        assertThat(first.getStatus()).isEqualTo("APPROVED");
        assertThat(first.getVersion()).isEqualTo(2);
        assertThat(first.getReviewedBy()).isEqualTo(3L);
        assertThat(first.getReviewedAt()).isEqualTo("2026-10-02T15:00:00");
        assertThat(first.getReviewComment()).isEqualTo("证照模糊无法辨识有效期，请重新上传。");
        assertThat(first.getEffectiveDate()).isEqualTo("2026-01-01");
        assertThat(first.getCreatedAt()).isEqualTo("2026-10-14T09:30:00");
        // 未到期 PENDING 行：expired=false，审核痕迹 null（尚未批复）
        QualificationResponse second = rows.get(1);
        assertThat(second.getExpired()).isFalse();
        assertThat(second.getVersion()).isEqualTo(1);
        assertThat(second.getReviewedBy()).isNull();
        assertThat(second.getReviewedAt()).isNull();
        assertThat(second.getReviewComment()).isNull();
    }

    @Test
    void listWithNullExpiryRowReturnsExpiredFalseWithoutNpe() {
        // DDL 允许 expiry_date NULL（存量兼容）：派生比较必须空安全，null 不算过期
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.selectList(any()))
                .thenReturn(List.of(row(503L, "PENDING", "OTHER", null, 1)));

        List<QualificationResponse> rows = service.list(1001L);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getExpired()).isFalse();
        assertThat(rows.get(0).getExpiryDate()).isNull();
    }

    @Test
    void listReturnsEmptyArrayWhenNoRows() {
        when(currentUserProvider.require()).thenReturn(staffOwner());
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));
        when(qualificationMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(service.list(1001L)).isEmpty();
    }

    // ==================== §2.3 review 存续期资质单独审批 ====================

    @Test
    void reviewApproveWritesApprovedWithReviewTraceAndRetirePreviousApprovedRow() {
        // 存续期件：供应商 NORMAL + 资质 PENDING 才允许进入本接口（契约 §2.3 L382）
        when(currentUserProvider.require()).thenReturn(auditor());
        SupplierQualification q = pendingQualOf(1001L);
        when(qualificationMapper.selectById(501L)).thenReturn(q);
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class))).thenReturn(1);

        QualificationResponse resp = service.review(501L, reviewRequest("APPROVE", "证照清晰，予以通过。", 1));

        assertThat(resp.getStatus()).isEqualTo("APPROVED");
        // 单测层无 @Version 拦截器，出参版本即入参版本；真实自增由集成测试在 H2 上锁定
        assertThat(resp.getVersion()).isEqualTo(1);
        assertThat(resp.getReviewedBy()).isEqualTo(3L);
        assertThat(resp.getReviewComment()).isEqualTo("证照清晰，予以通过。");
        assertThat(resp.getExpired()).isFalse();

        ArgumentCaptor<SupplierQualification> captor = ArgumentCaptor.forClass(SupplierQualification.class);
        verify(qualificationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getReviewedAt()).isNotNull();
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
        // APPROVE 分支必做同类型旧 APPROVED 件退位（生效位让路，且必须早于本件更新）
        InOrder ordered = inOrder(qualificationMapper);
        ordered.verify(qualificationMapper).delete(any());
        ordered.verify(qualificationMapper).updateById(any(SupplierQualification.class));

        verify(auditLogService).record(eq(EntityType.SUPPLIER_QUALIFICATION), eq(501L), eq(1001L),
                any(UserPrincipal.class), eq("REVIEW_APPROVE"), eq("PENDING"), eq("APPROVED"),
                eq(AuditResult.SUCCESS), eq("证照清晰，予以通过。"));
    }

    @Test
    void reviewApproveWithoutCommentSucceedsAndWritesNullComment() {
        // review_comment 只在 REJECT 时必填：APPROVE 分支可省略（契约 §2.3 字段表 L406「条件必填」）
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class))).thenReturn(1);

        assertThat(service.review(501L, reviewRequest("APPROVE", null, 1)).getStatus()).isEqualTo("APPROVED");

        verify(auditLogService).record(eq(EntityType.SUPPLIER_QUALIFICATION), eq(501L), eq(1001L),
                any(UserPrincipal.class), eq("REVIEW_APPROVE"), eq("PENDING"), eq("APPROVED"),
                eq(AuditResult.SUCCESS), isNull());
    }

    @Test
    void reviewRejectWritesRejectedAndNeverRetiresOldApprovedRow() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class))).thenReturn(1);

        QualificationResponse resp = service.review(501L,
                reviewRequest("REJECT", "证照模糊无法辨识有效期，请重新上传。", 1));

        assertThat(resp.getStatus()).isEqualTo("REJECTED");
        assertThat(resp.getReviewComment()).isEqualTo("证照模糊无法辨识有效期，请重新上传。");
        // api-spec L281「批复真空」约束：驳回绝不动旧生效位
        verify(qualificationMapper, never()).delete(any());
        verify(auditLogService).record(eq(EntityType.SUPPLIER_QUALIFICATION), eq(501L), eq(1001L),
                any(UserPrincipal.class), eq("REVIEW_REJECT"), eq("PENDING"), eq("REJECTED"),
                eq(AuditResult.SUCCESS), eq("证照模糊无法辨识有效期，请重新上传。"));
    }

    @Test
    void reviewByNonAuditorReturns40301AndTouchesNothing() {
        when(currentUserProvider.require()).thenReturn(staffOwner());

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(ex.getMessage()).isEqualTo("无权限");
        verifyNoInteractions(qualificationMapper, supplierMapper, auditLogService);
    }

    @Test
    void reviewQualificationNotFoundReturns40401() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = asBiz(catchThrowable(() -> service.review(999L,
                reviewRequest("APPROVE", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        // 契约 §2.3 L427 矩阵原文即泛义「资源不存在」；资质域无契约登记的专属文案，不自拟
        assertThat(ex.getMessage()).isEqualTo("资源不存在");
        verifyNoInteractions(supplierMapper, auditLogService);
    }

    @Test
    void reviewOrphanQualificationWhoseSupplierGoneReturns40401SupplierMessage() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(null);

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND);
        // 供应商域 40401 文案由契约 §0 L36 权威注记锁定为「供应商不存在」，跨接口不得漂移
        assertThat(ex.getMessage()).isEqualTo("供应商不存在");
    }

    @Test
    void reviewOnDraftSupplierReturns40302BecauseAdmissionCasesMustGoAudit() {
        // 裁决 20261012 范围限定：准入期件只能随 §2.2 连带批复，本接口只服务 NORMAL 存续期件
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "DRAFT", 2L));

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
        assertThat(ex.getMessage()).isEqualTo(MSG_STATUS_NOT_ALLOWED);
        verify(qualificationMapper, never()).updateById(any(SupplierQualification.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewAlreadyApprovedQualificationReturns40302() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L))
                .thenReturn(row(501L, "APPROVED", "BUSINESS_LICENSE", "2028-12-31", 2));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 2))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
        verify(qualificationMapper, never()).updateById(any(SupplierQualification.class));
    }

    @Test
    void reviewStatusGateIsJudgedBeforeVersionSoDirtyVersionOnDecidedRowStill40302() {
        // 判序锁定：状态闸门先于乐观锁，避免"已决件 + 脏版本"回成 40901 掩盖真实原因
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(row(501L, "REJECTED", "OTHER", null, 5));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        assertThat(asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("REJECT", "理由", 999))))
                .getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED);
    }

    @Test
    void reviewUnknownActionReturns40001AndNeverTreatsItAsReject() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("PASS", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(ex.getMessage()).isEqualTo("参数校验失败");
        verify(qualificationMapper, never()).updateById(any(SupplierQualification.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewRejectWithoutCommentOrWithBlankCommentReturns40001AndChangesNothing() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        BusinessException missing = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("REJECT", null, 1))));
        assertThat(missing.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(missing.getMessage()).isEqualTo("驳回时必须填写意见");
        assertThat(asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("REJECT", "   ", 1)))).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("REJECT", "", 1)))).getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(qualificationMapper, never()).updateById(any(SupplierQualification.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewMissingVersionReturns40901() {
        // version 缺失属并发语义而非格式语义，与 §2.1 候选 B 同口径
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, null))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        verify(qualificationMapper, never()).updateById(any(SupplierQualification.class));
    }

    @Test
    void reviewStaleVersionReturns40901AndWritesNoAudit() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class))).thenReturn(0);

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 7))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        assertThat(ex.getMessage()).isEqualTo("数据已被修改，请刷新后重试");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewApproveHittingApprovedUniqueIndexReturns40902() {
        // 生效位并发竞态：两个 AUDITOR 同时批不同件，DB 唯一索引兜底，DuplicateKey 必须转译 40902
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class)))
                .thenThrow(new DuplicateKeyException("uk_supplier_approved_doc"));

        BusinessException ex = asBiz(catchThrowable(() -> service.review(501L,
                reviewRequest("APPROVE", null, 1))));
        assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS);
        assertThat(ex.getMessage()).isEqualTo("业务冲突");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewTrimsCommentBeforeWritingItBack() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(qualificationMapper.selectById(501L)).thenReturn(pendingQualOf(1001L));
        when(supplierMapper.selectById(1001L)).thenReturn(supplier(1001L, "NORMAL", 2L));
        when(qualificationMapper.updateById(any(SupplierQualification.class))).thenReturn(1);

        QualificationResponse resp = service.review(501L, reviewRequest("REJECT", "  证照模糊  ", 1));

        assertThat(resp.getReviewComment()).isEqualTo("证照模糊");
        verify(auditLogService).record(any(EntityType.class), anyLong(), anyLong(),
                any(UserPrincipal.class), any(), any(), any(), any(), eq("证照模糊"));
    }
}
