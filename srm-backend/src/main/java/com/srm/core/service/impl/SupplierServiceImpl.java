package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.supplier.SupplierAuditDecisionRequest;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierSubmitRequest;
import com.srm.core.dto.supplier.SupplierUpdateRequest;
import com.srm.core.dto.supplier.SupplierValidationPatterns;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import com.srm.core.service.SupplierService;
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
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 模块一：供应商主链实现（契约 §1.1–§1.5）。
 *
 * 设计要点：
 * - <b>校验分工</b>：单字段格式（40001）在 Controller 入参层一次完成（Bean Validation），本类不重复校验；
 *   唯一性（40902）、归属/状态（40302）、乐观锁（40901）、存在性（40401）只能在 Service 判定。
 *   本类内的日期处理只做两件契约要求的事：String→LocalDate 转换（解析失败折 40001，绝不冒 50001）
 *   与 Partial <b>合并后</b>的先后顺序复核——后者是"提交局部字段 + 存量字段"的组合判断，
 *   入参层看不到存量值，不属于重复校验。
 * - <b>税号唯一双保险</b>：应用层预检（友好 40902）+ DB 生成列 uk_tax_no_active 兜底（并发窗口），
 *   DuplicateKeyException 必须转译成 40902，不得原抛成 50001 或泄露 SQL。
 * - <b>§0 行级隔离后端强制（仅对 STAFF 生效）</b>：过滤条件拼进 SQL（wrapper），绝不依赖前端传参。
 *   用户裁决 2026-10-14 晚："AUDITOR 不受 §0 规则A的约束"——规则 A 私有在途区隔离只卡 STAFF，
 *   AUDITOR/ADMIN 均全量可见（api-spec §2 接口表 "AUDITOR/ADMIN 为全量" 同口径；§0 原文的
 *   "ADMIN 例外" 措辞早于该裁决，以裁决为准）。写操作不受本放宽影响：AUDITOR/ADMIN 仍只读（40301）。
 * - <b>派生字段归二期</b>（用户裁决 2026-10-14 16:15）：business_license_url /
 *   latest_performance_grade / risk_warning 恒 null，本类不做任何派生计算。
 *   注（2026-10-15 模块三落地）：后两枚的<b>写侧</b>已由绩效 §3.3 归档联动回写落库，
 *   本类的<b>读侧仍按原裁决保持 null</b>（未开启"抄存量列值当派生结果"的口径），
 *   两处裁决的张力已留痕上报待人类复核，本类不自擅放宽。
 * - <b>§1.1–§1.5 不落 AuditLog</b>（同一裁决）：契约未要求留痕，AuditAction 维持 11 码不变；
 *   模块二 §2.1/§2.2 则<b>必须</b>留痕（契约 §2.1 L297、§2.2 L354），并冗余写 supplier_id（DDL②）。
 *
 * 模块二状态机（本类新增 submit/audit）设计要点：
 * - <b>唯一状态入口</b>：supplier.status 只能由 §2.1/§2.2 与 §2.6 生命周期终审改写，
 *   本类没有任何"把状态当入参"的方法（§1.2 的 SupplierUpdateRequest 物理上无 status 字段）。
 * - <b>乐观锁三层递进</b>：① 缺失 version → 40901（并发语义，契约 §2.1 候选 B 明文，非格式失败）；
 *   ② 0/负数 → 入参层 40001；③ 与库内不一致 → {@code @Version} 拦截器 update 影响 0 行 → 40901，
 *   事务回滚，状态/资质/审计三处一律不变（acceptance §1.2）。
 * - <b>§2.2 连带批复同事务</b>：审供应商即审资质（裁决 20261012 方案 A），批量批复与状态迁移
 *   共处一个 {@code @Transactional}，任一失败整体回滚；只取本供应商 PENDING 件，
 *   APPROVED 件经生成列唯一索引 uk_supplier_approved_doc 的"旧件逻辑退位"让位（不物理删）。
 * - <b>审计零新增码（方案甲）</b>：连带批复不另落第二笔 SUPPLIER_QUALIFICATION 留痕，
 *   只写 SUPPLIER 域一笔 AUDIT_APPROVE/AUDIT_REJECT，资质归属体现在 audit_log.supplier_id。
 */
@Service
public class SupplierServiceImpl implements SupplierService {

