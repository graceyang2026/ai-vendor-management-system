package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.audit.EntityType;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.lifecycle.LifecycleDecisionRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestCreateRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestResponse;
import com.srm.core.dto.lifecycle.LifecycleRequestTodoItem;
import com.srm.core.dto.lifecycle.LifecycleValidationPatterns;
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
import com.srm.core.service.LifecycleRequestService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模块二 §2.4/§2.5/§2.6 生命周期申请实现（契约 §2.4-§2.6 + 附录 A3，docs/api-spec.md §3）。
 *
 * 设计要点：
 * - <b>两步流程是唯一状态入口</b>：§2.4 只 insert 一张 PENDING 申请单，<b>物理上碰不到 supplier.status</b>；
 *   真正改供应商状态的只有 §2.6 的 APPROVE 分支（同事务联动）。REJECT 分支只改申请单，
 *   供应商状态与 version 分毫不差（acceptance §1.3、契约 §2.6 L511）。
 * - <b>类型-状态门槛三段判</b>：① {@code ELIMINATED} 终态一律禁建（契约 §2.4 L443）；
 *   ② 类型与当前状态不匹配禁建（L441）；③ §2.6 决策 APPROVE 时<b>再判一次</b>同一门槛——
 *   申请从提交到终审之间供应商可能已被并发改状态，L554 的"供应商已被并发淘汰 → 40302"就落在这一步，
 *   绝不能只信建单时的历史快照。
 * - <b>在途唯一双保险</b>：应用层 selectCount 预检（友好 40902「已存在在途申请，请勿重复提交」）
 *   + DB 生成列 uk_supplier_active_lifecycle 兜底（并发窗口），DuplicateKeyException 转译同一码值，
 *   不得冒 50001、不得泄露 SQL。
 * - <b>乐观锁主体是申请单</b>：§2.6 入参 version 取 §2.5 列表返回的申请单 version（裁决 20261013 候选 A）；
 *   供应商主表那一行由 {@code @Version} 用<b>库内当前值</b>自增，不接受前端传值（前端传的是申请单版本）。
 *   影响 0 行一律 40901，整体事务回滚（申请单与供应商都不会出现"半改"状态）。
 * - <b>version 缺失归 40901 而非 40001</b>：契约 §2.6 L512 与 api-spec §3 L318 明文"不回传或版本不匹配
 *   → 40901"，而 L552 矩阵把"version 缺失"写进 40001，两处自相矛盾；按契约文档 L950
 *   「口径冲突一律以 docs/api-spec.md 为权威」取 40901，与 §2.1/§2.2 同批对齐（已备案待裁决）。
 * - <b>§2.4 仅建档人可提（用户裁决 20261015 收紧，旧"协同可提"结论作废）</b>：依据 §0 规则 B，
 *   公有资产区对非建档人<b>只读</b>（可读不可提）；api-spec §3 L324 角色列已更新为 {@code STAFF（本人）}、
 *   契约 §2.4 L440 明文"仅建档人可提"。故查出供应商后即 {@code requireSelf(supplier.created_by)} →
 *   40302「仅限本人操作」，复用 RoleGuard.requireSelf 现有实现，口径与 §1.2 编辑他人档案、§1.5 删除
 *   一致；归属闸先于类型-状态门槛与在途预检（判序详见 create() 内注释）。
 * - <b>审计码零新增（方案甲）</b>：SUBMIT / DECISION_APPROVE / DECISION_REJECT 复用既有 11 码，
 *   entity_type=LIFECYCLE_REQUEST，entity_id=申请单 ID，冗余写 supplier_id；
 *   只读接口 §2.5 绝不落审计（契约 L493"不写审计"）。
 * - 派生两枚人名字段（applied_by_name / decided_by_name）本表无物理列，列表页批量反查避免 N+1。
 */
@Service
public class LifecycleRequestServiceImpl implements LifecycleRequestService {

    /** 供应商六态中本域关心的三态（NORMAL/SUSPENDED/ELIMINATED） */
    private static final String SUP_NORMAL = "NORMAL";
    private static final String SUP_SUSPENDED = "SUSPENDED";
    private static final String SUP_ELIMINATED = "ELIMINATED";

    /** 申请类型三码（api-spec §3 LifecycleRequestType） */
    private static final String TYPE_SUSPEND = "SUSPEND";
    private static final String TYPE_RESUME = "RESUME";
    private static final String TYPE_ELIMINATE = "ELIMINATE";

