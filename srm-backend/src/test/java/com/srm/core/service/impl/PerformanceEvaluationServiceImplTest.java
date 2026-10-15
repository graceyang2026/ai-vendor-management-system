package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.calculator.PerformanceMetricProvider;
import com.srm.core.calculator.PerformanceScoreCalculator;
import com.srm.core.common.BusinessException;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.performance.PerformanceEvaluationCreateRequest;
import com.srm.core.dto.performance.PerformanceEvaluationResponse;
import com.srm.core.dto.performance.PerformanceEvaluationResult;
import com.srm.core.dto.performance.PerformanceEvaluationUpdateRequest;
import com.srm.core.dto.performance.PerformanceFactRecord;
import com.srm.core.dto.performance.PerformanceReviewDecisionRequest;
import com.srm.core.entity.PerformanceEvaluation;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.User;
import com.srm.core.mapper.PerformanceEvaluationMapper;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模块三·绩效评价服务单元测试（契约 §3.1–§3.5 + 附录 A4，docs/api-spec.md §4）。
 *
 * 单元层只锁<b>判序与决策</b>，不锁框架行为：
 * <ul>
 *   <li>create：40301 → 供应商 40401「供应商不存在」→ ELIMINATED 40401 → 周期语义 40001 →
 *       周期倒挂 40001 → mode 无对应 bean 40001 → 在途预检 40902（COUNT 返回 null 视为无在途）→
 *       insert（DuplicateKey 转译同一 40902）→ SUBMIT 审计；</li>
 *   <li>update：40301 → 40401「评价记录不存在」→ 非本人 40302 → 非 RETURNED 40302 →
 *       version 缺失 40901 → 按<b>落库 mode</b> 取适配器（入参无 mode，不得切模式）→
 *       updateById 0 行 40901 → SUBMIT 审计（用户 2026-10-15 16:42 拍板 B2）；</li>
 *   <li>review：40301 → 40401 → 非 PENDING_REVIEW 40302 → decision 码值 40001 →
 *       REJECT 缺 comment 40001 → version 缺失 40901 → APPROVE 取供应商 40401 →
 *       评价单 0 行 40901 → 供应商 0 行 40901 → REVIEW_* 审计；</li>
 *   <li>list/get：§0 行级隔离条件、分页收敛、只读零审计、越权 40302。</li>
 * </ul>
 *
 * {@code @Version} 真实自增、uk_supplier_active_period 生成列兜底、按 created_at 倒序的真实行序、
 * 夹带分数字段被 Jackson 静默丢弃——一律由 PerformanceEvaluationIntegrationTest 在 H2 端到端锁定；
 * 本类用 mock 只验"调用点与传值"，不做替身式假绿。
 *
 * RoleGuard 用真实实例包裹 mock provider（40301/40302 走生产同一实现）；
 * 取数适配器与算分引擎用 mock，把"服务判序"与"算分公式"两类缺陷分离定位。
 */
@ExtendWith(MockitoExtension.class)
class PerformanceEvaluationServiceImplTest {

    @Mock
    private PerformanceEvaluationMapper evaluationMapper;

    @Mock
    private SupplierMapper supplierMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PerformanceScoreCalculator scoreCalculator;

    @Mock
    private PerformanceMetricProvider manualProvider;

    @Mock
    private PerformanceMetricProvider mockProvider;

    private PerformanceEvaluationServiceImpl service;

