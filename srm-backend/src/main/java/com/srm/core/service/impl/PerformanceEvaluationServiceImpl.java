package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.calculator.PerformanceMetricProvider;
import com.srm.core.calculator.PerformanceScoreCalculator;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.performance.PerformanceEvaluationCreateRequest;
import com.srm.core.dto.performance.PerformanceEvaluationResponse;
import com.srm.core.dto.performance.PerformanceEvaluationResult;
import com.srm.core.dto.performance.PerformanceEvaluationUpdateRequest;
import com.srm.core.dto.performance.PerformanceFactRecord;
import com.srm.core.dto.performance.PerformanceReviewDecisionRequest;
import com.srm.core.dto.performance.PerformanceValidationPatterns;
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
import com.srm.core.service.PerformanceEvaluationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模块三·绩效评价实现（契约 §3.1–§3.5，docs/api-spec.md §4）。
 *
 * <h2>防篡改铁律的三条结构保证（rules.md §4.3、验收 §2.1）</h2>
 * <ol>
 *   <li><b>入参物理无分数</b>：三个写 DTO 只有事实/版本/决策，本类不存在任何
 *       "把入参分数写进实体"的赋值语句；</li>
 *   <li><b>算分单一出口</b>：五维分、总分、评级、免考、风险预警<b>只</b>来自
 *       {@link PerformanceScoreCalculator#calculate}，本类只负责把返回的 9 枚结果
 *       原样平铺进实体（{@link #applyResult}），不参与任何四则运算；</li>
 *   <li><b>取数零分支</b>：按 {@code mode} 从 Spring 注入的
 *       {@code Map<String, PerformanceMetricProvider>}（bean name = mode 码值）直取适配器，
 *       api-spec §7 L543 明令的"禁止 if/else 判模式"在本类里是<b>零处</b>模式判断。</li>
 * </ol>
 *
 * <h2>判序风格（与 LifecycleRequestServiceImpl / SupplierServiceImpl 同族）</h2>
 * 角色 → 存在性 → 归属 → 状态 → 并发（version）→ 业务规则 → 写库 → 联动 → 审计。
 * <b>失败路径零留痕</b>：审计写入一律排在写库成功之后，403xx/40901/40902 都不落 AuditLog
 * （与既有四域实现一致；验收 §3.1 要求失败也写 {@code result=REJECTED} 一条，
 * 该口径与全项目既有实现相悖，已留痕上报待人类裁决，本类不自造第二套规矩）。
 *
 * <h2>状态机（绩效三态，与供应商/资质/申请单枚举互不复用）</h2>
 * {@code PENDING_REVIEW → APPROVED}（归档终态，永久不可改，无独立历史表）；
 * {@code PENDING_REVIEW → RETURNED →（§3.2 重提）→ PENDING_REVIEW}。
 * 状态只能由本类 §3.1/§3.2/§3.3 三条迁移路径改写，入参 DTO 物理上不含 {@code status}，
 * 不存在"直接 PATCH 状态"的旁路。
 *
 * <h2>并发双保险（验收 §1.8）</h2>
 * 同供应商同周期在途唯一：应用层预检给友好 40902，DB 生成列
 * {@code uk_supplier_active_period} 兜住并发窗口，{@code DuplicateKeyException}
 * 必须转译 40902，不得原抛成 50001 或泄露 SQL。
 */
@Service
public class PerformanceEvaluationServiceImpl implements PerformanceEvaluationService {

    /** 绩效三态（契约 §3 状态机，schema.sql L122 DEFAULT 'PENDING_REVIEW'） */
    private static final String EVAL_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String EVAL_APPROVED = "APPROVED";
    private static final String EVAL_RETURNED = "RETURNED";

    /** 供应商终态：被 ELIMINATED 的供应商不得再被评价（契约 §3.1 参数表"存在且未 ELIMINATED"） */
    private static final String SUP_ELIMINATED = "ELIMINATED";

    /** mode 缺省值（DB DEFAULT 'manual'，契约 §3.1 参数表"manual（默认）"） */
    private static final String MODE_MANUAL = "manual";

    /** §3.3 决策二值（入参层 @Pattern 同套值域，本类是第二道闸） */
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REJECT = "REJECT";

    /** 评级低档：C/D 须给供应商打风险标（A1 表 risk_warning 行 + 任务 B2-3） */
    private static final String GRADE_C = "C";
    private static final String GRADE_D = "D";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    /** 分页口径沿用 §1.3 收敛范式（api-spec §0：默认 20、上限 100、页码最小 1） */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /** 文案全部引用绩效域常量源，不在本类写第二套字面量 */
    private static final String MSG_FACT_INVALID = PerformanceValidationPatterns.FACT_INVALID_MSG;
    private static final String MSG_PARAM_INVALID = PerformanceValidationPatterns.PARAM_INVALID_MSG;
    private static final String MSG_REJECT_COMMENT = PerformanceValidationPatterns.REJECT_COMMENT_MSG;
    private static final String MSG_VERSION_CONFLICT = PerformanceValidationPatterns.VERSION_CONFLICT_MSG;
    private static final String MSG_STATUS_NOT_ALLOWED = PerformanceValidationPatterns.STATUS_NOT_ALLOWED_MSG;
    private static final String MSG_IN_FLIGHT_EXISTS = PerformanceValidationPatterns.IN_FLIGHT_EXISTS_MSG;
    private static final String MSG_SUPPLIER_NOT_FOUND = PerformanceValidationPatterns.SUPPLIER_NOT_FOUND_MSG;
    private static final String MSG_EVALUATION_NOT_FOUND = PerformanceValidationPatterns.EVALUATION_NOT_FOUND_MSG;

    private final PerformanceEvaluationMapper evaluationMapper;
    private final SupplierMapper supplierMapper;
    private final UserMapper userMapper;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;
    private final PerformanceScoreCalculator scoreCalculator;

    /**
     * bean name → 适配器。Spring 对 {@code Map<接口>} 的注入语义即"按名取 bean"，
     * 新增 mode（如二期 external）只需多注册一个 bean，本类零改动（对扩展开放）。
     */
    private final Map<String, PerformanceMetricProvider> metricProviders;

    public PerformanceEvaluationServiceImpl(PerformanceEvaluationMapper evaluationMapper,
                                            SupplierMapper supplierMapper,
                                            UserMapper userMapper,
                                            RoleGuard roleGuard,
                                            CurrentUserProvider currentUserProvider,
                                            AuditLogService auditLogService,
                                            PerformanceScoreCalculator scoreCalculator,
                                            Map<String, PerformanceMetricProvider> metricProviders) {
        this.evaluationMapper = evaluationMapper;
        this.supplierMapper = supplierMapper;
        this.userMapper = userMapper;
        this.roleGuard = roleGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
        this.scoreCalculator = scoreCalculator;
        this.metricProviders = metricProviders;
    }

    // ==================== §3.1 发起评价（一步算分落待审） ====================

    /**
     * 一步式：取数 → 算分 → 落 {@code PENDING_REVIEW}，<b>无草稿态</b>（裁决 20261010 方案 A）。
     * 入参不含 version（新建无旧版本），status/version 由本方法定死。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceEvaluationResponse create(PerformanceEvaluationCreateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireEvaluableSupplier(request.getSupplierId());
        LocalDate periodStart = requirePeriod(request.getPeriodStart());
        LocalDate periodEnd = requirePeriod(request.getPeriodEnd());
        if (!periodEnd.isAfter(periodStart)) {
            // 周期区间非法归 40001「事实数据校验失败」（契约 §3.1 矩阵同格），不是 40302
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_FACT_INVALID);
        }

        String mode = normalizeMode(request.getMode());
        PerformanceFactRecord facts = requireProvider(mode)
                .fetchFacts(supplier.getId(), periodStart, periodEnd, request.getFactRecord());
        requireNoInFlightEvaluation(supplier.getId(), periodStart, periodEnd);

        PerformanceEvaluationResult result = scoreCalculator.calculate(facts, supplier.getId());

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        PerformanceEvaluation row = new PerformanceEvaluation();
        row.setSupplierId(supplier.getId());
        row.setPeriodStart(periodStart);
        row.setPeriodEnd(periodEnd);
        // 落库存规范化 mode：出参回显与 §3.2 重提取适配器都以此为准，杜绝 " manual " 这类脏值
        row.setMode(mode);
        row.setFactRecord(facts);
        applyResult(row, result);
        // 状态与版本号由后端定死，入参物理上不含这两枚字段（提交即锁死）
        row.setStatus(EVAL_PENDING_REVIEW);
        row.setVersion(1);
        row.setCreatedBy(me.getId());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setDeleted(0);
        // reviewed_* 三枚留 NULL，等 §3.3 复核回写

        insertTranslatingDuplicateKey(row);
        // 评价单自身状态维度：从无到 PENDING_REVIEW，故 old_status=null
        auditLogService.record(EntityType.PERFORMANCE_EVALUATION, row.getId(), row.getSupplierId(), me,
                AuditAction.SUBMIT.name(), null, EVAL_PENDING_REVIEW, AuditResult.SUCCESS, null);
        return toResponse(row, displayName(me.getId()));
    }

    // ==================== §3.2 退回后修改事实重提 ====================

    /**
     * 沿用同一条记录（不新建），重新取数、<b>重新算分</b>、回到 {@code PENDING_REVIEW}。
     * 取数模式<b>不入参</b>（契约 §3.2"不得借重提切换取数模式"）——按落库 {@code row.mode} 取同一 bean。
     * 重提成功写审计 {@code SUBMIT}（用户 2026-10-15 16:42 拍板 B2=甲）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceEvaluationResponse update(Long id, PerformanceEvaluationUpdateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        PerformanceEvaluation row = requireEvaluation(id);
        // 归属先于状态（口径同 §1.2 编辑）：两道闸都是 40302，先判归属可避免"他人单状态外泄"
        roleGuard.requireSelf(row.getCreatedBy());
        roleGuard.requireStatusAllowed(EVAL_RETURNED.equals(row.getStatus()), MSG_STATUS_NOT_ALLOWED);
        requireVersionPresent(request.getVersion());

        PerformanceFactRecord facts = requireProvider(normalizeMode(row.getMode()))
                .fetchFacts(row.getSupplierId(), row.getPeriodStart(), row.getPeriodEnd(), request.getFactRecord());
        PerformanceEvaluationResult result = scoreCalculator.calculate(facts, row.getSupplierId());

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        row.setFactRecord(facts);
        applyResult(row, result);
        row.setStatus(EVAL_PENDING_REVIEW);
        row.setUpdatedAt(now);
        row.setVersion(request.getVersion());
        // 重提即回到待审新状态，清空上一次复核痕迹三字段（用户 2026-10-15 18:50 拍板裁决③），
        // 避免旧复核结论残留误导复核人。实体侧 reviewed_* 已声明 updateStrategy=IGNORED，
        // 使这三枚 null 在 updateById 时真实写入 SET ... = NULL（NOT_NULL 默认会静默跳过）；
        // 落库效果由 PerformanceEvaluationIntegrationTest 端到端兜证（裁决③要求不得静默不生效）
        row.setReviewedBy(null);
        row.setReviewedAt(null);
        row.setReviewComment(null);
        if (evaluationMapper.updateById(row) == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
        auditLogService.record(EntityType.PERFORMANCE_EVALUATION, row.getId(), row.getSupplierId(), me,
                AuditAction.SUBMIT.name(), EVAL_RETURNED, EVAL_PENDING_REVIEW, AuditResult.SUCCESS, null);
        return toResponse(row, displayName(row.getCreatedBy()));
    }

    // ==================== §3.3 复核（归档 / 退回） ====================

    /**
     * 只能复核 {@code PENDING_REVIEW}；APPROVE 归档并<b>同事务</b>回写供应商
     * {@code latest_performance_grade}/{@code risk_warning}（只打标，不改供应商状态——
     * 停用必须走 §2.4/§2.6 两步流程，验收 §2.4）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PerformanceEvaluationResponse review(Long id, PerformanceReviewDecisionRequest request) {
        roleGuard.requireRole(Role.AUDITOR);
        PerformanceEvaluation row = requireEvaluation(id);
        // 状态闸先于乐观锁闸：已归档单重复审是"合法性"问题，不该被并发解释掩盖真实原因
        roleGuard.requireStatusAllowed(EVAL_PENDING_REVIEW.equals(row.getStatus()), MSG_STATUS_NOT_ALLOWED);

        boolean approve = isApprove(request.getDecision());
        String comment = trimToNull(request.getComment());
        if (!approve && comment == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_REJECT_COMMENT);
        }
        requireVersionPresent(request.getVersion());

        Supplier supplier = null;
        if (approve) {
            // 归档联动的取数前置：供应商若已在建单后被删除，宁可在写库前 40401，也不留半归档状态
            // （ELIMINATED 不拦：历史周期评价仍须允许归档，只是不打评级之外的任何动作）
            supplier = requireSupplier(row.getSupplierId());
        }

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        String targetStatus = approve ? EVAL_APPROVED : EVAL_RETURNED;
        row.setStatus(targetStatus);
        row.setReviewedBy(me.getId());
        row.setReviewedAt(now);
        row.setReviewComment(comment);
        row.setUpdatedAt(now);
        // 入参 version 是<b>评价单</b>的版本；供应商联动行的 version 由 @Version 用库内值自增，
        // 两个实体各有版本号，混用会把并发判定判错对象（口径同 §2.6 终审）
        row.setVersion(request.getVersion());
        if (evaluationMapper.updateById(row) == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }

        if (approve) {
            supplier.setLatestPerformanceGrade(row.getGrade());
            supplier.setRiskWarning(supplierRiskWarning(row));
            supplier.setUpdatedAt(now);
            if (supplierMapper.updateById(supplier) == 0) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
            }
        }

        auditLogService.record(EntityType.PERFORMANCE_EVALUATION, row.getId(), row.getSupplierId(), me,
                (approve ? AuditAction.REVIEW_APPROVE : AuditAction.REVIEW_REJECT).name(),
                EVAL_PENDING_REVIEW, targetStatus, AuditResult.SUCCESS, comment);
        return toResponse(row, displayName(row.getCreatedBy()));
    }

    // ==================== §3.4 列表（分页 + §0 行级隔离） ====================

    @Override
    public PageResult<PerformanceEvaluationResponse> list(Long supplierId, String status, int page, int pageSize) {
        UserPrincipal me = currentUserProvider.require();
        int safePage = Math.max(page, 1);
        int safePageSize = clampPageSize(pageSize);

        LambdaQueryWrapper<PerformanceEvaluation> wrapper = new LambdaQueryWrapper<PerformanceEvaluation>()
                .eq(supplierId != null, PerformanceEvaluation::getSupplierId, supplierId)
                // 状态过滤器原样透传：非法码值查不到即空集，不新造 40001 规则（沿用 §1.3 口径）
                .eq(StringUtils.hasText(status), PerformanceEvaluation::getStatus, status);
        if (me.getRole() == Role.STAFF) {
            // §0 行级隔离下推 SQL：STAFF 仅见本人创建的单；AUDITOR/ADMIN 全量（裁决 2026-10-14 晚同口径）
            wrapper.eq(PerformanceEvaluation::getCreatedBy, me.getId());
        }
        wrapper.orderByDesc(PerformanceEvaluation::getCreatedAt)
                .orderByDesc(PerformanceEvaluation::getId);

        IPage<PerformanceEvaluation> result = evaluationMapper.selectPage(new Page<>(safePage, safePageSize), wrapper);
        List<PerformanceEvaluation> records = result.getRecords();
        Map<Long, String> names = creatorNames(records);
        List<PerformanceEvaluationResponse> list = records.stream()
                .map(record -> toResponse(record, names.getOrDefault(record.getCreatedBy(), "")))
                .collect(Collectors.toList());
        return new PageResult<>(list, result.getTotal(), safePage, safePageSize);
    }

    // ==================== §3.5 详情（只读） ====================

    @Override
    public PerformanceEvaluationResponse get(Long id) {
        UserPrincipal me = currentUserProvider.require();
        PerformanceEvaluation row = requireEvaluation(id);
        if (me.getRole() == Role.STAFF) {
            // 越权 40302，与 §1.4 同口径：不泄露存在性之外的信息，也不落审计（只读零留痕）
            roleGuard.requireSelf(row.getCreatedBy());
        }
        return toResponse(row, displayName(row.getCreatedBy()));
    }

    // ==================== 校验与取数 ====================

    /** 评价单存在性：{@code @TableLogic} 使逻辑删除行天然隐身，故查不到即 40401「评价记录不存在」 */
    private PerformanceEvaluation requireEvaluation(Long id) {
        PerformanceEvaluation row = evaluationMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_EVALUATION_NOT_FOUND);
        }
        return row;
    }

    /** §3.1 被评供应商存在性（绩效域 40401 子文案「供应商不存在」，用户 2026-10-15 16:42 拍板 B5） */
    private Supplier requireSupplier(Long supplierId) {
        Supplier supplier = supplierMapper.selectById(supplierId);
        if (supplier == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    /** 发起评价专用：存在 + 未 ELIMINATED。终态供应商不可再评（契约 §3.1 参数表），终态命中同回 40401 */
    private Supplier requireEvaluableSupplier(Long supplierId) {
        Supplier supplier = requireSupplier(supplierId);
        if (SUP_ELIMINATED.equals(supplier.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    /**
     * 周期解析：{@code \d{4}-\d{2}-\d{2}} 形状由入参层 @Pattern 收口，本方法管<b>语义</b>
     * （2026-02-31 这类形状合法但不存在的日期），两类一律折 40001「事实数据校验失败」，不得冒 50001。
     */
    private LocalDate requirePeriod(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_FACT_INVALID);
        }
    }

    /** mode 缺省即 manual（契约 §3.1 参数表"manual（默认）"），非空则 trim 后原样用于取 bean */
    private String normalizeMode(String mode) {
        return StringUtils.hasText(mode) ? mode.trim() : MODE_MANUAL;
    }

    /**
     * 按 mode 直取适配器（<b>零 if/else 判模式</b>）。取不到 bean 属配置级异常，
     * 折 40001「参数校验失败」而不是让其 NPE 成 50001。
     */
    private PerformanceMetricProvider requireProvider(String mode) {
        PerformanceMetricProvider provider = metricProviders.get(mode);
        if (provider == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
        }
        return provider;
    }

    /** 同周期在途预检（PENDING_REVIEW/RETURNED 均算在途，仅 APPROVED 归档后让位） */
    private void requireNoInFlightEvaluation(Long supplierId, LocalDate periodStart, LocalDate periodEnd) {
        Long conflicts = evaluationMapper.selectCount(new LambdaQueryWrapper<PerformanceEvaluation>()
                .eq(PerformanceEvaluation::getSupplierId, supplierId)
                .eq(PerformanceEvaluation::getPeriodStart, periodStart)
                .eq(PerformanceEvaluation::getPeriodEnd, periodEnd)
                .in(PerformanceEvaluation::getStatus, EVAL_PENDING_REVIEW, EVAL_RETURNED));
        if (Optional.ofNullable(conflicts).orElse(0L) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_IN_FLIGHT_EXISTS);
        }
    }

    /** 预检通过后的并发窗口由 DB 生成列唯一索引 uk_supplier_active_period 兜底，同样折 40902 */
    private void insertTranslatingDuplicateKey(PerformanceEvaluation row) {
        try {
            evaluationMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_IN_FLIGHT_EXISTS);
        }
    }

    /** §3.3 决策二值分流；非 APPROVE/REJECT 的脏值折 40001（入参层 @Pattern 之外的第二道闸） */
    private boolean isApprove(String decision) {
        if (DECISION_APPROVE.equals(decision)) {
            return true;
        }
        if (DECISION_REJECT.equals(decision)) {
            return false;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
    }

    /** version 缺失 → 40901（并发语义，口径同 §2.1/§2.6；0/负数已由入参层 @Min 折 40001） */
    private void requireVersionPresent(Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
    }

    /** 纯空白等同未填写（与 §2.2/§2.3/§2.6 comment 同一口径） */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    // ==================== 算分结果搬运与派生 ====================

    /**
     * 把唯一算分引擎的 9 枚结果<b>原样</b>平铺进实体 9 枚 DECIMAL/VARCHAR/TINYINT 列。
     * 本方法是"分数入实体"的唯一语句块——除这里之外，本类没有任何给分数赋值的地方。
     */
    private void applyResult(PerformanceEvaluation row, PerformanceEvaluationResult result) {
        row.setScoreQuality(result.getScoreQuality());
        row.setScoreDelivery(result.getScoreDelivery());
        row.setScorePrice(result.getScorePrice());
        row.setScoreService(result.getScoreService());
        row.setScoreCompliance(result.getScoreCompliance());
        row.setTotalScore(result.getTotalScore());
        row.setGrade(result.getGrade());
        row.setQualityExempt(result.getQualityExempt());
        row.setRiskWarning(result.getRiskWarning());
    }

    /**
     * 供应商风险标 = 评价单自身风险标（存在过期已批资质）<b>或</b> 评级 C/D
     * （契约 A1 risk_warning 行"最近一次归档为 C/D 或存在过期已批资质"）。
     * 只打标，绝不改供应商状态。
     */
    private boolean supplierRiskWarning(PerformanceEvaluation row) {
        if (Boolean.TRUE.equals(row.getRiskWarning())) {
            return true;
        }
        return GRADE_C.equals(row.getGrade()) || GRADE_D.equals(row.getGrade());
    }

    // ==================== 出参装配 ====================

    /** 分页口径（api-spec §0）：page&lt;=0 折 1；page_size&lt;=0 折 20、&gt;100 折 100（沿用 §1.3 收敛范式） */
    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /** 列表页创建人姓名批量反查，避免 N+1；空页不触库 */
    private Map<Long, String> creatorNames(List<PerformanceEvaluation> records) {
        if (records.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> creatorIds = records.stream()
                .map(PerformanceEvaluation::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return userMapper.selectBatchIds(creatorIds).stream()
                // 按主键批量反查不会返回重复 ID，无需 merge 函数
                .collect(Collectors.toMap(User::getId, this::displayName));
    }

    /**
     * 单条反查创建人展示名。{@code created_by} 是 DB NOT NULL 列，故本方法<b>不设 null 分支</b>
     * （留一枚生产不可达的分支就等于少一枚真实覆盖，与 SupplierServiceImpl 同口径）。
     * 展示名优先真实姓名、缺失回退登录名；查不到用户（FK 悬空/历史脏数据）由
     * {@link #displayName(User)} 回退空串，绝不 NPE——出参装配不能因一名用户记录缺失而打挂整张评价单。
     */
    private String displayName(Long userId) {
        return displayName(userMapper.selectById(userId));
    }

    private String displayName(User user) {
        if (user == null) {
            return "";
        }
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
    }

    /** period_start/period_end 为 DB NOT NULL 列，无 null 分支可言 */
    private String format(LocalDate date) {
        return date.format(DATE_FORMAT);
    }

    private String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_FORMAT);
    }

    /**
     * 出站 16 字段（附录 A4.2）：{@code result} 为 DB 平铺 9 列的嵌套视图（A4.3），
     * 未复核单的 reviewed_by/reviewed_at/review_comment 三枚为 null。
     * active_period_key / deleted 两列物理不在本映射内，天然不会外泄。
     */
    private PerformanceEvaluationResponse toResponse(PerformanceEvaluation row, String createdByName) {
        PerformanceEvaluationResult result = PerformanceEvaluationResult.builder()
                .scoreQuality(row.getScoreQuality())
                .scoreDelivery(row.getScoreDelivery())
                .scorePrice(row.getScorePrice())
                .scoreService(row.getScoreService())
                .scoreCompliance(row.getScoreCompliance())
                .totalScore(row.getTotalScore())
                .grade(row.getGrade())
                .qualityExempt(row.getQualityExempt())
                .riskWarning(row.getRiskWarning())
                .build();
        return PerformanceEvaluationResponse.builder()
                .id(row.getId())
                .supplierId(row.getSupplierId())
                .periodStart(format(row.getPeriodStart()))
                .periodEnd(format(row.getPeriodEnd()))
                .mode(row.getMode())
                .factRecord(row.getFactRecord())
                .result(result)
                .status(row.getStatus())
                .version(row.getVersion())
                .createdBy(row.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(format(row.getCreatedAt()))
                .updatedAt(format(row.getUpdatedAt()))
                .reviewedBy(row.getReviewedBy())
                .reviewedAt(format(row.getReviewedAt()))
                .reviewComment(row.getReviewComment())
                .build();
    }
}