    /** 申请单三态（与供应商/资质/绩效状态枚举严格区分，四套码互不复用） */
    private static final String REQ_PENDING = "PENDING";
    private static final String REQ_APPROVED = "APPROVED";
    private static final String REQ_REJECTED = "REJECTED";

    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REJECT = "REJECT";

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    /** 契约 §2.6 L555 的 40401 文案即泛义「资源不存在」（申请单域无契约登记的专属文案，不自拟） */
    private static final String MSG_REQUEST_NOT_FOUND = "资源不存在";
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    /** 契约 §2.4 L484 原文文案（40902 在途冲突） */
    private static final String MSG_IN_FLIGHT_EXISTS = "已存在在途申请，请勿重复提交";
    /** 契约 §0 L36 文案权威注记：40901 实回「数据已被修改，请刷新后重试」 */
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    /** 契约 §2.4 L481 / §2.6 L552 的 40001 泛义文案 */
    private static final String MSG_PARAM_INVALID = LifecycleValidationPatterns.PARAM_INVALID_MSG;

    private final LifecycleRequestMapper lifecycleRequestMapper;
    private final SupplierMapper supplierMapper;
    private final UserMapper userMapper;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    public LifecycleRequestServiceImpl(LifecycleRequestMapper lifecycleRequestMapper,
                                       SupplierMapper supplierMapper,
                                       UserMapper userMapper,
                                       RoleGuard roleGuard,
                                       CurrentUserProvider currentUserProvider,
                                       AuditLogService auditLogService) {
        this.lifecycleRequestMapper = lifecycleRequestMapper;
        this.supplierMapper = supplierMapper;
        this.userMapper = userMapper;
        this.roleGuard = roleGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
    }

    // ==================== §2.4 创建生命周期申请 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleRequestResponse create(Long supplierId, LifecycleRequestCreateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireSupplier(supplierId);
        // 判序（对齐 §2.1 submit 与 SupplierSubmitAuditIntegrationTest 范式）：角色 → 存在性 →
        // 仅建档人（用户裁决 20261015，契约 §2.4 L440 / api-spec §3 L324）→ 类型门槛 → 在途唯一。
        // 归属闸必须先于门槛与在途预检：非建档人不得借错误码差异探测他人供应商的状态/在途信息
        roleGuard.requireSelf(supplier.getCreatedBy());
        String type = requireValidType(request.getType());

        // 终态闸先于类型闸：ELIMINATED 之后"任何类型"都禁建（契约 §2.4 L443），
        // 单列一道是为了让"终态不可逆"这条业务规则在代码与报错上都自证，而非靠门槛表的副作用兜住
        roleGuard.requireStatusAllowed(!SUP_ELIMINATED.equals(supplier.getStatus()), MSG_STATUS_NOT_ALLOWED);
        roleGuard.requireStatusAllowed(isTypeAllowed(type, supplier.getStatus()), MSG_STATUS_NOT_ALLOWED);
        requireNoInFlightRequest(supplier.getId(), type);

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        LifecycleRequest row = new LifecycleRequest();
        row.setSupplierId(supplier.getId());
        row.setType(type);
        row.setReason(request.getReason().trim());
        // 建单即待审：状态与版本号由后端定死，入参物理上不含这两字段（两步流程铁律）
        row.setStatus(REQ_PENDING);
        row.setVersion(1);
        row.setAppliedBy(me.getId());
        row.setAppliedAt(now);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setDeleted(0);
        // decided_* 三枚留 NULL，等 §2.6 终审回写