    private static final long SUPPLIER_ID = 1001L;
    private static final long EVALUATION_ID = 5001L;
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 9, 30);

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    private static final String MSG_EVALUATION_NOT_FOUND = "评价记录不存在";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    private static final String MSG_SELF_ONLY = "仅限本人操作";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    private static final String MSG_IN_FLIGHT_EXISTS = "该供应商同期已有在途评价单";
    private static final String MSG_FACT_INVALID = "事实数据校验失败";
    private static final String MSG_PARAM_INVALID = "参数校验失败";
    private static final String MSG_REJECT_COMMENT = "驳回时必须填写意见";
    private static final String MSG_FORBIDDEN = "无权限";

    private final UserPrincipal staff = new UserPrincipal(2L, "staff01", null, "张三", Role.STAFF, true);
    private final UserPrincipal auditor = new UserPrincipal(3L, "auditor01", null, "李四", Role.AUDITOR, true);
    private final UserPrincipal admin = new UserPrincipal(1L, "admin", null, "系统管理员", Role.ADMIN, true);

    /** 单元层无 Spring 容器：在途预检与列表过滤都走 LambdaQueryWrapper，需手工注册 TableInfo */
    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), PerformanceEvaluation.class);
    }

    @BeforeEach
    void setUp() {
        service = new PerformanceEvaluationServiceImpl(evaluationMapper, supplierMapper, userMapper,
                new RoleGuard(currentUserProvider), currentUserProvider, auditLogService,
                scoreCalculator,
                Map.of("manual", manualProvider, "mock", mockProvider));
    }

    // ==================== 夹具 ====================

    private Supplier supplier(String status) {
        Supplier s = new Supplier();
        s.setId(SUPPLIER_ID);
        s.setName("杭州之江电镀有限公司");
        s.setStatus(status);
        s.setCreatedBy(staff.getId());
        s.setVersion(3);
        s.setCreatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setUpdatedAt(LocalDateTime.of(2026, 10, 1, 0, 0));
        s.setDeleted(0);
        return s;
    }

    private PerformanceFactRecord facts() {
        return new PerformanceFactRecord(40, 3, new BigDecimal("2.5"), 20, 1, 0,
                new BigDecimal("4.2"), 1);
    }

    private PerformanceEvaluationResult resultOf(String grade, boolean riskWarning) {
        return PerformanceEvaluationResult.builder()
                .scoreQuality(new BigDecimal("92.50"))
                .scoreDelivery(new BigDecimal("87.50"))
                .scorePrice(new BigDecimal("91.60"))
                .scoreService(new BigDecimal("90.00"))
                .scoreCompliance(new BigDecimal("100.00"))
                .totalScore(new BigDecimal("91.45"))
                .grade(grade)
                .qualityExempt(false)
                .riskWarning(riskWarning)
                .build();
    }

    private PerformanceEvaluation evaluation(String status, Long createdBy, Integer version) {
        PerformanceEvaluation row = new PerformanceEvaluation();
        row.setId(EVALUATION_ID);
        row.setSupplierId(SUPPLIER_ID);
        row.setPeriodStart(START);
        row.setPeriodEnd(END);
        row.setMode("manual");
        row.setFactRecord(facts());
        row.setStatus(status);
        row.setCreatedBy(createdBy);
        row.setVersion(version);
        row.setQualityExempt(false);
        row.setRiskWarning(false);
        row.setScoreQuality(new BigDecimal("92.50"));
        row.setTotalScore(new BigDecimal("91.45"));
        row.setGrade("A");
        row.setCreatedAt(LocalDateTime.of(2026, 10, 13, 10, 30));
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 13, 10, 30));
        row.setDeleted(0);
        return row;
    }

    private PerformanceEvaluationCreateRequest createRequest(String mode, PerformanceFactRecord inbound) {
        PerformanceEvaluationCreateRequest req = new PerformanceEvaluationCreateRequest();
        req.setSupplierId(SUPPLIER_ID);
        req.setPeriodStart("2026-07-01");
        req.setPeriodEnd("2026-09-30");
        req.setMode(mode);
        req.setFactRecord(inbound);
        return req;
    }

    private PerformanceEvaluationUpdateRequest updateRequest(PerformanceFactRecord inbound, Integer version) {
        PerformanceEvaluationUpdateRequest req = new PerformanceEvaluationUpdateRequest();
        req.setFactRecord(inbound);
        req.setVersion(version);
        return req;
    }

    private PerformanceReviewDecisionRequest reviewRequest(String decision, String comment, Integer version) {
        PerformanceReviewDecisionRequest req = new PerformanceReviewDecisionRequest();
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

    /**
     * 可组合桩（刻意不写"一键铺全程"的大桩）：每个用例只 stub 自己会用到的调用，
     * 让 Mockito 默认 STRICT_STUBS 继续把关——一旦出现"铺了却没用"的桩就说明判序变了，
     * 这本身就是本域最值钱的回归信号。
     */
    private void stubStaff() {
        when(currentUserProvider.require()).thenReturn(staff);
    }

    private void stubSupplierNormal() {
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
    }

    private void stubNoInFlight() {
        when(evaluationMapper.selectCount(any())).thenReturn(0L);
    }

    private void stubCalculateA() {
        when(scoreCalculator.calculate(any(), any())).thenReturn(resultOf("A", false));
    }

    private void stubStaffName() {
        when(userMapper.selectById(any())).thenReturn(user(staff.getId(), "staff01", "张三"));
    }

    /** 后端取数返回值（断言"落库事实就是它"的唯一凭据，必须持有引用而非新建等值对象） */
    private PerformanceFactRecord backendFacts = facts();

    private void stubManualFetch() {
        when(manualProvider.fetchFacts(any(), any(), any(), any())).thenReturn(backendFacts);
    }

    private void stubMockFetch() {
        when(mockProvider.fetchFacts(any(), any(), any(), any())).thenReturn(backendFacts);
    }

    /** 让 insert 像 MyBatis-Plus 一样回填自增主键，否则审计 entity_id 只会是 null（假绿） */
    private void stubInsertAssigningId() {
        when(evaluationMapper.insert(any(PerformanceEvaluation.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, PerformanceEvaluation.class).setId(EVALUATION_ID);
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

    // ==================== §3.1 create：闸门链 ====================

    @Test
    void createRejectsAuditorWith40301BeforeTouchingAnyMapper() {
        when(currentUserProvider.require()).thenReturn(auditor);

        expectCode(40301, MSG_FORBIDDEN, () -> service.create(createRequest("manual", facts())));

        verifyNoInteractions(supplierMapper, evaluationMapper, scoreCalculator, auditLogService);
    }

    @Test
    void createRejectsUnknownSupplierWith40401() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(null);

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND, () -> service.create(createRequest("manual", facts())));

        verifyNoInteractions(evaluationMapper, scoreCalculator, auditLogService);
    }

    /** 契约 §3.1 参数表"存在且未 ELIMINATED"：终态供应商不可再被评价，命中同回 40401 */
    @Test
    void createRejectsEliminatedSupplierWith40401() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("ELIMINATED"));

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND, () -> service.create(createRequest("manual", facts())));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    /** 形状合法（@Pattern 已放行）但语义不存在的日期（2026-02-31）必须折 40001，不得冒 50001 */
    @Test
    void createRejectsNonexistentCalendarDateWith40001() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        PerformanceEvaluationCreateRequest req = createRequest("manual", facts());
        req.setPeriodStart("2026-02-31");

        expectCode(40001, MSG_FACT_INVALID, () -> service.create(req));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    /** period_end 必须<b>晚于</b> period_start：同日与倒挂两种非法一律 40001（契约 §3.1 矩阵"周期区间非法"） */
    @ParameterizedTest
    @ValueSource(strings = {"2026-07-01", "2026-06-30"})
    void createRejectsPeriodNotAfterStart(String periodEnd) {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        PerformanceEvaluationCreateRequest req = createRequest("manual", facts());
        req.setPeriodEnd(periodEnd);

        expectCode(40001, MSG_FACT_INVALID, () -> service.create(req));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    /**
     * mode 有合法形状但无对应 bean（bean 缺失/配置漂移）→ 40001，
     * 绝不允许 Map.get 返回 null 后 NPE 成 50001。
     */
    @Test
    void createRejectsModeWithoutRegisteredProviderWith40001() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        PerformanceEvaluationCreateRequest req = createRequest("manual", facts());
        req.setMode("legacy");

        expectCode(40001, MSG_PARAM_INVALID, () -> service.create(req));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    @Test
    void createRejectsInFlightEvaluationOfSamePeriodWith40902() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(manualProvider.fetchFacts(any(), any(), any(), any())).thenReturn(facts());
        when(evaluationMapper.selectCount(any())).thenReturn(1L);

        expectCode(40902, MSG_IN_FLIGHT_EXISTS, () -> service.create(createRequest("manual", facts())));

        verify(evaluationMapper, never()).insert(any(PerformanceEvaluation.class));
        verifyNoInteractions(auditLogService);
    }

    /** COUNT 装箱返回 null 按"无在途"放行（口径与 SupplierServiceImpl 一致，绝不 NPE） */
    @Test
    void createTreatsNullCountAsNoInFlight() {
        stubStaff();
        stubSupplierNormal();
        stubManualFetch();
        stubCalculateA();
        stubStaffName();
        stubInsertAssigningId();
        when(evaluationMapper.selectCount(any())).thenReturn(null);

        assertThat(service.create(createRequest("manual", facts())).getStatus()).isEqualTo("PENDING_REVIEW");

        verify(evaluationMapper).insert(any(PerformanceEvaluation.class));
    }

    /** 预检通过后的并发窗口由 DB 生成列唯一索引兜底：DuplicateKey 必须转译 40902 且不写审计 */
    @Test
    void createTranslatesDuplicateKeyTo40902() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(manualProvider.fetchFacts(any(), any(), any(), any())).thenReturn(facts());
        when(evaluationMapper.selectCount(any())).thenReturn(0L);
        when(scoreCalculator.calculate(any(), any())).thenReturn(resultOf("A", false));
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_supplier_active_period"))
                .when(evaluationMapper).insert(any(PerformanceEvaluation.class));

        expectCode(40902, MSG_IN_FLIGHT_EXISTS, () -> service.create(createRequest("manual", facts())));

        verifyNoInteractions(auditLogService);
    }

    /**
     * 正常链：状态/版本/删除位/创建人一律由后端定死，mode 缺省即 manual，
     * 分数只能来自算分引擎（本断言证明"实体里的分数就是 calculate 的返回值"）。
     */
    @Test
    void createPersistsBackendComputedScoreAndSubmitsAudit() {
        stubStaff();
        stubSupplierNormal();
        stubManualFetch();
        stubNoInFlight();
        stubCalculateA();
        stubStaffName();
        stubInsertAssigningId();

        PerformanceEvaluationResponse response = service.create(createRequest(null, facts()));

        ArgumentCaptor<PerformanceEvaluation> captor = ArgumentCaptor.forClass(PerformanceEvaluation.class);
        verify(evaluationMapper).insert(captor.capture());
        PerformanceEvaluation persisted = captor.getValue();
        assertThat(persisted.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(persisted.getVersion()).isEqualTo(1);
        assertThat(persisted.getDeleted()).isZero();
        assertThat(persisted.getCreatedBy()).isEqualTo(staff.getId());
        assertThat(persisted.getMode()).isEqualTo("manual");
        assertThat(persisted.getReviewedBy()).isNull();
        assertThat(persisted.getReviewedAt()).isNull();
        assertThat(persisted.getReviewComment()).isNull();
        assertThat(persisted.getFactRecord()).isSameAs(backendFacts);
        assertThat(persisted.getTotalScore()).isEqualByComparingTo("91.45");
        assertThat(persisted.getGrade()).isEqualTo("A");
        assertThat(persisted.getScoreQuality()).isEqualByComparingTo("92.50");

        assertThat(response.getVersion()).isEqualTo(1);
        assertThat(response.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(response.getMode()).isEqualTo("manual");
        assertThat(response.getPeriodStart()).isEqualTo("2026-07-01");
        assertThat(response.getCreatedAt()).isNotNull().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        assertThat(response.getUpdatedAt()).isEqualTo(response.getCreatedAt());
        assertThat(response.getCreatedByName()).isEqualTo("张三");

        verify(auditLogService).record(eq(EntityType.PERFORMANCE_EVALUATION), eq(EVALUATION_ID),
                eq(SUPPLIER_ID), eq(staff), eq(AuditAction.SUBMIT.name()), eq(null),
                eq("PENDING_REVIEW"), eq(AuditResult.SUCCESS), eq(null));
    }

    /** mode 带首尾空白（脏值）→ 规范化后取 bean，落库也存规范化值，不给 " manual " 留活口 */
    @Test
    void createNormalizesModeBeforeLookupAndPersistence() {
        stubStaff();
        stubSupplierNormal();
        stubMockFetch();
        stubNoInFlight();
        stubCalculateA();
        stubStaffName();
        stubInsertAssigningId();

        PerformanceFactRecord inbound = facts();
        service.create(createRequest("  mock  ", inbound));

        verify(mockProvider).fetchFacts(SUPPLIER_ID, START, END, inbound);
        verify(manualProvider, never()).fetchFacts(any(), any(), any(), any());
        ArgumentCaptor<PerformanceEvaluation> captor = ArgumentCaptor.forClass(PerformanceEvaluation.class);
        verify(evaluationMapper).insert(captor.capture());
        assertThat(captor.getValue().getMode()).isEqualTo("mock");
    }

    /** 取数失败（适配器抛 40001）时绝不落库、绝不审计、也绝不算分 */
    @Test
    void createStopsBeforeAnyWriteWhenAdapterRejectsFacts() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(manualProvider.fetchFacts(any(), any(), any(), any()))
                .thenThrow(new BusinessException(
                        com.srm.core.common.ErrorCode.VALIDATION_FAILED, MSG_FACT_INVALID));

        expectCode(40001, MSG_FACT_INVALID, () -> service.create(createRequest("manual", facts())));

        verifyNoInteractions(scoreCalculator, evaluationMapper, auditLogService);
    }

    // ==================== §3.2 update：退回重提 ====================

    @Test
    void updateRejectsAuditorWith40301() {
        when(currentUserProvider.require()).thenReturn(auditor);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.update(EVALUATION_ID, updateRequest(facts(), 1)));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    @Test
    void updateRejectsMissingEvaluationWith40401AndItsOwnMessage() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(null);

        expectCode(40401, MSG_EVALUATION_NOT_FOUND,
                () -> service.update(EVALUATION_ID, updateRequest(facts(), 1)));

        verifyNoInteractions(auditLogService);
    }

    /** 非本人重提 → 40302（归属闸先于状态闸，口径同 §1.2） */
    @Test
    void updateRejectsNotOwnedEvaluationWith40302() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("RETURNED", 999L, 2));

        expectCode(40302, MSG_SELF_ONLY, () -> service.update(EVALUATION_ID, updateRequest(facts(), 2)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
        verifyNoInteractions(auditLogService);
    }

    /** 只有 RETURNED 可重提：PENDING_REVIEW（在途）与 APPROVED（归档终态）一律 40302 */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "APPROVED"})
    void updateRejectsStatusOtherThanReturnedWith40302(String status) {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(evaluation(status, staff.getId(), 2));

        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.update(EVALUATION_ID, updateRequest(facts(), 2)));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateRejectsMissingVersionWith40901() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("RETURNED", staff.getId(), 2));

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.update(EVALUATION_ID, updateRequest(facts(), null)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
    }

    @Test
    void updateTranslatesZeroRowsInto40901WithoutAudit() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("RETURNED", staff.getId(), 2));
        when(manualProvider.fetchFacts(any(), any(), any(), any())).thenReturn(facts());
        when(scoreCalculator.calculate(any(), any())).thenReturn(resultOf("A", false));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(0);

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.update(EVALUATION_ID, updateRequest(facts(), 2)));

        verifyNoInteractions(auditLogService);
    }

    /**
     * 正常重提：沿用同一条记录（同一 id，不新建）、回 PENDING_REVIEW、version 传库内值给拦截器自增、
     * 重算分覆盖 result，并按 2026-10-15 裁决写 SUBMIT 审计（old=RETURNED → new=PENDING_REVIEW）。
     */
    @Test
    void updateResubmitsSameRowWithRecomputedScoreAndSubmitAudit() {
        when(currentUserProvider.require()).thenReturn(staff);
        PerformanceEvaluation row = evaluation("RETURNED", staff.getId(), 2);
        row.setReviewComment("事实数据与质检台账不符，请修正后重提。");
        row.setReviewedBy(auditor.getId());
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(row);
        when(manualProvider.fetchFacts(any(), any(), any(), any())).thenReturn(facts());
        when(scoreCalculator.calculate(any(), any())).thenReturn(resultOf("B", false));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenReturn(user(staff.getId(), "staff01", "张三"));

        PerformanceEvaluationResponse response = service.update(EVALUATION_ID, updateRequest(facts(), 2));

        ArgumentCaptor<PerformanceEvaluation> captor = ArgumentCaptor.forClass(PerformanceEvaluation.class);
        verify(evaluationMapper).updateById(captor.capture());
        PerformanceEvaluation persisted = captor.getValue();
        assertThat(persisted.getId()).isEqualTo(EVALUATION_ID);
        assertThat(persisted.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(persisted.getVersion()).isEqualTo(2);
        assertThat(persisted.getGrade()).isEqualTo("B");
        assertThat(persisted.getTotalScore()).isEqualByComparingTo("91.45");
        // 裁决③（2026-10-15 18:50）：重提把上一次复核痕迹显式置 null 交给 updateById
        // （实体侧 updateStrategy=IGNORED 保证 null 真实落库，DB 层由集成测试兜证）
        assertThat(persisted.getReviewedBy()).isNull();
        assertThat(persisted.getReviewedAt()).isNull();
        assertThat(persisted.getReviewComment()).isNull();
        assertThat(response.getVersion()).isEqualTo(2);
        assertThat(response.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(response.getReviewedBy()).isNull();
        assertThat(response.getReviewedAt()).isNull();
        assertThat(response.getReviewComment()).isNull();

        verify(evaluationMapper, never()).insert(any(PerformanceEvaluation.class));
        verify(auditLogService).record(eq(EntityType.PERFORMANCE_EVALUATION), eq(EVALUATION_ID),
                eq(SUPPLIER_ID), eq(staff), eq(AuditAction.SUBMIT.name()), eq("RETURNED"),
                eq("PENDING_REVIEW"), eq(AuditResult.SUCCESS), eq(null));
    }

    /** 重提<b>不入参 mode</b>：取数适配器按落库 mode 取，mock 单永不因重提变成 manual 单 */
    @Test
    void updateKeepsPersistedModeInsteadOfSwitchingProvider() {
        when(currentUserProvider.require()).thenReturn(staff);
        PerformanceEvaluation row = evaluation("RETURNED", staff.getId(), 2);
        row.setMode("mock");
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(row);
        when(mockProvider.fetchFacts(any(), any(), any(), any())).thenReturn(facts());
        when(scoreCalculator.calculate(any(), any())).thenReturn(resultOf("A", false));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenReturn(user(staff.getId(), "staff01", "张三"));

        service.update(EVALUATION_ID, updateRequest(facts(), 2));

        verify(mockProvider).fetchFacts(SUPPLIER_ID, START, END, facts());
        verify(manualProvider, never()).fetchFacts(any(), any(), any(), any());
    }

    // ==================== §3.3 review：复核 ====================

    @Test
    void reviewRejectsStaffWith40301SoNoSelfReview() {
        when(currentUserProvider.require()).thenReturn(staff);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 1)));

        verifyNoInteractions(evaluationMapper, auditLogService);
    }

    @Test
    void reviewRejectsAdminWith40301() {
        when(currentUserProvider.require()).thenReturn(admin);

        expectCode(40301, MSG_FORBIDDEN,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 1)));
    }

    @Test
    void reviewRejectsMissingEvaluationWith40401() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(null);

        expectCode(40401, MSG_EVALUATION_NOT_FOUND,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 1)));
    }

    /** 只能复核 PENDING_REVIEW：RETURNED/APPROVED 一律 40302（归档终态不可复审，状态机唯一入口） */
    @ParameterizedTest
    @ValueSource(strings = {"RETURNED", "APPROVED"})
    void reviewRejectsIllegalStatusWith40302(String status) {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation(status, staff.getId(), 2));

        expectCode(40302, MSG_STATUS_NOT_ALLOWED,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 2)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
        verifyNoInteractions(auditLogService);
    }

    /** decision 脏值（非 APPROVE/REJECT）→ 40001，不得"默认当成拒绝"这种静默降级 */
    @Test
    void reviewRejectsUnknownDecisionWith40001() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));

        expectCode(40001, MSG_PARAM_INVALID,
                () -> service.review(EVALUATION_ID, reviewRequest("MAYBE", "x", 1)));

        verifyNoInteractions(auditLogService);
    }

    /** REJECT 缺 comment（null / 空串 / 纯空白三态）→ 40001「驳回时必须填写意见」 */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void reviewRejectsWithoutCommentFails(String comment) {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));

        expectCode(40001, MSG_REJECT_COMMENT,
                () -> service.review(EVALUATION_ID, reviewRequest("REJECT", comment, 1)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reviewRejectsMissingVersionWith40901() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, null)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
    }

    /** APPROVE 归档时供应商已被并发删除 → 40401，且评价单一张都不改（不留半归档） */
    @Test
    void reviewApproveWithMissingSupplierStopsBeforeAnyUpdate() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(null);

        expectCode(40401, MSG_SUPPLIER_NOT_FOUND,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 1)));

        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
        verifyNoInteractions(auditLogService);
    }

    /** 评价单 version 不匹配（0 行）→ 40901，供应商与审计两处一律不动 */
    @Test
    void reviewTranslatesEvaluationLockConflictInto40901() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(0);

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 7)));

        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verifyNoInteractions(auditLogService);
    }

    /** 供应商行并发冲突（0 行）→ 同样 40901 且不写审计，事务整体回滚（单元层验"不写审计"） */
    @Test
    void reviewTranslatesSupplierLockConflictInto40901() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(1);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(0);

        expectCode(40901, MSG_VERSION_CONFLICT,
                () -> service.review(EVALUATION_ID, reviewRequest("APPROVE", null, 1)));

        verifyNoInteractions(auditLogService);
    }

    /**
     * 归档回写供应商两列的四种组合（验收 §2.4 只打标不改状态）：
     * 评价单风险标优先命中；否则评级 C/D 命中；A/B 且无风险标 → false。
     */
    @ParameterizedTest
    @CsvSource({
            "A, false, false",
            "B, false, false",
            "C, false, true",
            "D, false, true",
            "A, true, true"
    })
    void reviewApproveWritesBackGradeAndRiskFlag(String grade, boolean evaluationRisk,
                                                 boolean expectedSupplierRisk) {
        when(currentUserProvider.require()).thenReturn(auditor);
        PerformanceEvaluation row = evaluation("PENDING_REVIEW", staff.getId(), 1);
        row.setGrade(grade);
        row.setRiskWarning(evaluationRisk);
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(row);
        when(supplierMapper.selectById(SUPPLIER_ID)).thenReturn(supplier("NORMAL"));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(1);
        when(supplierMapper.updateById(any(Supplier.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenReturn(user(staff.getId(), "staff01", "张三"));

        PerformanceEvaluationResponse response =
                service.review(EVALUATION_ID, reviewRequest("APPROVE", "  经核实无误。  ", 1));

        ArgumentCaptor<Supplier> captor = ArgumentCaptor.forClass(Supplier.class);
        verify(supplierMapper).updateById(captor.capture());
        assertThat(captor.getValue().getLatestPerformanceGrade()).isEqualTo(grade);
        assertThat(captor.getValue().getRiskWarning()).isEqualTo(expectedSupplierRisk);
        // 只打标不改状态（停用/淘汰必须走 §2.4/§2.6 两步流程）
        assertThat(captor.getValue().getStatus()).isEqualTo("NORMAL");

        assertThat(response.getStatus()).isEqualTo("APPROVED");
        assertThat(response.getReviewedBy()).isEqualTo(auditor.getId());
        assertThat(response.getReviewComment()).isEqualTo("经核实无误。");
        assertThat(response.getReviewedAt()).isNotBlank();

        verify(auditLogService).record(eq(EntityType.PERFORMANCE_EVALUATION), eq(EVALUATION_ID),
                eq(SUPPLIER_ID), eq(auditor), eq(AuditAction.REVIEW_APPROVE.name()),
                eq("PENDING_REVIEW"), eq("APPROVED"), eq(AuditResult.SUCCESS), eq("经核实无误。"));
    }

    /** REJECT：退回 RETURNED + 回写 review_*，供应商两列<b>一概不动</b>，审计 REVIEW_REJECT */
    @Test
    void reviewRejectReturnsRowToStaffWithoutTouchingSupplier() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(evaluationMapper.updateById(any(PerformanceEvaluation.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenReturn(user(staff.getId(), "staff01", "张三"));

        PerformanceEvaluationResponse response =
                service.review(EVALUATION_ID, reviewRequest("REJECT", "交付批次口径不一致", 1));

        ArgumentCaptor<PerformanceEvaluation> captor = ArgumentCaptor.forClass(PerformanceEvaluation.class);
        verify(evaluationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("RETURNED");
        assertThat(captor.getValue().getReviewComment()).isEqualTo("交付批次口径不一致");
        assertThat(captor.getValue().getReviewedBy()).isEqualTo(auditor.getId());

        assertThat(response.getStatus()).isEqualTo("RETURNED");
        verify(supplierMapper, never()).updateById(any(Supplier.class));
        verify(supplierMapper, never()).selectById(anyLong());
        verify(auditLogService).record(eq(EntityType.PERFORMANCE_EVALUATION), eq(EVALUATION_ID),
                eq(SUPPLIER_ID), eq(auditor), eq(AuditAction.REVIEW_REJECT.name()),
                eq("PENDING_REVIEW"), eq("RETURNED"), eq(AuditResult.SUCCESS), eq("交付批次口径不一致"));
    }

    // ==================== §3.4 list：行级隔离与分页收敛 ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private LambdaQueryWrapper<PerformanceEvaluation> capturedListWrapper() {
        ArgumentCaptor<Wrapper<PerformanceEvaluation>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(evaluationMapper).selectPage(any(), captor.capture());
        return (LambdaQueryWrapper<PerformanceEvaluation>) captor.getValue();
    }

    private Page<PerformanceEvaluation> pageOf(int page, int size, long total,
                                               List<PerformanceEvaluation> records) {
        Page<PerformanceEvaluation> p = new Page<>(page, size);
        p.setRecords(records);
        p.setTotal(total);
        return p;
    }

    /** STAFF 必须把 created_by 本人条件<b>下推 SQL</b>，绝不依赖前端过滤 */
    @Test
    void listForStaffPushesCreatedByIsolationIntoSql() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        service.list(null, null, 1, 20);

        LambdaQueryWrapper<PerformanceEvaluation> wrapper = capturedListWrapper();
        String sqlSegment = wrapper.getSqlSegment();
        assertThat(sqlSegment).contains("created_by =");
        assertThat(wrapper.getParamNameValuePairs().containsValue(staff.getId())).isTrue();
    }

    /** AUDITOR / ADMIN 全量可见，不得被加上 created_by 条件（裁决 2026-10-14 晚同口径） */
    @ParameterizedTest
    @ValueSource(strings = {"AUDITOR", "ADMIN"})
    void listForAuditorAndAdminHasNoOwnerFilter(String role) {
        UserPrincipal me = new UserPrincipal(9L, "u", null, "n", Role.valueOf(role), true);
        when(currentUserProvider.require()).thenReturn(me);
        when(evaluationMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 1, List.of(evaluation("APPROVED", 2L, 2))));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(2L, "staff01", "张三")));

        PageResult<PerformanceEvaluationResponse> result = service.list(null, null, 1, 20);

        assertThat(capturedListWrapper().getSqlSegment()).doesNotContain("created_by");
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getCreatedByName()).isEqualTo("张三");
    }

    /** supplier_id / status 两枚过滤器按需下推，绑定值走占位符不进 SQL 文本 */
    @Test
    void listPushesSupplierAndStatusFilters() {
        when(currentUserProvider.require()).thenReturn(admin);
        when(evaluationMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        service.list(SUPPLIER_ID, "PENDING_REVIEW", 1, 20);

        LambdaQueryWrapper<PerformanceEvaluation> wrapper = capturedListWrapper();
        String sql = wrapper.getSqlSegment();
        assertThat(sql).contains("supplier_id =").contains("status =").doesNotContain("PENDING_REVIEW");
        assertThat(wrapper.getParamNameValuePairs().values())
                .contains(SUPPLIER_ID, "PENDING_REVIEW");
    }

    /** 空页不触库反查人名（避免 N+1 与无谓查询） */
    @Test
    void listSkipsUserLookupOnEmptyPage() {
        when(currentUserProvider.require()).thenReturn(admin);
        when(evaluationMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        PageResult<PerformanceEvaluationResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList()).isEmpty();
        assertThat(result.getTotal()).isZero();
        verifyNoInteractions(userMapper);
    }

    /**
     * 分页收敛（沿用 §1.3 范式，契约 §3.4"分页参数非法 40001"与本裁决的取舍已留痕备案）：
     * page≤0 折 1；page_size≤0 折 20；page_size&gt;100 折 100。
     */
    @ParameterizedTest
    @CsvSource({"0, 0, 1, 20", "-5, 500, 1, 100", "3, 10, 3, 10"})
    void listClampsPagingParameters(int page, int pageSize, int expectedPage, int expectedSize) {
        when(currentUserProvider.require()).thenReturn(admin);
        when(evaluationMapper.selectPage(any(), any())).thenReturn(pageOf(1, 20, 0, List.of()));

        PageResult<PerformanceEvaluationResponse> result = service.list(null, null, page, pageSize);

        ArgumentCaptor<Page<PerformanceEvaluation>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(evaluationMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(expectedPage);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(expectedSize);
        assertThat(result.getPage()).isEqualTo(expectedPage);
        assertThat(result.getPageSize()).isEqualTo(expectedSize);
    }

    /** created_by 悬空（历史脏数据/FK 缺失）→ 人名回退空串，绝不让整张评价单 500 */
    @Test
    void listFallsBackToEmptyNameWhenCreatorMissing() {
        when(currentUserProvider.require()).thenReturn(admin);
        when(evaluationMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 1, List.of(evaluation("APPROVED", 999L, 2))));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of());

        PageResult<PerformanceEvaluationResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList().get(0).getCreatedByName()).isEmpty();
    }

    /** real_name 为空白的历史用户 → 回退登录名 */
    @Test
    void listFallsBackToUsernameWhenRealNameBlank() {
        when(currentUserProvider.require()).thenReturn(admin);
        when(evaluationMapper.selectPage(any(), any()))
                .thenReturn(pageOf(1, 20, 1, List.of(evaluation("APPROVED", 7L, 2))));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(7L, "staff07", "  ")));

        PageResult<PerformanceEvaluationResponse> result = service.list(null, null, 1, 20);

        assertThat(result.getList().get(0).getCreatedByName()).isEqualTo("staff07");
    }

    // ==================== §3.5 get：只读与越权 ====================

    @Test
    void getRejectsMissingEvaluationWith40401() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID)).thenReturn(null);

        expectCode(40401, MSG_EVALUATION_NOT_FOUND, () -> service.get(EVALUATION_ID));
    }

    /** STAFF 读他人单 → 40302（与 §1.4 同口径，不泄露存在性之外信息） */
    @Test
    void getRejectsStaffReadingOthersWith40302() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", 999L, 1));

        expectCode(40302, MSG_SELF_ONLY, () -> service.get(EVALUATION_ID));
    }

    /** 只读铁律：详情读取零审计、零写库 */
    @Test
    void detailIsReadOnlyWithoutAudit() {
        when(currentUserProvider.require()).thenReturn(auditor);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(userMapper.selectById(staff.getId())).thenReturn(user(staff.getId(), "staff01", "张三"));

        PerformanceEvaluationResponse response = service.get(EVALUATION_ID);

        assertThat(response.getFactRecord()).isNotNull();
        assertThat(response.getResult().getGrade()).isEqualTo("A");
        assertThat(response.getResult().getScoreQuality()).isEqualByComparingTo("92.50");
        assertThat(response.getReviewedAt()).isNull();
        assertThat(response.getReviewedBy()).isNull();
        verifyNoInteractions(auditLogService);
        verify(evaluationMapper, never()).updateById(any(PerformanceEvaluation.class));
    }

    /** STAFF 读本人单正常放行，且未复核时 result 的免考/风险标按库值输出 false 而非 null */
    @Test
    void staffCanReadOwnEvaluation() {
        when(currentUserProvider.require()).thenReturn(staff);
        when(evaluationMapper.selectById(EVALUATION_ID))
                .thenReturn(evaluation("PENDING_REVIEW", staff.getId(), 1));
        when(userMapper.selectById(staff.getId())).thenReturn(null);

        PerformanceEvaluationResponse response = service.get(EVALUATION_ID);

        assertThat(response.getCreatedByName()).isEmpty();
        assertThat(response.getResult().getQualityExempt()).isFalse();
        assertThat(response.getResult().getRiskWarning()).isFalse();
    }
}
