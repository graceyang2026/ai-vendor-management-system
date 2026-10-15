package com.srm.core.service.impl;

import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.lifecycle.LifecycleDecisionRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestCreateRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestResponse;
import com.srm.core.entity.LifecycleRequest;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.User;
import com.srm.core.mapper.LifecycleRequestMapper;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块二 §2.4/§2.5/§2.6 生命周期申请服务单元测试（契约 §2.4-§2.6 + 附录 A3，docs/api-spec.md §3）。
 *
 * 判序（与实现一致，逐道锁定，避免"错误码互相掩盖"）：
 * <ul>
 *   <li>create：40301 → 供应商 40401 → <b>仅建档人 requireSelf 40302「仅限本人操作」</b>（裁决 20261015，
 *       先于 type 码值/终态闸/门槛/在途预检，与 §2.1 submit 判序范式一致）→ type 码值 40001
 *       → <b>终态闸</b> 40302 → 类型-状态门槛 40302 → 在途预检 40902 → insert（DuplicateKey 转译同一
 *       40902）→ SUBMIT 审计；</li>
 *   <li>list：未登录 40101 → 40401 → 只读返回（<b>零审计、零写库</b>）；</li>
 *   <li>decide：40301 → 申请单 40401 → 申请单非 PENDING 40302 → decision 码值 40001 →
 *       version 缺失 40901 → APPROVE 分支重判门槛（供应商 40401 / 40302 / 未知类型 40001）
 *       → 申请单 updateById 0 行 40901 → 供应商 updateById 0 行 40901 → 决策审计。</li>
 * </ul>
 *
 * 单元层只锁"决策"：状态映射、版本传值、审计动作名、失败路径是否触达写库；
 * {@code @Version} 真自增、生成列 uk_supplier_active_lifecycle 并发兜底、按申请时间倒序的真实行序
 * 一律由 LifecycleRequestIntegrationTest 在 H2 上端到端锁定，本类不做替身式断言。
 *
 * RoleGuard 用真实实例包裹 mock provider（40301/40302 走生产同一实现，照 B1/B2 范式）。
 */
@ExtendWith(MockitoExtension.class)
class LifecycleRequestServiceImplTest {

    @Mock
    private LifecycleRequestMapper lifecycleRequestMapper;

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private AuditLogService auditLogService;

    private LifecycleRequestServiceImpl service;

    /** 契约固定文案，逐字比对，不接受模糊匹配 */
    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    private static final String MSG_REQUEST_NOT_FOUND = "资源不存在";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_IN_FLIGHT_EXISTS = "已存在在途申请，请勿重复提交";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_PARAM_INVALID = "参数校验失败";
    private static final String MSG_FORBIDDEN = "无权限";

    private static final long SUPPLIER_ID = 1001L;
    private static final long REQUEST_ID = 500L;

