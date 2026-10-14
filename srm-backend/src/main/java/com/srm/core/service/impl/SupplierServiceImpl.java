package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.PageResult;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.supplier.SupplierCreateRequest;
import com.srm.core.dto.supplier.SupplierResponse;
import com.srm.core.dto.supplier.SupplierUpdateRequest;
import com.srm.core.dto.supplier.SupplierValidationPatterns;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.User;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.UserMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
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
 * - <b>本批不落 AuditLog</b>（同一裁决）：§1.1–§1.5 契约未要求留痕，AuditAction 维持 11 码不变。
 */
@Service
public class SupplierServiceImpl implements SupplierService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_RETURNED = "RETURNED";

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

    private final SupplierMapper supplierMapper;
    private final UserMapper userMapper;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;

    public SupplierServiceImpl(SupplierMapper supplierMapper,
                               UserMapper userMapper,
                               RoleGuard roleGuard,
                               CurrentUserProvider currentUserProvider) {
        this.supplierMapper = supplierMapper;
        this.userMapper = userMapper;
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

    // ==================== 内部方法 ====================

    private Supplier requireSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            // 已逻辑删除的行由 @TableLogic 自动过滤，同样落此分支 → 40401
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
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