        insertTranslatingDuplicateKey(row);
        // 申请单自身的状态维度：从无到 PENDING，故 old_status=null；reason 已落业务行，审计不冗存
        auditLogService.record(EntityType.LIFECYCLE_REQUEST, row.getId(), row.getSupplierId(), me,
                AuditAction.SUBMIT.name(), null, REQ_PENDING, AuditResult.SUCCESS, null);
        return toResponse(row, displayName(me.getId()));
    }

    // ==================== §2.5 申请历史（只读） ====================

    @Override
    public List<LifecycleRequestResponse> listBySupplier(Long supplierId) {
        currentUserProvider.require();               // ALL 登录角色可读，匿名在 Security 层已 401/40101
        Supplier supplier = requireSupplier(supplierId);
        List<LifecycleRequest> rows = lifecycleRequestMapper.selectList(
                new LambdaQueryWrapper<LifecycleRequest>()
                        .eq(LifecycleRequest::getSupplierId, supplier.getId())
                        // 契约 §2.5 L494：按申请时间倒序；同一时刻并发建单时以主键倒序保证分页稳定
                        .orderByDesc(LifecycleRequest::getAppliedAt)
                        .orderByDesc(LifecycleRequest::getId));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, String> names = displayNames(rows);
        return rows.stream().map(row -> toResponse(row, names)).collect(Collectors.toList());
    }

    // ==================== §2.6 终审决策 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LifecycleRequestResponse decide(Long id, LifecycleDecisionRequest request) {
        roleGuard.requireRole(Role.AUDITOR);
        LifecycleRequest row = requireRequest(id);
        // 已决单（APPROVED/REJECTED）不可重复审；本闸必须先于乐观锁，避免掩盖真实失败原因
        roleGuard.requireStatusAllowed(REQ_PENDING.equals(row.getStatus()), MSG_STATUS_NOT_ALLOWED);

        boolean approve = isApprove(request.getDecision());
        String comment = trimToNull(request.getComment());
        requireVersionPresent(request.getVersion());

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        String targetSupplierStatus = null;
        Supplier supplier = null;
        if (approve) {
            // 第三道类型门槛（决策时重判）：建单到终审之间供应商可能已被并发流转，
            // 契约 §2.6 L554"供应商已被并发淘汰 → 40302"必须在这里拦，而不是改完状态再回滚
            supplier = requireSupplier(row.getSupplierId());
            // 先判门槛再取目标态：门槛判定是合法性问题，目标态映射只是合法性通过后的结果
            roleGuard.requireStatusAllowed(isTypeAllowed(row.getType(), supplier.getStatus()),
                    MSG_STATUS_NOT_ALLOWED);
            targetSupplierStatus = targetStatusOf(row.getType());
        }

        // 决策主体先落：申请单状态 + 决策痕迹三列 + 乐观锁判定（入参 version 是<b>申请单</b>版本）
        row.setStatus(approve ? REQ_APPROVED : REQ_REJECTED);
        row.setDecidedBy(me.getId());
        row.setDecidedAt(now);
        row.setDecisionComment(comment);
        row.setUpdatedAt(now);
        row.setVersion(request.getVersion());
        if (lifecycleRequestMapper.updateById(row) == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }

        if (approve) {
            // 同事务联动主表：supplier.version 由 @Version 用库内当前值自增（不接受前端传值——
            // 前端传的是申请单版本，两个实体各有版本号，混用会把并发判定判错对象）
            supplier.setStatus(targetSupplierStatus);
            supplier.setUpdatedAt(now);
            if (supplierMapper.updateById(supplier) == 0) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
            }
        }

        auditLogService.record(EntityType.LIFECYCLE_REQUEST, row.getId(), row.getSupplierId(), me,
                (approve ? AuditAction.DECISION_APPROVE : AuditAction.DECISION_REJECT).name(),
                REQ_PENDING, row.getStatus(), AuditResult.SUCCESS, comment);
        return toResponse(row, displayName(row.getAppliedBy()), displayName(me.getId()));
    }


    // ==================== §2.7 跨供应商聚合查询（Red 骨架，待实现） ====================

    @Override
    public PageResult<LifecycleRequestTodoItem> listTodo(String status, String type, Long supplierId,
                                                         int page, int pageSize) {
        throw new UnsupportedOperationException("§2.7 listTodo not implemented yet (TDD Red)");
    }

    // ==================== 内部方法 ====================

    private Supplier requireSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    private LifecycleRequest requireRequest(Long id) {
        LifecycleRequest row = lifecycleRequestMapper.selectById(id);
        if (row == null) {
            // 逻辑删除行由 @TableLogic 自动隐身，同样落此分支 → 40401
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_REQUEST_NOT_FOUND);
        }
        return row;
    }

    /** 入参层 @Pattern 已拦一次，本方法是第二道闸：未知码值绝不默认当成某一种类型 */
    private String requireValidType(String type) {
        if (TYPE_SUSPEND.equals(type) || TYPE_RESUME.equals(type) || TYPE_ELIMINATE.equals(type)) {
            return type;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
    }

    /** 类型-状态门槛表（契约 §2.4 L441 / api-spec §3 接口表 L324） */
    private boolean isTypeAllowed(String type, String supplierStatus) {
        return switch (type) {
            case TYPE_SUSPEND -> SUP_NORMAL.equals(supplierStatus);
            case TYPE_RESUME -> SUP_SUSPENDED.equals(supplierStatus);
            case TYPE_ELIMINATE -> SUP_NORMAL.equals(supplierStatus) || SUP_SUSPENDED.equals(supplierStatus);
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
        };
    }

    /** APPROVE 时的供应商目标态映射（契约 §2.6 L511，硬编码且仅此一处，不接受任何入参覆盖） */
    private String targetStatusOf(String type) {
        return switch (type) {
            case TYPE_SUSPEND -> SUP_SUSPENDED;
            case TYPE_RESUME -> SUP_NORMAL;
            case TYPE_ELIMINATE -> SUP_ELIMINATED;
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
        };
    }

    /** 在途预检（应用层友好码）：同供应商 + 同类型已存在 PENDING 单 → 40902 */
    private void requireNoInFlightRequest(Long supplierId, String type) {
        long inflight = Optional.ofNullable(lifecycleRequestMapper.selectCount(
                new LambdaQueryWrapper<LifecycleRequest>()
                        .eq(LifecycleRequest::getSupplierId, supplierId)
                        .eq(LifecycleRequest::getType, type)
                        .eq(LifecycleRequest::getStatus, REQ_PENDING))).orElse(0L);
        if (inflight > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_IN_FLIGHT_EXISTS);
        }
    }

    /** 预检通过后的并发窗口由 DB 生成列唯一索引 uk_supplier_active_lifecycle 兜底，同样折 40902 */
    private void insertTranslatingDuplicateKey(LifecycleRequest row) {
        try {
            lifecycleRequestMapper.insert(row);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_IN_FLIGHT_EXISTS);
        }
    }

    private boolean isApprove(String decision) {
        if (DECISION_APPROVE.equals(decision)) {
            return true;
        }
        if (DECISION_REJECT.equals(decision)) {
            return false;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
    }

    /** version 缺失 → 40901（并发语义；0/负数已由入参层 @Min 折 40001），见类头不一致备案 */
    private void requireVersionPresent(Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
    }

    /** 纯空白等同未填写（与 §2.2/§2.3 comment 同一口径） */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /** 列表页人名批量反查（申请人 + 决策人并集），避免 N+1；空集不查库 */
    private Map<Long, String> displayNames(List<LifecycleRequest> rows) {
        Set<Long> userIds = rows.stream()
                .flatMap(row -> java.util.stream.Stream.of(row.getAppliedBy(), row.getDecidedBy()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream()
                // 按主键批量反查不会返回重复 ID，无需 merge 函数
                .collect(Collectors.toMap(User::getId, this::displayName));
    }

    private String displayName(Long userId) {
        if (userId == null) {
            return "";
        }
        return displayName(userMapper.selectById(userId));
    }

    private String displayName(Map<Long, String> names, Long userId) {
        if (userId == null) {
            return "";
        }
        return names.getOrDefault(userId, "");
    }

    /** 展示名优先真实姓名，缺失回退登录名；查不到用户（历史脏数据）回退空串，绝不 NPE */
    private String displayName(User user) {
        if (user == null) {
            return "";
        }
        return StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
    }

    private String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_FORMAT);
    }

    /** 附录 A3 的 13 字段一个不能少；未决单 decided_* 三枚原样为 null */
    private LifecycleRequestResponse toResponse(LifecycleRequest row, String appliedByName) {
        return toResponse(row, appliedByName, null);
    }

    private LifecycleRequestResponse toResponse(LifecycleRequest row, Map<Long, String> names) {
        return toResponse(row, displayName(names, row.getAppliedBy()), displayName(names, row.getDecidedBy()));
    }

    private LifecycleRequestResponse toResponse(LifecycleRequest row, String appliedByName, String decidedByName) {
        return LifecycleRequestResponse.builder()
                .id(row.getId())
                .supplierId(row.getSupplierId())
                .type(row.getType())
                .reason(row.getReason())
                .status(row.getStatus())
                .appliedBy(row.getAppliedBy())
                .appliedByName(appliedByName)
                .appliedAt(format(row.getAppliedAt()))
                .decidedBy(row.getDecidedBy())
                .decidedByName(decidedByName)
                .decidedAt(format(row.getDecidedAt()))
                .decisionComment(row.getDecisionComment())
                .version(row.getVersion())
                .build();
    }
}