    /** 供应商六态（契约附录 A1 status 行）：本类只涉及迁移的两端与准入后状态，不含生命周期三态 */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_RETURNED = "RETURNED";
    private static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String STATUS_NORMAL = "NORMAL";

    /** 资质三态与证照类型（§2.2 连带批复用；码值权威见 SupplierQualification 实体注释） */
    private static final String QUAL_PENDING = "PENDING";
    private static final String QUAL_APPROVED = "APPROVED";
    private static final String QUAL_REJECTED = "REJECTED";
    private static final String DOC_TYPE_BUSINESS_LICENSE = "BUSINESS_LICENSE";

    /** §2.2/§2.6 决策二值（入参层 @Pattern 同套值域，Service 侧第二道闸） */
    private static final String DECISION_APPROVE = "APPROVE";
    private static final String DECISION_REJECT = "REJECT";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    /** 分页口径（api-spec §0）：默认 20，上限 100，页码最小 1 */
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private static final String MSG_TAX_NO_CONFLICT = "统一社会信用代码已被其他供应商占用";
    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    private static final String MSG_EDITABLE_STATUS = "仅草稿/退回状态允许编辑";
    private static final String MSG_DELETABLE_STATUS = "仅草稿状态可删除";
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    /** 契约 §2.1 错误码矩阵 L308 原文文案 */
    private static final String MSG_LICENSE_REQUIRED = "请先上传有效期内的营业执照";
    /** 契约模块二通用 40302 文案（§2.1 L310 已给，本域各状态闸门共用） */
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    /** 契约 §2.2 错误码矩阵 L366 原文文案 */
    private static final String MSG_REJECT_COMMENT_REQUIRED = SupplierValidationPatterns.REJECT_COMMENT_MSG;
    /** 契约 §2.3 L429 的 40902 文案；§2.2 连带批复若撞上生效位竞态，同口径回传（契约未单列，取同族文案） */
    private static final String MSG_APPROVED_RACE = "业务冲突";

    private final SupplierMapper supplierMapper;
    private final UserMapper userMapper;
    private final SupplierQualificationMapper qualificationMapper;
    private final AuditLogService auditLogService;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;

    public SupplierServiceImpl(SupplierMapper supplierMapper,
                               UserMapper userMapper,
                               SupplierQualificationMapper qualificationMapper,
                               AuditLogService auditLogService,
                               RoleGuard roleGuard,
                               CurrentUserProvider currentUserProvider) {
        this.supplierMapper = supplierMapper;
        this.userMapper = userMapper;
        this.qualificationMapper = qualificationMapper;
        this.auditLogService = auditLogService;
        this.roleGuard = roleGuard;
        this.currentUserProvider = currentUserProvider;
    }

    // ==================== §1.1 建档 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierResponse create(SupplierCreateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        UserPrincipal me = currentUserProvider.require();

        LocalDate effectiveDate = requireDate(request.getEffectiveDate());
        LocalDate expiryDate = requireDate(request.getExpiryDate());
        requireDateOrder(effectiveDate, expiryDate);
        requireTaxNoAvailable(request.getTaxNo(), null);

        Supplier supplier = new Supplier();
        supplier.setName(request.getName());
        supplier.setTaxNo(request.getTaxNo());
        supplier.setType(request.getType());
        supplier.setContactName(request.getContactName());
        supplier.setContactPhone(request.getContactPhone());
        supplier.setContactEmail(request.getContactEmail());
        supplier.setAddress(request.getAddress());
        supplier.setRemark(request.getRemark());
        supplier.setEffectiveDate(effectiveDate);
        supplier.setExpiryDate(expiryDate);
        // 创建即草稿：私有在途区，STAFF 侧仅本人可见（§0 规则 A，AUDITOR/ADMIN 全量），送审是模块二的独立入口
        supplier.setStatus(STATUS_DRAFT);
        supplier.setCreatedBy(me.getId());
        supplier.setVersion(1);
        LocalDateTime now = LocalDateTime.now();
        supplier.setCreatedAt(now);
        supplier.setUpdatedAt(now);
        supplier.setDeleted(0);

