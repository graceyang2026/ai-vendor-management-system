package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.supplier.SupplierAuditDecisionRequest;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierUpdateRequest;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.service.AuditLogService;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块一 §1.1–§1.5 SupplierService 单元测试（契约：docs/接口契约文档-供应商与绩效-20261013.md）。
 * 锁定行为：
 * 1. create：DRAFT/version=1/created_by=当前用户；税号预检与 DB 兜底均折 40902；ADMIN → 40301
 * 2. update：40401/40302(非本人、非 DRAFT/RETURNED)/40901(rows=0)/40902(税号撞他人)；
 *    Partial 只改提交字段；日期与存量合并校验 40001
 * 3. list：仅 STAFF 强制 §0 规则 A 行级隔离（wrapper 内含 created_by 条件）；AUDITOR/ADMIN 全量
 *    （用户裁决 2026-10-14 晚：AUDITOR 不受 §0 规则 A 约束，与 api-spec §2 「AUDITOR/ADMIN 为全量」一致）
 * 4. get：仅 STAFF 访问他人私有在途区（DRAFT/RETURNED）→ 40302；AUDITOR/ADMIN 全量可见；
 *    不存在/已逻辑删除 → 40401
 * 5. delete：仅本人 DRAFT；逻辑删除走 deleteById（@TableLogic）
 * 6. 派生字段归二期（用户裁决 2026-10-14 16:15）：business_license_url / latest_performance_grade /
 *    risk_warning 一律 null，本批不得出现任何派生计算。
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceImplTest {

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private UserMapper userMapper;

    /** §2.2 连带批复与 §2.1 硬门槛的资质侧依赖（模块二新增构造参数） */
    @Mock
    private SupplierQualificationMapper qualificationMapper;

    /** 关键操作留痕依赖：单测层只验"写没写、写什么"，写入本体由 AuditLogServiceImplTest 覆盖 */
    @Mock
    private AuditLogService auditLogService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    private SupplierServiceImpl service;

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Supplier.class);
        // §2.2 连带批复要在单测层构造 LambdaQueryWrapper<SupplierQualification>，MP 需先初始化表信息
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SupplierQualification.class);
    }

    @BeforeEach
    void setUp() {
        // RoleGuard 用真实实例包裹 mock provider：错误码语义（40301/40302）走生产同一实现
        service = new SupplierServiceImpl(supplierMapper, userMapper, qualificationMapper,
                auditLogService, new RoleGuard(currentUserProvider), currentUserProvider);
    }

    // ==================== 夹具 ====================

    private static UserPrincipal staff() {
        return new UserPrincipal(2L, "staff01", null, "张三", Role.STAFF, true);
    }

    private static UserPrincipal admin() {
        return new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);
    }

    private static UserPrincipal auditor() {
        return new UserPrincipal(3L, "auditor01", null, "李四", Role.AUDITOR, true);
    }

    private static SupplierCreateRequest validCreateRequest() {
        SupplierCreateRequest req = new SupplierCreateRequest();
        req.setName("杭州之江电镀有限公司");
        req.setTaxNo("91330100MA27XXXX8X");
        req.setType("MANUFACTURING");
        req.setContactName("王强");
        req.setContactPhone("13800138000");
        req.setContactEmail("wang@example.com");
        req.setAddress("杭州市西湖区");
        req.setRemark("测试备注");
        req.setEffectiveDate("2026-01-01");
        req.setExpiryDate("2028-12-31");
        return req;
    }

    private static Supplier existing(Long id, String status, String taxNo, int version, Long createdBy) {
        Supplier s = new Supplier();
        s.setId(id);
        s.setName("杭州之江电镀有限公司");
        s.setTaxNo(taxNo);
        s.setType("MANUFACTURING");
        s.setContactName("王强");
        s.setContactPhone("13800138000");
        s.setContactEmail("wang@example.com");
        s.setAddress("杭州市西湖区");
        s.setRemark("测试备注");
        s.setEffectiveDate(LocalDate.of(2026, 1, 1));
        s.setExpiryDate(LocalDate.of(2028, 12, 31));
        s.setStatus(status);
        s.setCreatedBy(createdBy);
        s.setVersion(version);
        s.setCreatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setDeleted(0);
        return s;
    }

    private static User zhangSan() {
        User u = new User();
        u.setId(2L);
        u.setUsername("staff01");
        u.setRealName("张三");
        u.setRole("STAFF");
        u.setEnabled(true);
        return u;
    }

    private static BusinessException asBiz(Throwable ex) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        return (BusinessException) ex;
    }

    // ==================== §1.1 create ====================

    @Test
    void createDraftWithVersionOneAndCreatedByAndDerivedFieldsNull() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            inv.getArgument(0, Supplier.class).setId(100L);
            return 1;
        });
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierResponse resp = service.create(validCreateRequest());

        assertThat(resp.getId()).isEqualTo(100L);
        assertThat(resp.getStatus()).isEqualTo("DRAFT");
        assertThat(resp.getVersion()).isEqualTo(1);
        assertThat(resp.getCreatedBy()).isEqualTo(2L);
        assertThat(resp.getCreatedByName()).isEqualTo("张三");
        assertThat(resp.getTaxNo()).isEqualTo("91330100MA27XXXX8X");
        assertThat(resp.getEffectiveDate()).isEqualTo("2026-01-01");
        assertThat(resp.getExpiryDate()).isEqualTo("2028-12-31");
        // 派生字段归二期：物理字段必须在，但一律 null（用户裁决 2026-10-14 16:15）
        assertThat(resp.getBusinessLicenseUrl()).isNull();
        assertThat(resp.getLatestPerformanceGrade()).isNull();
        assertThat(resp.getRiskWarning()).isNull();

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).insert(captor.capture());
        Supplier saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getCreatedBy()).isEqualTo(2L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void createDuplicateTaxNoPrecheckThrows40902AndSkipsInsert() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.create(validCreateRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS));

        verify(supplierMapper, never()).insert(any(Supplier.class));
    }

    /** 并发兜底：预检通过后 DB uk_tax_no_active 拦截 → 必须转译 40902，不得原抛成 50001 */
    @Test
    void createDbDuplicateKeyBackstopTranslatedTo40902() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class)))
                .thenThrow(new DuplicateKeyException("uk_tax_no_active"));

        assertThatThrownBy(() -> service.create(validCreateRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS));
    }

    @Test
    void createByAdminBlockedWith40301() {
        when(currentUserProvider.require()).thenReturn(admin());

        assertThatThrownBy(() -> service.create(validCreateRequest()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(supplierMapper);
    }

    /** 建档人反查不到 sys_user（历史脏数据/外键逻辑关系）时 created_by_name 回退空串，不得 NPE */
    @Test
    void createWithMissingCreatorUserFallsBackToEmptyName() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            inv.getArgument(0, Supplier.class).setId(101L);
            return 1;
        });
        when(userMapper.selectById(2L)).thenReturn(null);

        SupplierResponse resp = service.create(validCreateRequest());
        assertThat(resp.getCreatedByName()).isEmpty();
    }

    // ==================== §1.2 update ====================

    private static SupplierUpdateRequest updateRequest(Integer version) {
        SupplierUpdateRequest req = new SupplierUpdateRequest();
        req.setVersion(version);
        return req;
    }

    @Test
    void updateNotFoundThrows40401() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(999L, updateRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void updateNotOwnerThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 9L));

        assertThatThrownBy(() -> service.update(1L, updateRequest(1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    /** 验收 §1.2：PENDING_REVIEW 期间乐观锁 + 状态拒绝双保险，STAFF 编辑必 40302 */
    @Test
    void updatePendingReviewThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L))
                .thenReturn(existing(1L, "PENDING_REVIEW", "91330100MA27XXXX8X", 3, 2L));

        assertThatThrownBy(() -> service.update(1L, updateRequest(3)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    @Test
    void updateReturnedIsAllowed() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "RETURNED", "91330100MA27XXXX8X", 2, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(2);
        req.setName("整改后名称");
        SupplierResponse resp = service.update(1L, req);

        assertThat(resp.getName()).isEqualTo("整改后名称");
        assertThat(resp.getStatus()).isEqualTo("RETURNED");
    }

    /** Partial 语义：只改提交的字段，其余保持存量值 */
    @Test
    void updatePartialAppliesOnlySubmittedFields() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 3, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(3);
        req.setContactPhone("13900139000");
        req.setRemark("新备注");
        service.update(1L, req);

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        Supplier updated = captor.getValue();
        assertThat(updated.getContactPhone()).isEqualTo("13900139000");
        assertThat(updated.getRemark()).isEqualTo("新备注");
        // 未提交字段原样保留
        assertThat(updated.getName()).isEqualTo("杭州之江电镀有限公司");
        assertThat(updated.getTaxNo()).isEqualTo("91330100MA27XXXX8X");
        assertThat(updated.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        // 乐观锁：以请求携带的 version 作为 WHERE 条件版本
        assertThat(updated.getVersion()).isEqualTo(3);
    }

    /** 验收 §1.2 乐观锁并发：updateById 影响 0 行 → 40901，不得静默成功 */
    @Test
    void updateVersionMismatchRowsZeroThrows40901() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 5, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(0);

        SupplierUpdateRequest req = updateRequest(3); // 客户端持旧版本号 3，库内已是 5
        req.setName("并发改名");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK_CONFLICT));
    }

    /** 验收 §1.6：把自己税号改成另一家在用供应商 → 40902 */
    @Test
    void updateTaxNoConflictsAnotherActiveSupplierThrows40902() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(supplierMapper.selectCount(any())).thenReturn(1L);

        SupplierUpdateRequest req = updateRequest(1);
        req.setTaxNo("91110000MA00XXXX1A");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS));

        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    /** 验收 §1.6：改成自己当前税号（幂等）仍成功，且不触发重复查重 */
    @Test
    void updateTaxNoSameAsCurrentIsIdempotentSuccess() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(1);
        req.setTaxNo("91330100MA27XXXX8X");

        SupplierResponse resp = service.update(1L, req);
        assertThat(resp.getTaxNo()).isEqualTo("91330100MA27XXXX8X");
        verify(supplierMapper, never()).selectCount(any());
    }

    /** 并发兜底：update 写库命中 uk_tax_no_active → 40902 */
    @Test
    void updateDbDuplicateKeyTranslatedTo40902() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.updateById(any(Supplier.class)))
                .thenThrow(new DuplicateKeyException("uk_tax_no_active"));

        SupplierUpdateRequest req = updateRequest(1);
        req.setTaxNo("91110000MA00XXXX1A");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS));
    }

    /** Partial 日期合并校验：只改 expiry 且早于存量 effective → 40001（契约固定文案） */
    @Test
    void updateMergedDateInvalidThrows40001() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));

        SupplierUpdateRequest req = updateRequest(1);
        req.setExpiryDate("2025-06-01"); // 存量 effective=2026-01-01，合并后到期早于生效

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("日期格式不合法或失效日期早于生效日期");
                });

        verify(supplierMapper, never()).updateById(any(Supplier.class));
    }

    /** 防御分支：绕过 Controller 层直调 Service 收到语法合法但语义非法（2 月 30 日）的日期 → 40001 */
    @Test
    void updateUnparseableDateThrows40001NotCrash() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));

        SupplierUpdateRequest req = updateRequest(1);
        req.setEffectiveDate("2026-02-30");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    /** 日期两枚一起提交且合法：更新后响应携带新日期 */
    @Test
    void updateBothDatesValidAppliesNewRange() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(1);
        req.setEffectiveDate("2026-06-01");
        req.setExpiryDate("2029-06-01");

        SupplierResponse resp = service.update(1L, req);
        assertThat(resp.getEffectiveDate()).isEqualTo("2026-06-01");
        assertThat(resp.getExpiryDate()).isEqualTo("2029-06-01");
    }

    // ==================== §1.3 list ====================

    private Page<Supplier> pageOf(int page, int size, long total, List<Supplier> records) {
        Page<Supplier> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return p;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<Supplier> capturedListWrapper() {
        ArgumentCaptor<Wrapper<Supplier>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(supplierMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<Supplier>) captor.getValue();
    }

    @Test
    void listForStaffAppliesRuleAIsolationCondition() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 0, List.of()));

        service.list(null, null, 1, 20);

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrapper();
        assertThat(wrapper.getSqlSegment()).contains("created_by");
        assertThat(wrapper.getParamNameValuePairs().containsValue(2L)).isTrue();
    }

    @Test
    void listForAuditorSeesAllWithoutIsolationCondition() {
        // 用户裁决 2026-10-14 晚："AUDITOR 不受 §0 规则A的约束"——AUDITOR 与 ADMIN 同口径全量
        when(currentUserProvider.require()).thenReturn(auditor());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 0, List.of()));

        service.list(null, null, 1, 20);

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrapper();
        assertThat(wrapper.getSqlSegment()).doesNotContain("created_by");
    }

    @Test
    void listForAdminSeesAllWithoutIsolationCondition() {
        // ADMIN 全量（写接口仍 40301 只读拦截，另有测试）；AUDITOR 依裁决 2026-10-14 晚同享全量
        when(currentUserProvider.require()).thenReturn(admin());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 0, List.of()));

        service.list(null, null, 1, 20);

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrapper();
        assertThat(wrapper.getSqlSegment()).doesNotContain("created_by");
    }

    @Test
    void listStatusAndKeywordFiltersPushedDownToSql() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 0, List.of()));

        service.list("NORMAL", "杭州", 1, 20);

        LambdaQueryWrapper<Supplier> wrapper = capturedListWrapper();
        // MyBatis-Plus 在生成 SQL 段时才把绑定值写入 paramNameValuePairs，
        // 因此必须先取 getSqlSegment() 触发物化，再断言参数表（顺序反过来只会读到空 map）。
        String sqlSegment = wrapper.getSqlSegment();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        assertThat(sqlSegment)
                .contains("status NOT IN")
                .contains("created_by =")
                .contains("status =")
                .contains("name LIKE")
                .contains("tax_no LIKE")
                .doesNotContain("'NORMAL'")
                .doesNotContain("杭州");
        assertThat(pairs.containsValue("NORMAL")).isTrue();
        assertThat(pairs.containsValue("%杭州%")).isTrue();
    }

    @Test
    void listMapsRecordsToPageResultWithCreatorNames() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier row = existing(1L, "NORMAL", "91330100MA27XXXX8X", 2, 2L);
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 37, List.of(row)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(zhangSan()));

        PageResult<SupplierResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getTotal()).isEqualTo(37);
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getCreatedByName()).isEqualTo("张三");
    }

    @Test
    void listEmptyRecordsSkipsUserLookup() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 0, List.of()));

        PageResult<SupplierResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList()).isEmpty();
        verify(userMapper, never()).selectBatchIds(any());
    }

    @Test
    void listClampsOversizedPageSizeTo100AndPageTo1() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 100, 0, List.of()));

        PageResult<SupplierResponse> result = service.list(null, null, 0, 500);

        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(100);
    }

    // ==================== §1.4 get ====================

    @Test
    void getOwnDraftVisibleToOwner() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierResponse resp = service.get(1L);
        assertThat(resp.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void getOtherUsersDraftForStaffThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 9L));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));
    }

    @Test
    void getOtherUsersReturnedRecordForStaffThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "RETURNED", "91330100MA27XXXX8X", 1, 9L));

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));
    }

    /** 用户裁决 2026-10-14 晚：AUDITOR 不受 §0 规则 A 约束，看他人 DRAFT 不再 40302（与 ADMIN 同全量） */
    @Test
    void getOtherUsersDraftForAuditorAllowed() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierResponse resp = service.get(1L);
        assertThat(resp.getStatus()).isEqualTo("DRAFT");
        assertThat(resp.getCreatedByName()).isEqualTo("张三");
    }

    @Test
    void getOtherUsersDraftForAdminAllowed() {
        when(currentUserProvider.require()).thenReturn(admin());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        assertThat(service.get(1L).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void getPublicNormalVisibleToOtherStaff() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L))
                .thenReturn(existing(1L, "NORMAL", "91330100MA27XXXX8X", 4, 9L));
        when(userMapper.selectById(9L)).thenReturn(null);

        SupplierResponse resp = service.get(1L);
        assertThat(resp.getStatus()).isEqualTo("NORMAL");
        assertThat(resp.getCreatedByName()).isEmpty();
    }

    /** 逻辑删除行走 MP @TableLogic 自动过滤，selectById 返回 null → 40401 */
    @Test
    void getMissingOrSoftDeletedThrows40401() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.get(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    // ==================== §1.5 delete ====================

    @Test
    void deleteOwnDraftCallsLogicDelete() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));

        service.delete(1L);

        verify(supplierMapper).deleteById(1L);
    }

    @Test
    void deleteNotFoundThrows40401() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.NOT_FOUND));

        verify(supplierMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteOtherUsersDraftThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 9L));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(supplierMapper, never()).deleteById(any(Long.class));
    }

    /** 送审后不可删：RETURNED 也拒绝（仅 DRAFT，契约 §1.5 明确"仅本人 DRAFT 可删"） */
    @Test
    void deleteNonDraftStatusThrows40302() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "RETURNED", "91330100MA27XXXX8X", 2, 2L));

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STATUS_NOT_ALLOWED));

        verify(supplierMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void deleteByAuditorBlockedWith40301() {
        when(currentUserProvider.require()).thenReturn(auditor());

        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verifyNoInteractions(supplierMapper);
    }

    // ==================== 防御分支（绕过 Controller 入参层直调 Service，必须有真实行为用例） ====================

    /** 必填日期缺失/纯空格：Service 侧兜底折 40001 契约文案，不得 NPE 或冒 50001 */
    @Test
    void createWithBlankDateRejected40001BeforeAnyWrite() {
        when(currentUserProvider.require()).thenReturn(staff());

        SupplierCreateRequest req = validCreateRequest();
        req.setEffectiveDate("   ");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("日期格式不合法或失效日期早于生效日期");
                });

        verifyNoInteractions(supplierMapper);
    }

    /** 语法合法但语义非法（2 月 30 日）在建档侧同样折 40001 */
    @Test
    void createWithSemanticallyInvalidDateRejected40001() {
        when(currentUserProvider.require()).thenReturn(staff());

        SupplierCreateRequest req = validCreateRequest();
        req.setExpiryDate("2026-02-30");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(supplierMapper);
    }

    /** 到期日等于生效日同样拒绝（契约要求"晚于"，非"不早于"） */
    @Test
    void createWithExpiryEqualToEffectiveRejected40001() {
        when(currentUserProvider.require()).thenReturn(staff());

        SupplierCreateRequest req = validCreateRequest();
        req.setExpiryDate("2026-01-01");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verifyNoInteractions(supplierMapper);
    }

    /** 存量 NULL 日期行（NULL 分层裁决 20261011）不得被合并校验卡死：只改电话仍可保存 */
    @Test
    void updateLegacyRowWithNullEffectiveDateRemainsEditable() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier legacy = existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L);
        legacy.setEffectiveDate(null);
        when(supplierMapper.selectById(1L)).thenReturn(legacy);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(1);
        req.setContactPhone("13700137000");
        SupplierResponse resp = service.update(1L, req);

        assertThat(resp.getEffectiveDate()).isNull();
        assertThat(resp.getExpiryDate()).isEqualTo("2028-12-31");
        assertThat(resp.getContactPhone()).isEqualTo("13700137000");
    }

    /** 单侧日期缺失（到期日为 NULL 的历史行）也不触发顺序校验 */
    @Test
    void updateLegacyRowWithNullExpiryDateRemainsEditable() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier legacy = existing(1L, "RETURNED", "91330100MA27XXXX8X", 2, 2L);
        legacy.setExpiryDate(null);
        when(supplierMapper.selectById(1L)).thenReturn(legacy);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierResponse resp = service.update(1L, updateRequest(2));

        assertThat(resp.getEffectiveDate()).isEqualTo("2026-01-01");
        assertThat(resp.getExpiryDate()).isNull();
    }

    /** 建档人已不在 sys_user（离职清理/历史脏数据）：列表展示空名，不 NPE */
    @Test
    void listFallsBackToEmptyCreatorNameWhenCreatorUserMissing() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier row = existing(1L, "NORMAL", "91330100MA27XXXX8X", 2, 9L);
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 1, List.of(row)));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        PageResult<SupplierResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getCreatedByName()).isEmpty();
    }

    /** created_by 为 NULL 的脏数据行：不发起无意义的用户反查，展示空名 */
    @Test
    void listWithDirtyRowWithoutCreatedBySkipsUserLookup() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier dirty = existing(1L, "NORMAL", "91330100MA27XXXX8X", 2, null);
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 1, List.of(dirty)));

        PageResult<SupplierResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList().get(0).getCreatedByName()).isEmpty();
        verify(userMapper, never()).selectBatchIds(any());
    }

    /** 展示名优先真实姓名，缺失时回退登录名（而非空串） */
    @Test
    void createFallsBackToUsernameWhenRealNameMissing() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectCount(any())).thenReturn(0L);
        when(supplierMapper.insert(any(Supplier.class))).thenAnswer(inv -> {
            inv.getArgument(0, Supplier.class).setId(102L);
            return 1;
        });
        User noRealName = zhangSan();
        noRealName.setRealName("  ");
        when(userMapper.selectById(2L)).thenReturn(noRealName);

        assertThat(service.create(validCreateRequest()).getCreatedByName()).isEqualTo("staff01");
    }

    /** page_size 非正数回落契约默认值 20（api-spec §0） */
    @Test
    void listWithNonPositivePageSizeFallsBackToDefaultTwenty() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        PageResult<SupplierResponse> result = service.list(null, null, 1, 0);

        assertThat(result.getPageSize()).isEqualTo(20);
        assertThat(result.getPage()).isEqualTo(1);
    }

    /** 公有区的历史行（created_by/时间戳为 NULL）详情仍可读，绝不影响 40401 判定 */
    @Test
    void getLegacyPublicRowWithoutCreatorAndTimestampsStillReadable() {
        when(currentUserProvider.require()).thenReturn(staff());
        Supplier legacy = existing(1L, "NORMAL", "91330100MA27XXXX8X", 4, null);
        legacy.setCreatedAt(null);
        legacy.setUpdatedAt(null);
        when(supplierMapper.selectById(1L)).thenReturn(legacy);

        SupplierResponse resp = service.get(1L);

        assertThat(resp.getCreatedByName()).isEmpty();
        assertThat(resp.getCreatedAt()).isNull();
        assertThat(resp.getUpdatedAt()).isNull();
    }

    /** 整单提交（非局部改）：11 个可编辑字段全部落库，覆盖 Partial 分支的另一侧 */
    @Test
    void updateSubmittingEveryEditableFieldPersistsAllOfThem() {
        when(currentUserProvider.require()).thenReturn(staff());
        when(supplierMapper.selectById(1L)).thenReturn(existing(1L, "DRAFT", "91330100MA27XXXX8X", 1, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(2L)).thenReturn(zhangSan());

        SupplierUpdateRequest req = updateRequest(1);
        req.setName("整单提交新名称");
        req.setTaxNo("91330100MA27XXXX8X");
        req.setType("DISTRIBUTOR");
        req.setContactName("李梅");
        req.setContactPhone("13900139111");
        req.setContactEmail("limei@example.com");
        req.setAddress("杭州市滨江区");
        req.setRemark("整单提交");
        req.setEffectiveDate("2026-03-01");
        req.setExpiryDate("2029-03-01");

        SupplierResponse resp = service.update(1L, req);

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        Supplier saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("整单提交新名称");
        assertThat(saved.getType()).isEqualTo("DISTRIBUTOR");
        assertThat(saved.getContactName()).isEqualTo("李梅");
        assertThat(saved.getContactPhone()).isEqualTo("13900139111");
        assertThat(saved.getContactEmail()).isEqualTo("limei@example.com");
        assertThat(saved.getAddress()).isEqualTo("杭州市滨江区");
        assertThat(saved.getRemark()).isEqualTo("整单提交");
        assertThat(saved.getEffectiveDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(saved.getExpiryDate()).isEqualTo(LocalDate.of(2029, 3, 1));
        assertThat(resp.getType()).isEqualTo("DISTRIBUTOR");
        assertThat(resp.getContactEmail()).isEqualTo("limei@example.com");
    }

    // ==================== §2.2 audit：模块二防御分支（补齐 JaCoCo 报出的两条未触达分支） ====================

    private static SupplierAuditDecisionRequest auditRequest(String decision, String comment, Integer version) {
        SupplierAuditDecisionRequest req = new SupplierAuditDecisionRequest();
        req.setDecision(decision);
        req.setComment(comment);
        req.setVersion(version);
        return req;
    }

    private static SupplierQualification pendingQualification(String docType) {
        SupplierQualification q = new SupplierQualification();
        q.setId(7L);
        q.setSupplierId(1L);
        q.setDocType(docType);
        q.setFileUrl("/uploads/2026/10/" + docType.toLowerCase() + ".pdf");
        q.setEffectiveDate(LocalDate.of(2026, 1, 1));
        q.setExpiryDate(LocalDate.of(2028, 12, 31));
        q.setStatus("PENDING");
        q.setVersion(1);
        q.setDeleted(0);
        return q;
    }

    /**
     * §2.2 APPROVE 连带批复资质时撞 uk_supplier_approved_doc（生效位并发竞态）：
     * DuplicateKeyException 必须转译成 40902 契约文案，绝不冒 50001、绝不泄露 SQL；
     * 且整笔事务回滚意味着审核留痕不得落库。
     */
    @Test
    void auditApproveTranslatesDuplicateKeyOnCascadedQualificationTo40902() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(supplierMapper.selectById(1L))
                .thenReturn(existing(1L, "PENDING_REVIEW", "91330100MA27XXXX8X", 2, 2L));
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(qualificationMapper.selectList(any()))
                .thenReturn(List.of(pendingQualification("BUSINESS_LICENSE")));
        when(qualificationMapper.updateById(any(SupplierQualification.class)))
                .thenThrow(new DuplicateKeyException(
                        "Duplicate entry '1_BUSINESS_LICENSE' for key 'uk_supplier_approved_doc'"));

        assertThatThrownBy(() -> service.audit(1L, auditRequest("APPROVE", null, 2)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_IN_PROGRESS);
                    assertThat(ex.getMessage()).isEqualTo("业务冲突");
                    assertThat(ex.getMessage())
                            .doesNotContain("uk_supplier_approved_doc", "Duplicate entry", "SQL");
                });

        // 连带批复失败 → 主表那一跳一并回滚，审核留痕绝不能先落库
        verifyNoInteractions(auditLogService);
    }

    /** 绕过 Controller 入参层直调 Service：未知决策码值不得默认当成 REJECT，一律 40001 且不触达任何写库 */
    @Test
    void auditWithUnknownDecisionCodeRejected40001AtServiceLayer() {
        when(currentUserProvider.require()).thenReturn(auditor());
        when(supplierMapper.selectById(1L))
                .thenReturn(existing(1L, "PENDING_REVIEW", "91330100MA27XXXX8X", 2, 2L));

        assertThatThrownBy(() -> service.audit(1L, auditRequest("PASS", "随意意见", 2)))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getMessage()).isEqualTo("参数校验失败");
                });

        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verifyNoInteractions(qualificationMapper, auditLogService);
    }
}