    private final UserPrincipal staff = new UserPrincipal(2L, "staff01", null, "张三", Role.STAFF, true);
    private final UserPrincipal auditor = new UserPrincipal(3L, "auditor01", null, "李四", Role.AUDITOR, true);
    private final UserPrincipal admin = new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);

    @BeforeEach
    void setUp() {
        service = new LifecycleRequestServiceImpl(lifecycleRequestMapper, supplierMapper, userMapper,
                new RoleGuard(currentUserProvider), currentUserProvider, auditLogService);
    }

    // ==================== 夹具 ====================

    private Supplier supplier(String status) {
        Supplier s = new Supplier();
        s.setId(SUPPLIER_ID);
        s.setName("杭州之江电镀有限公司");
        s.setTaxNo("91330100MA27XXXX8X");
        s.setType("MANUFACTURING");
        s.setStatus(status);
        s.setCreatedBy(staff.getId());
        s.setVersion(3);
        s.setCreatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setDeleted(0);
        return s;
    }

    private void stubSupplier(Supplier supplier) {
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier);
    }

    private LifecycleRequest requestRow(String type, String status, Integer version) {
        LifecycleRequest row = new LifecycleRequest();
        row.setId(REQUEST_ID);
        row.setSupplierId(SUPPLIER_ID);
        row.setType(type);
        row.setReason("连续两期绩效 D 级，暂停新订单分配。");
        row.setStatus(status);
        row.setVersion(version);
        row.setAppliedBy(staff.getId());
        row.setAppliedAt(LocalDateTime.of(2026, 10, 10, 9, 0));
        row.setCreatedAt(LocalDateTime.of(2026, 10, 10, 9, 0));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 10, 9, 0));
        row.setDeleted(0);
        return row;
    }

    private LifecycleRequestCreateRequest createRequest(String type, String reason) {
        LifecycleRequestCreateRequest req = new LifecycleRequestCreateRequest();
        req.setType(type);
        req.setReason(reason);
        return req;
    }

    private LifecycleDecisionRequest decisionRequest(String decision, String comment, Integer version) {
        LifecycleDecisionRequest req = new LifecycleDecisionRequest();
        req.setDecision(decision);
        req.setComment(comment);
        req.setVersion(version);
        return req;
    }

    private User user(Long id, String username, String realName) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setRealName(realName);
        return u;
    }

    /** 让 insert 像 MyBatis-Plus 一样回填自增主键，否则审计 entity_id 只会是 null（假绿） */
    private void stubInsertAssigningId() {
        when(lifecycleRequestMapper.insert(any(LifecycleRequest.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, LifecycleRequest.class).setId(REQUEST_ID);
            return 1;
        });
    }

    private static void expectCode(int code, String message, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .hasMessage(message)
                .extracting(ex -> ((BusinessException) ex).getCode().getValue())
                .isEqualTo(code);
    }

    // ==================== §2.4 create：闸门链 ====================

    @Test
    void createRejectsAuditorWith40301BeforeTouchingAnyMapper() {
        when(currentUserProvider.require()).thenReturn(auditor);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "绩效连续两期 D 级。")));

        verifyNoInteractions(supplierMapper, lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createRejectsAdminWith40301BecauseOnlyStaffMayApply() {
        when(currentUserProvider.require()).thenReturn(admin);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.create(SUPPLIER_ID, createRequest("ELIMINATE", "资不抵债。")));

        verifyNoInteractions(supplierMapper, lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createReturns40401WithSupplierDomainMessageWhenSupplierMissing() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(9999L)).thenReturn(null);

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND,
                () -> service.create(9999L, createRequest("SUSPEND", "测试理由。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"suspend", "SUSPEND_", "BLACKLIST", "", "PASS"})
    void createRejectsUnknownTypeCodeWith40001(String type) {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));

        expectCode(40001, MSG_PARAM_INVALID,
                () -> service.create(SUPPLIER_ID, createRequest(type, "测试理由。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createRejectsEveryTypeOnceSupplierIsEliminatedWith40302() {
        // 终态不可逆：ELIMINATED 之后 SUSPEND/RESUME/ELIMINATE 三种申请一律禁建（契约 §2.4 L443）
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("ELIMINATED"));

        for (String type : List.of("SUSPEND", "RESUME", "ELIMINATE")) {
            expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                    () -> service.create(SUPPLIER_ID, createRequest(type, "测试理由。")));
        }

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "PENDING_REVIEW", "RETURNED"})
    void createRejectsTypesOutsideNormalOrSuspendedWith40302(String supplierStatus) {
        // 存续期之外的三态（准入区）不允许进入生命周期流转：SUSPEND 只认 NORMAL、RESUME 只认 SUSPENDED
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier(supplierStatus));

        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "测试理由。")));
        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.create(SUPPLIER_ID, createRequest("RESUME", "整改完成。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createRejectsSuspendOnSuspendedAndResumeOnNormalBecauseTypeThresholdIsExact() {
        when(currentUserProvider.require()).thenReturn(staff);

        stubSupplier(supplier("SUSPENDED"));
        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "再次停用。")));

        stubSupplier(supplier("NORMAL"));
        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.create(SUPPLIER_ID, createRequest("RESUME", "恢复合作。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createRejectsEliminateOnReturnedSupplierWith40302() {
        // 门槛表的 ELIMINATE 行两个条件都为假（RETURNED 属准入区）：补齐 || 的 false-false 分支
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("RETURNED"));

        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.create(SUPPLIER_ID, createRequest("ELIMINATE", "退回未重提也要淘汰？")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createAllowsEliminateFromBothNormalAndSuspended() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubInsertAssigningId();
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));

        stubSupplier(supplier("NORMAL"));
        assertThat(service.create(SUPPLIER_ID, createRequest("ELIMINATE", "资不抵债，依据破产裁定。"))
                .getStatus()).isEqualTo("PENDING");

        stubSupplier(supplier("SUSPENDED"));
        assertThat(service.create(SUPPLIER_ID, createRequest("ELIMINATE", "停用期满仍未整改。"))
                .getStatus()).isEqualTo("PENDING");

        verify(lifecycleRequestMapper, org.mockito.Mockito.times(2)).insert(any(LifecycleRequest.class));
    }

    @Test
    void createRejectsInFlightSameTypeWith40902AndNeverInsertsOrAudits() {
        // 应用层预检分支（友好码）：同供应商同类型已有 PENDING 在途
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        when(lifecycleRequestMapper.selectCount(any())).thenReturn(1L);

        expectCode(40902, MSG_IN_FLIGHT_EXISTS,
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "测试理由。")));

        verify(lifecycleRequestMapper, never()).insert(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void createTranslatesDuplicateKeyFromGeneratedColumnTo40902WithoutLeakingSql() {
        // 预检通过后的并发窗口：DB 生成列唯一索引兜底，异常必须折成同一业务码，不得冒 50001
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        doThrow(new org.springframework.dao.DuplicateKeyException(
                "Duplicate entry '1001_SUSPEND' for key 'uk_supplier_active_lifecycle'"))
                .when(lifecycleRequestMapper).insert(any(LifecycleRequest.class));

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "测试理由。")));

        assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS);
        assertThat(ex.getMessage()).isEqualTo(MSG_IN_FLIGHT_EXISTS);
        assertThat(ex.getMessage()).doesNotContain("uk_supplier_active_lifecycle", "Duplicate entry");
        verifyNoInteractions(auditLogService);
    }

    // ==================== §2.4 create：仅建档人闸（用户裁决 20261015 收紧） ====================

    @Test
    void createRejectsNonCreatorStaffWith40302SelfMessageAndWritesNothing() {
        // 契约 §2.4 L440 / api-spec §3 L324：supplier.created_by != 当前用户 → 40302，
        // 口径复用 RoleGuard.requireSelf，与 §1.2 编辑他人档案、§1.5 删除一致（「仅限本人操作」）
        UserPrincipal otherStaff = new UserPrincipal(9L, "staff09", null, "王二", Role.STAFF, true);
        when(currentUserProvider.require()).thenReturn(otherStaff);
        stubSupplier(supplier("NORMAL"));   // created_by = staff(2L) ≠ otherStaff(9L)

        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "协同提单要被判拒。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createSelfGatePrecedesTerminalAndTypeThresholdsBecauseNonCreatorMustNotProbeStatusGates() {
        // 判序锁定：角色 → 存在性 → 仅建档人 → 类型-状态门槛。终态/门槛同样 40302 但文案不同，
        // 非建档人必须先在归属闸折返「仅限本人操作」，不得提前泄露状态门槛信息
        UserPrincipal otherStaff = new UserPrincipal(9L, "staff09", null, "王二", Role.STAFF, true);
        when(currentUserProvider.require()).thenReturn(otherStaff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("ELIMINATED"));

        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("RESUME", "被淘汰的也要替别人提。")));

        stubSupplier(supplier("NORMAL"));
        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("RESUME", "NORMAL 上非法 RESUME。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createSelfGatePrecedesInFlightCheckSoDuplicateNeverMasksOwnership() {
        // 归属闸先于在途预检：他人已建 PENDING 的供应商，非建档人拿到的是 40302「仅限本人操作」，
        // 而不是 40902——否则等于替别人探测在途状态；且 selectCount 一次都不该被打
        UserPrincipal otherStaff = new UserPrincipal(9L, "staff09", null, "王二", Role.STAFF, true);
        when(currentUserProvider.require()).thenReturn(otherStaff);
        stubSupplier(supplier("NORMAL"));

        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "换个身份蹭在途单。")));

        verify(lifecycleRequestMapper, never()).selectCount(any());
        verify(lifecycleRequestMapper, never()).insert(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void createSelfGatePrecedesTypeCodeGateSoOwnershipWinsOverIllegalCode() {
        // 归属闸先于类型码值二道闸：非建档人 + 未知码值也判 40302，不判 40001（§2.1 submit 同构判序）
        UserPrincipal otherStaff = new UserPrincipal(9L, "staff09", null, "王二", Role.STAFF, true);
        when(currentUserProvider.require()).thenReturn(otherStaff);
        stubSupplier(supplier("NORMAL"));

        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("BLACKLIST", "非法码值也不该先报。")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void createRejectsSupplierWithNullCreatedByBecauseOwnershipCannotBeProven() {
        // requireSelf 对 ownerId=null 判拒（脏数据不得默认放行），同样折 40302「仅限本人操作」
        when(currentUserProvider.require()).thenReturn(staff);
        Supplier orphan = supplier("NORMAL");
        orphan.setCreatedBy(null);
        stubSupplier(orphan);

        expectCode(40302, "仅限本人操作",
                () -> service.create(SUPPLIER_ID, createRequest("SUSPEND", "无主档案也放行？")));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    // ==================== §2.4 create：落库形态与出参 ====================

    @Test
    void createPersistsPendingRequestWithVersionOneAndNeverTouchesSupplierStatus() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        stubInsertAssigningId();
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));

        LifecycleResponseAsserts.assertPendingNewRequest(service.create(
                SUPPLIER_ID, createRequest("SUSPEND", "  连续两期绩效 D 级，暂停新订单分配。  ")),
                lifecycleRequestMapper);

        // 两步流程铁律：提申请这一跳物理上碰不到供应商主表
        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verify(supplierMapper, never()).insert(any(Supplier.class));
    }

    @Test
    void createWritesExactlyOneSubmitAuditWithSupplierIdAndNullOldStatus() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("SUSPENDED"));
        stubInsertAssigningId();
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));

        service.create(SUPPLIER_ID, createRequest("RESUME", "整改完成，申请恢复合作。"));

        verify(auditLogService).record(eq(EntityType.LIFECYCLE_REQUEST), eq(REQUEST_ID), eq(SUPPLIER_ID),
                eq(staff), eq("SUBMIT"), isNull(), eq("PENDING"), eq(AuditResult.SUCCESS), isNull());
        verifyNoMoreAuditWrites();
    }

    @Test
    void createResponsePrefersRealNameThenUsernameThenEmptyAndAlwaysNullDecidedTriple() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        stubInsertAssigningId();

        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));
        LifecycleRequestResponse byRealName = service.create(SUPPLIER_ID, createRequest("SUSPEND", "理由一。"));
        assertThat(byRealName.getAppliedByName()).isEqualTo("张三");

        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "  "));
        LifecycleRequestResponse byUsername = service.create(SUPPLIER_ID, createRequest("SUSPEND", "理由二。"));
        assertThat(byUsername.getAppliedByName()).isEqualTo("staff01");

        when(userMapper.selectById(staff.getId())).thenReturn(null);
        LifecycleRequestResponse noUser = service.create(SUPPLIER_ID, createRequest("SUSPEND", "理由三。"));
        assertThat(noUser.getAppliedByName()).isEmpty();

        // 未决单的决策痕迹三枚恒 null（附录 A3 可选字段）
        assertThat(noUser.getDecidedBy()).isNull();
        assertThat(noUser.getDecidedByName()).isNull();
        assertThat(noUser.getDecidedAt()).isNull();
        assertThat(noUser.getDecisionComment()).isNull();
        assertThat(noUser.getVersion()).isEqualTo(1);
        assertThat(noUser.getStatus()).isEqualTo("PENDING");
        assertThat(noUser.getSupplierId()).isEqualTo(SUPPLIER_ID);
        assertThat(noUser.getReason()).isEqualTo("理由三。");
    }

    // ==================== §2.5 list：只读 ====================

    @Test
    void listRejectsAnonymousWith40101() {
        when(currentUserProvider.require()).thenThrow(
                new BusinessException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期"));

        expectCode(40101, "未登录或登录已过期", () -> service.listBySupplier(SUPPLIER_ID));

        verifyNoInteractions(lifecycleRequestMapper, supplierMapper, auditLogService);
    }

    @Test
    void listReturns40401WhenSupplierMissing() {
        when(currentUserProvider.require()).thenReturn(admin);
        when(supplierMapper.selectById(9999L)).thenReturn(null);

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND, () -> service.listBySupplier(9999L));

        verifyNoInteractions(lifecycleRequestMapper, auditLogService);
    }

    @Test
    void listReturnsEmptyListWithoutAnyUserLookupWhenNoRequestExists() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        when(lifecycleRequestMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertThat(service.listBySupplier(SUPPLIER_ID)).isEmpty();

        verifyNoInteractions(userMapper, auditLogService);
    }

    @Test
    void listKeepsMapperOrderAndCarriesVersionForDecisionChain() {
        // 倒序由 SQL orderBy 决定（H2 集成层锁定真实行序）；单元层锁"不重排 + version 全量透传"
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("SUSPENDED"));
        LifecycleRequest newer = requestRow("SUSPEND", "APPROVED", 2);
        newer.setId(502L);
        newer.setAppliedAt(LocalDateTime.of(2026, 10, 12, 8, 0));
        newer.setDecidedBy(auditor.getId());
        newer.setDecidedAt(LocalDateTime.of(2026, 10, 13, 10, 0));
        newer.setDecisionComment("确认停用。");
        LifecycleRequest older = requestRow("ELIMINATE", "PENDING", 1);
        older.setId(501L);
        older.setAppliedAt(LocalDateTime.of(2026, 10, 10, 8, 0));
        when(lifecycleRequestMapper.selectList(any())).thenReturn(List.of(newer, older));
        when(userMapper.selectBatchIds(any())).thenReturn(
                List.of(user(2L, "staff01", "张三"), user(3L, "auditor01", "李四")));

        List<LifecycleRequestResponse> rows = service.listBySupplier(SUPPLIER_ID);

        assertThat(rows).extracting(LifecycleRequestResponse::getId).containsExactly(502L, 501L);
        assertThat(rows).extracting(LifecycleRequestResponse::getVersion).containsExactly(2, 1);
        assertThat(rows.get(0).getAppliedByName()).isEqualTo("张三");
        assertThat(rows.get(0).getDecidedByName()).isEqualTo("李四");
        assertThat(rows.get(0).getDecidedAt()).isEqualTo("2026-10-13T10:00:00");
        assertThat(rows.get(0).getAppliedAt()).isEqualTo("2026-10-12T08:00:00");
        assertThat(rows.get(1).getDecidedBy()).isNull();
        assertThat(rows.get(1).getDecidedByName()).isEmpty();
        verify(userMapper).selectBatchIds(any());        // 批量反查一次，绝不多打 N 次
        verify(userMapper, never()).selectById(anyLong());
        verifyNoInteractions(auditLogService);            // 只读接口不写审计（契约 §2.5 L493）
        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
    }

    @Test
    void listFallsBackToEmptyNameWhenUserRowIsMissingAndNeverNpe() {
        when(currentUserProvider.require()).thenReturn(staff);
        stubSupplier(supplier("NORMAL"));
        when(lifecycleRequestMapper.selectList(any())).thenReturn(List.of(requestRow("SUSPEND", "PENDING", 1)));
        when(userMapper.selectBatchIds(any())).thenReturn(Collections.emptyList());

        List<LifecycleRequestResponse> rows = service.listBySupplier(SUPPLIER_ID);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAppliedByName()).isEmpty();
        assertThat(rows.get(0).getDecidedByName()).isEmpty();
    }

    // ==================== §2.6 decide：闸门链 ====================

    @Test
    void decideRejectsStaffWith40301BecauseOnlyAuditorMayDecide() {
        when(currentUserProvider.require()).thenReturn(staff);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", null, 1)));

        verifyNoInteractions(lifecycleRequestMapper, supplierMapper, auditLogService);
    }

    @Test
    void decideReturns40401WithGenericMessageWhenRequestMissing() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(9999L)).thenReturn(null);

        expectCode(40401, MSG_REQUEST_NOT_FOUND,
                () -> service.decide(9999L, decisionRequest("APPROVE", null, 1)));

        verifyNoInteractions(supplierMapper, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void decideRejectsAlreadyDecidedRequestWith40302BeforeOptimisticLock(String status) {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", status, 2));

        // 传脏版本也不能改判错误原因：状态闸必须先于乐观锁
        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", null, 99)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"approve", "PASS", "DELETE", ""})
    void decideRejectsIllegalDecisionCodeWith40001(String decision) {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));

        expectCode(40001, MSG_PARAM_INVALID,
                () -> service.decide(REQUEST_ID, decisionRequest(decision, "意见", 1)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void decideTreatsMissingVersionAs40901Not40001() {
        // 契约 §2.6 L512 与 api-spec §3 L318：不回传或版本不匹配一律 40901（口径冲突以 api-spec 为权威）
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "意见", null)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void decideReturns40901WhenRequestVersionIsStaleAndWritesNothing() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        // 门槛链全绿（供应商存在且 NORMAL、版本由前端从 §2.5 读到），只让申请单那一跳影响 0 行，
        // 才能把 40901 判给"申请单版本脏"，而不是被前面的 40401/40302 掩盖
        stubSupplier(supplier("NORMAL"));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(0);

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意停用。", 7)));

        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verifyNoInteractions(auditLogService);
    }

    // ==================== §2.6 decide：APPROVE 联动主表 ====================

    @Test
    void decideApproveSuspendLinksSupplierToSuspended() {
        assertApproveLinksStatus("SUSPEND", "NORMAL", "SUSPENDED");
    }

    @Test
    void decideApproveResumeLinksSupplierToNormal() {
        assertApproveLinksStatus("RESUME", "SUSPENDED", "NORMAL");
    }

    @Test
    void decideApproveEliminateLinksSupplierToEliminated() {
        assertApproveLinksStatus("ELIMINATE", "SUSPENDED", "ELIMINATED");
    }

    private void assertApproveLinksStatus(String type, String fromStatus, String expectedSupplierStatus) {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow(type, "PENDING", 1));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(1);
        Supplier supplier = supplier(fromStatus);
        stubSupplier(supplier);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));
        when(userMapper.selectById(auditor.getId())).thenReturn(user(3L, "auditor01", "李四"));

        LifecycleRequestResponse response = service.decide(REQUEST_ID,
                decisionRequest("APPROVE", "确认停用，暂停新订单分配。", 1));

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getVersion()).isEqualTo(1);   // 入参版本原样回显，DB 自增由集成层锁定
        assertThat(response.getDecidedBy()).isEqualTo(auditor.getId());
        assertThat(response.getDecidedByName()).isEqualTo("李四");
        assertThat(response.getAppliedByName()).isEqualTo("张三");
        assertThat(response.getDecisionComment()).isEqualTo("确认停用，暂停新订单分配。");
        assertThat(response.getDecidedAt()).isNotBlank();

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(expectedSupplierStatus);
        assertThat(captor.getValue().getId()).isEqualTo(SUPPLIER_ID);

        verify(auditLogService).record(eq(EntityType.LIFECYCLE_REQUEST), eq(REQUEST_ID), eq(SUPPLIER_ID),
                eq(auditor), eq("DECISION_APPROVE"), eq("PENDING"), eq("APPROVED"),
                eq(AuditResult.SUCCESS), eq("确认停用，暂停新订单分配。"));
    }

    @Test
    void decideApproveReJudgesThresholdAndReturns40302WhenSupplierWasConcurrentlyEliminated() {
        // 建单后供应商已被并发淘汰：L554 的 40302 落在决策重判这一跳，绝不能只信建单快照
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        stubSupplier(supplier("ELIMINATED"));

        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意。", 1)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void decideApproveReturns40401WhenSupplierRowDisappeared() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(null);

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意。", 1)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void decideApproveWithUnknownTypeStoredInDbFallsBackTo40001InsteadOfGuessing() {
        // 库内脏类型绝不默认当成某一种类型（未知码值走门槛表的 default 分支）
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("BLACKLIST", "PENDING", 1));
        stubSupplier(supplier("NORMAL"));

        expectCode(40001, MSG_PARAM_INVALID,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意。", 1)));

        verify(lifecycleRequestMapper, never()).updateById(any(LifecycleRequest.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void decideApproveRollsBackOptimisticallyWhenSupplierRowIsStale() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(1);
        stubSupplier(supplier("NORMAL"));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(0);

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意。", 1)));

        // 审计不落：影响 0 行即抛，事务回滚会把申请单那一跳一起抹掉（不允许"半改"留痕）
        verifyNoInteractions(auditLogService);
    }

    // ==================== §2.6 decide：REJECT 只改申请单 ====================

    @Test
    void decideRejectKeepsSupplierStatusAndVersionUntouchedButStillWritesDecisionTrace() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(1);
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));
        when(userMapper.selectById(auditor.getId())).thenReturn(user(3L, "auditor01", "李四"));

        LifecycleRequestResponse response = service.decide(REQUEST_ID,
                decisionRequest("REJECT", "停用理由不充分，继续观察一期。", 1));

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(response.getDecisionComment()).isEqualTo("停用理由不充分，继续观察一期。");

        // REJECT 分支连供应商主表都不该读（既不改状态也不占乐观锁版本）
        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verify(supplierMapper, never()).selectById(anyLong());
        verify(lifecycleRequestMapper).updateById(any(LifecycleRequest.class));

        ArgumentCaptor<LifecycleRequest> captor = ArgumentCaptor.forClass(LifecycleRequest.class);
        verify(lifecycleRequestMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDecidedBy()).isEqualTo(auditor.getId());
        assertThat(captor.getValue().getDecidedAt()).isNotNull();
        assertThat(captor.getValue().getVersion()).isEqualTo(1);   // 传的是申请单版本，非供应商版本

        verify(auditLogService).record(eq(EntityType.LIFECYCLE_REQUEST), eq(REQUEST_ID), eq(SUPPLIER_ID),
                eq(auditor), eq("DECISION_REJECT"), eq("PENDING"), eq("REJECTED"),
                eq(AuditResult.SUCCESS), eq("停用理由不充分，继续观察一期。"));
    }

    @Test
    void decideRejectAcceptsMissingCommentBecauseContractMarksItOptional() {
        // 契约 §2.6 字段表 comment 必填=否（与 §2.2/§2.3"驳回必须给理由"不同域，不得套用）
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("RESUME", "PENDING", 1));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(1);
        when(userMapper.selectById(staff.getId())).thenReturn(user(2L, "staff01", "张三"));
        when(userMapper.selectById(auditor.getId())).thenReturn(user(3L, "auditor01", "李四"));

        LifecycleRequestResponse response = service.decide(REQUEST_ID,
                decisionRequest("REJECT", "   ", 1));

        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(response.getDecisionComment()).isNull();     // 纯空白等同未填写，不落 "   "

        verify(auditLogService).record(eq(EntityType.LIFECYCLE_REQUEST), eq(REQUEST_ID), eq(SUPPLIER_ID),
                eq(auditor), eq("DECISION_REJECT"), eq("PENDING"), eq("REJECTED"),
                eq(AuditResult.SUCCESS), isNull());
    }

    @Test
    void decideNeverWritesBareDecisionCodeToAuditLog() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(lifecycleRequestMapper.selectById(REQUEST_ID)).thenReturn(requestRow("SUSPEND", "PENDING", 1));
        when(lifecycleRequestMapper.updateById(any(LifecycleRequest.class))).thenReturn(1);
        stubSupplier(supplier("NORMAL"));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);

        service.decide(REQUEST_ID, decisionRequest("APPROVE", "同意。", 1));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(EntityType.class), anyLong(), anyLong(), any(UserPrincipal.class),
                action.capture(), any(), any(), any(AuditResult.class), any());
        assertThat(action.getValue()).isNotIn(List.of("APPROVE", "REJECT", "approve", "PASS"));
        assertThat(action.getValue()).isEqualTo("DECISION_APPROVE");
    }

    private void verifyNoMoreAuditWrites() {
        verify(auditLogService, org.mockito.Mockito.times(1)).record(any(EntityType.class), anyLong(),
                anyLong(), any(UserPrincipal.class), any(), any(), any(), any(AuditResult.class), any());
    }

    /** 建单落库形态断言：PENDING + version=1 + 决策痕迹三枚 NULL + 申请人/时间就位 */
    private static final class LifecycleResponseAsserts {

        static void assertPendingNewRequest(LifecycleRequestResponse response,
                                            LifecycleRequestMapper mapper) {
            assertThat(response.getStatus()).isEqualTo("PENDING");
            assertThat(response.getVersion()).isEqualTo(1);
            assertThat(response.getReason()).isEqualTo("连续两期绩效 D 级，暂停新订单分配。");

            ArgumentCaptor<LifecycleRequest> captor = ArgumentCaptor.forClass(LifecycleRequest.class);
            verify(mapper).insert(captor.capture());
            LifecycleRequest row = captor.getValue();
            assertThat(row.getStatus()).isEqualTo("PENDING");
            assertThat(row.getVersion()).isEqualTo(1);
            assertThat(row.getSupplierId()).isEqualTo(SUPPLIER_ID);
            assertThat(row.getType()).isEqualTo("SUSPEND");
            assertThat(row.getReason()).isEqualTo("连续两期绩效 D 级，暂停新订单分配。"); // 已 trim
            assertThat(row.getAppliedBy()).isNotNull();
            assertThat(row.getAppliedAt()).isNotNull();
            assertThat(row.getCreatedAt()).isNotNull();
            assertThat(row.getUpdatedAt()).isNotNull();
            assertThat(row.getDeleted()).isZero();
            assertThat(row.getDecidedBy()).isNull();
            assertThat(row.getDecidedAt()).isNull();
            assertThat(row.getDecisionComment()).isNull();
        }

        private LifecycleResponseAsserts() {
        }
    }
}