        insertTranslatingDuplicateKey(supplier);
        return toResponse(supplier, creatorName(supplier.getCreatedBy()));
    }

    // ==================== §1.2 编辑 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierResponse update(Long id, SupplierUpdateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireSupplier(id);
        // 双保护之归属侧：他人档案（含他人草稿）不可改 → requireSelf 抛 40302
        roleGuard.requireSelf(supplier.getCreatedBy());
        // 双保护之状态侧：PENDING_REVIEW 等状态由 Service 显式拒绝，乐观锁只是第二道闸
        roleGuard.requireStatusAllowed(isEditable(supplier.getStatus()), MSG_EDITABLE_STATUS);

        if (request.getTaxNo() != null && !request.getTaxNo().equals(supplier.getTaxNo())) {
            requireTaxNoAvailable(request.getTaxNo(), id);
        }

        // Partial 合并语义：未提交字段保持存量值，提交了的日期与存量日期一起复核
        LocalDate effectiveDate = mergedDate(request.getEffectiveDate(), supplier.getEffectiveDate());
        LocalDate expiryDate = mergedDate(request.getExpiryDate(), supplier.getExpiryDate());
        requireDateOrder(effectiveDate, expiryDate);

        applyIfPresent(request, supplier);
        supplier.setEffectiveDate(effectiveDate);
        supplier.setExpiryDate(expiryDate);
        supplier.setUpdatedAt(LocalDateTime.now());
        // 乐观锁：WHERE version = 请求携带的版本号（@Version 拦截器负责自增与冲突判定）
        supplier.setVersion(request.getVersion());

        int rows;
        try {
            rows = supplierMapper.updateById(supplier);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_TAX_NO_CONFLICT);
        }
        if (rows == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
        return toResponse(supplier, creatorName(supplier.getCreatedBy()));
    }

    // ==================== §1.3 列表 ====================

    @Override
    public PageResult<SupplierResponse> list(String status, String keyword, int page, int pageSize) {
        UserPrincipal me = currentUserProvider.require();
        int safePage = Math.max(page, 1);
        int safePageSize = clampPageSize(pageSize);

        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<>();
        if (me.getRole() == Role.STAFF) {
            // §0 规则 A 下推 SQL（仅卡 STAFF，裁决 2026-10-14 晚）：公有资产区（非 DRAFT/RETURNED）∪ 本人私有在途区
            wrapper.and(privateOrPublic -> privateOrPublic
                    .notIn(Supplier::getStatus, STATUS_DRAFT, STATUS_RETURNED)
                    .or()
                    .eq(Supplier::getCreatedBy, me.getId()));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(Supplier::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(nameOrTaxNo -> nameOrTaxNo
                    .like(Supplier::getName, keyword)
                    .or()
                    .like(Supplier::getTaxNo, keyword));
        }
        wrapper.orderByDesc(Supplier::getCreatedAt);

        IPage<Supplier> result = supplierMapper.selectPage(new Page<>(safePage, safePageSize), wrapper);
        List<Supplier> records = result.getRecords();
        Map<Long, String> creatorNames = creatorNames(records);
        List<SupplierResponse> list = records.stream()
                .map(record -> toResponse(record, creatorName(creatorNames, record.getCreatedBy())))
                .collect(Collectors.toList());
        return new PageResult<>(list, result.getTotal(), safePage, safePageSize);
    }

    // ==================== §1.4 详情 ====================

    @Override
    public SupplierResponse get(Long id) {
        UserPrincipal me = currentUserProvider.require();
        Supplier supplier = requireSupplier(id);
        if (me.getRole() == Role.STAFF && isPrivateInFlight(supplier.getStatus())) {
            // STAFF 命中他人私有在途区 → requireSelf 抛 40302；AUDITOR/ADMIN 全量可见（裁决 2026-10-14 晚）
            roleGuard.requireSelf(supplier.getCreatedBy());
        }
        return toResponse(supplier, creatorName(supplier.getCreatedBy()));
    }

    // ==================== §1.5 逻辑删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireSupplier(id);
        roleGuard.requireSelf(supplier.getCreatedBy());
        roleGuard.requireStatusAllowed(STATUS_DRAFT.equals(supplier.getStatus()), MSG_DELETABLE_STATUS);
        // @TableLogic：UPDATE deleted=1，非物理删除；生成列 tax_no_active 随之置 NULL 释放税号位
        supplierMapper.deleteById(id);
    }

    // ==================== §2.1 提交准入审核 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierResponse submit(Long id, SupplierSubmitRequest request) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireSupplier(id);
        // 判序（与 SupplierSubmitAuditIntegrationTest 逐条对齐）：角色 → 存在性 → 本人 → 状态
        // → 营业执照硬门槛 → 乐观锁。前四道都是"这个请求根本没有资格进入迁移"，
        // 硬门槛是"业务前置条件不满足"，乐观锁必须最后判：它只解决并发脏写，不该抢业务报错的位。
        roleGuard.requireSelf(supplier.getCreatedBy());
        roleGuard.requireStatusAllowed(isSubmittable(supplier.getStatus()), MSG_STATUS_NOT_ALLOWED);
        requireUnexpiredBusinessLicense(id);
        requireVersionPresent(request.getVersion());

        String oldStatus = supplier.getStatus();
        LocalDateTime now = LocalDateTime.now();
        supplier.setStatus(STATUS_PENDING_REVIEW);
        supplier.setUpdatedAt(now);
        // 乐观锁：WHERE version = 入参版本，@Version 拦截器负责自增；影响 0 行即并发脏写
        supplier.setVersion(request.getVersion());
        if (supplierMapper.updateById(supplier) == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }

        UserPrincipal me = currentUserProvider.require();
        auditLogService.record(EntityType.SUPPLIER, supplier.getId(), supplier.getId(), me,
                AuditAction.SUBMIT.name(), oldStatus, STATUS_PENDING_REVIEW, AuditResult.SUCCESS, null);
        return toResponse(supplier, creatorName(supplier.getCreatedBy()));
    }

    // ==================== §2.2 准入审核决策（连带批复资质） ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SupplierResponse audit(Long id, SupplierAuditDecisionRequest request) {
        roleGuard.requireRole(Role.AUDITOR);
        Supplier supplier = requireSupplier(id);
        // 只允许 PENDING_REVIEW：DRAFT 直审 / NORMAL 再审都属非法状态审核（acceptance §1.1）
        roleGuard.requireStatusAllowed(STATUS_PENDING_REVIEW.equals(supplier.getStatus()),
                MSG_STATUS_NOT_ALLOWED);

        boolean approve = isApprove(request.getDecision());
        String comment = trimToNull(request.getComment());
        if (!approve) {
            // 契约 §2.2 L366：驳回必须给理由，且本步先于任何写操作——失败要"状态与资质一律不变"
            requireRejectComment(comment);
        }
        requireVersionPresent(request.getVersion());

        UserPrincipal me = currentUserProvider.require();
        String oldStatus = supplier.getStatus();
        String newStatus = approve ? STATUS_NORMAL : STATUS_RETURNED;
        LocalDateTime now = LocalDateTime.now();
        supplier.setStatus(newStatus);
        supplier.setUpdatedAt(now);
        supplier.setVersion(request.getVersion());
        if (supplierMapper.updateById(supplier) == 0) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }

        // 同事务连带批复（裁决 20261012 方案 A）：准入件不单独走 §2.3，随本接口整体批复
        cascadePendingQualifications(supplier.getId(), approve, me.getId(), comment, now);

        auditLogService.record(EntityType.SUPPLIER, supplier.getId(), supplier.getId(), me,
                (approve ? AuditAction.AUDIT_APPROVE : AuditAction.AUDIT_REJECT).name(),
                oldStatus, newStatus, AuditResult.SUCCESS, comment);
        return toResponse(supplier, creatorName(supplier.getCreatedBy()));
    }

    // ==================== 内部方法 ====================

    private Supplier requireSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            // 已逻辑删除的行由 @TableLogic 自动过滤，同样落此分支 → 40401
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    /** §2.1 允许的起始态：DRAFT（首次送审）与 RETURNED（驳回后重提环），契约 §2.1 L262 */
    private boolean isSubmittable(String status) {
        return STATUS_DRAFT.equals(status) || STATUS_RETURNED.equals(status);
    }

    /**
     * §2.1 前置硬门槛（契约 L263）：名下至少一份<b>未过期</b> BUSINESS_LICENSE 资质，
     * 判定条件 {@code expiry_date >= 今天}（当天到期仍算未过期，边界由集成用例锁定）。
     * 只认营业执照类型：OTHER 即便未过期也不构成准入门槛；{@code @TableLogic} 已让逻辑删除件隐身；
     * expiry_date 为 NULL 的行在 SQL 比较中不满足 {@code >=}，属契约字面口径（§1.7 入参层本就强制必填）。
     */
    private void requireUnexpiredBusinessLicense(Long supplierId) {
        long valid = Optional.ofNullable(qualificationMapper.selectCount(
                new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplierId)
                        .eq(SupplierQualification::getDocType, DOC_TYPE_BUSINESS_LICENSE)
                        .ge(SupplierQualification::getExpiryDate, LocalDate.now()))).orElse(0L);
        if (valid == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_LICENSE_REQUIRED);
        }
    }

    /**
     * version 缺失 → 40901（契约 §2.1 L312「version 缺失或不匹配」+ api-spec §2 接口表
     * 「不回传或版本不匹配 → 40901」）。刻意不用 40001：缺失是并发语义（客户端没拿到版本号），
     * 且入参层 @Min 已把 0/负数这类<b>格式</b>非法折成 40001，二者必须分层。
     */
    private void requireVersionPresent(Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
    }

    /** 决策码值第二道闸：入参层 @Pattern 已拦一次，Service 仍不得把未知值默认当成 REJECT */
    private boolean isApprove(String decision) {
        if (DECISION_APPROVE.equals(decision)) {
            return true;
        }
        if (DECISION_REJECT.equals(decision)) {
            return false;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                SupplierValidationPatterns.PARAM_INVALID_MSG);
    }

    /** 纯空白等同未填写（契约"comment 为空"含前端传空格串的实际形态） */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireRejectComment(String comment) {
        if (comment == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_REJECT_COMMENT_REQUIRED);
        }
    }

    /**
     * §2.2 连带批复（同事务，契约 L324）：本供应商<b>全部</b> PENDING 资质批量置
     * APPROVED（APPROVE）或 REJECTED（REJECT），并回写 reviewed_by/reviewed_at/review_comment。
     *
     * APPROVE 分支先做"同类型旧 APPROVED 件退位"：{@code @TableLogic} 下 delete(wrapper) 编译为
     * {@code UPDATE deleted=1}（历史保留、不物理删、status 不改写——backend-interface-design L210 口径），
     * 必须早于本件更新，否则生成列 approved_key 会撞 uk_supplier_approved_doc 唯一索引。
     * REJECT 分支绝不退位：旧件保持生效位（api-spec §2"资质生效路径"L281，避免批复真空）。
     * 空集（纯表单准入、一张资质都没传）直接返回，不得报错。
     */
    private void cascadePendingQualifications(Long supplierId, boolean approve, Long reviewerId,
                                              String comment, LocalDateTime now) {
        List<SupplierQualification> pending = qualificationMapper.selectList(
                new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplierId)
                        .eq(SupplierQualification::getStatus, QUAL_PENDING));
        if (pending.isEmpty()) {
            return;
        }
        for (SupplierQualification qualification : pending) {
            if (approve) {
                qualificationMapper.delete(new LambdaQueryWrapper<SupplierQualification>()
                        .eq(SupplierQualification::getSupplierId, supplierId)
                        .eq(SupplierQualification::getDocType, qualification.getDocType())
                        .eq(SupplierQualification::getStatus, QUAL_APPROVED));
            }
            qualification.setStatus(approve ? QUAL_APPROVED : QUAL_REJECTED);
            qualification.setReviewedBy(reviewerId);
            qualification.setReviewedAt(now);
            qualification.setReviewComment(comment);
            qualification.setUpdatedAt(now);
            try {
                qualificationMapper.updateById(qualification);
            } catch (DuplicateKeyException ex) {
                // 生效位并发竞态（同类型两条同时置 APPROVED）由 DB 唯一索引兜底折 40902，
                // 与 §2.3 同口径；绝不冒 50001、绝不泄露 SQL
                throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_APPROVED_RACE);
            }
        }
    }

    private boolean isEditable(String status) {
        return STATUS_DRAFT.equals(status) || STATUS_RETURNED.equals(status);
    }

    private boolean isPrivateInFlight(String status) {
        return STATUS_DRAFT.equals(status) || STATUS_RETURNED.equals(status);
    }

    private int clampPageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private void insertTranslatingDuplicateKey(Supplier supplier) {
        try {
            supplierMapper.insert(supplier);
        } catch (DuplicateKeyException ex) {
            // 预检通过后的并发窗口由 DB 唯一索引兜底，同样折 40902（验收 §1.6）
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_TAX_NO_CONFLICT);
        }
    }

    /** 税号应用层预检：excludeId 非空时排除自己，保证"改成自己当前税号"幂等成功 */
    private void requireTaxNoAvailable(String taxNo, Long excludeId) {
        LambdaQueryWrapper<Supplier> wrapper = new LambdaQueryWrapper<Supplier>()
                .eq(Supplier::getTaxNo, taxNo);
        if (excludeId != null) {
            wrapper.ne(Supplier::getId, excludeId);
        }
        long conflicts = Optional.ofNullable(supplierMapper.selectCount(wrapper)).orElse(0L);
        if (conflicts > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_TAX_NO_CONFLICT);
        }
    }

    private LocalDate mergedDate(String submitted, LocalDate currentValue) {
        return submitted == null ? currentValue : requireDate(submitted);
    }

    /** 语法非法（含 2026-02-30 这类语义非法日期）一律折 40001 契约文案，不得冒 50001 */
    private LocalDate requireDate(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, SupplierValidationPatterns.DATE_MSG);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, SupplierValidationPatterns.DATE_MSG);
        }
    }

    /** 到期日必须晚于生效日；任一为空（存量 NULL 行，裁决 20261011 允许）时不做臆测校验 */
    private void requireDateOrder(LocalDate effectiveDate, LocalDate expiryDate) {
        if (effectiveDate == null || expiryDate == null) {
            return;
        }
        if (!expiryDate.isAfter(effectiveDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, SupplierValidationPatterns.DATE_MSG);
        }
    }

    private void applyIfPresent(SupplierUpdateRequest request, Supplier supplier) {
        if (request.getName() != null) {
            supplier.setName(request.getName());
        }
        if (request.getTaxNo() != null) {
            supplier.setTaxNo(request.getTaxNo());
        }
        if (request.getType() != null) {
            supplier.setType(request.getType());
        }
        if (request.getContactName() != null) {
            supplier.setContactName(request.getContactName());
        }
        if (request.getContactPhone() != null) {
            supplier.setContactPhone(request.getContactPhone());
        }
        if (request.getContactEmail() != null) {
            supplier.setContactEmail(request.getContactEmail());
        }
        if (request.getAddress() != null) {
            supplier.setAddress(request.getAddress());
        }
        if (request.getRemark() != null) {
            supplier.setRemark(request.getRemark());
        }
    }

    /** 列表页创建人姓名批量反查，避免 N+1；空页不查库 */
    private Map<Long, String> creatorNames(List<Supplier> records) {
        if (records.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> creatorIds = records.stream()
                .map(Supplier::getCreatedBy)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        if (creatorIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(creatorIds).stream()
                // 按主键批量反查不会返回重复 ID，无需 merge 函数
                .collect(Collectors.toMap(User::getId, this::displayName));
    }

    private String creatorName(Long userId) {
        if (userId == null) {
            return "";
        }
        return displayName(userMapper.selectById(userId));
    }

    private String creatorName(Map<Long, String> names, Long userId) {
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

    private String format(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMAT);
    }

    private String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_FORMAT);
    }

    /** 派生三枚字段显式留空（归二期），故意为 null 而非 0/false，避免前端把"未计算"读成"无风险" */
    private SupplierResponse toResponse(Supplier supplier, String createdByName) {
        return SupplierResponse.builder()
                .id(supplier.getId())
                .name(supplier.getName())
                .taxNo(supplier.getTaxNo())
                .type(supplier.getType())
                .contactName(supplier.getContactName())
                .contactPhone(supplier.getContactPhone())
                .contactEmail(supplier.getContactEmail())
                .address(supplier.getAddress())
                .remark(supplier.getRemark())
                .businessLicenseUrl(null)
                .effectiveDate(format(supplier.getEffectiveDate()))
                .expiryDate(format(supplier.getExpiryDate()))
                .status(supplier.getStatus())
                .version(supplier.getVersion())
                .createdBy(supplier.getCreatedBy())
                .createdByName(createdByName)
                .createdAt(format(supplier.getCreatedAt()))
                .updatedAt(format(supplier.getUpdatedAt()))
                .latestPerformanceGrade(null)
                .riskWarning(null)
                .build();
    }
}
