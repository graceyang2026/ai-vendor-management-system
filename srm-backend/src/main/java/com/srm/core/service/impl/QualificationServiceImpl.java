package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationValidationPatterns;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.QualificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模块一 §1.7 上传资质 / §1.8 资质列表实现（契约 §1.7、§1.8、附录 A2）。
 *
 * 判序（§1.7，逐条锁定于 QualificationServiceImplTest）：
 * 角色 40301 → 供应商存在性 40401 → 归属 requireSelf 40302 → 状态门槛 40302 → 日期语义 40001。
 * "先归属后状态"是刻意的：他人档案即便状态合法也不该走到状态闸门，更不该泄露"该状态是否允许上传"。
 * 新记录一律 status=PENDING、version=1（裁决 20261012：上传不等于生效，生效只能由 §2.2/§2.3 批复）。
 *
 * §1.8 可见性（契约"默认 APPROVED+PENDING、REJECTED 仅建档本人追溯"× 裁决 2026-10-14 晚
 * "AUDITOR 不受 §0 规则 A 约束"的组合口径）：
 * - 规则 A 私有在途区闸门只卡 STAFF（AUDITOR/ADMIN 全量，含他人 DRAFT/RETURNED）；
 * - REJECTED 行排除条件<b>下推 SQL 且只对"非建档本人的 STAFF"追加</b>：本表无 created_by 列，
 *   "本人"经归属链指向 supplier.created_by；AUDITOR/ADMIN 不排（REJECTED 行本就是批复主体产出的，
 *   对其隐身不成立）。绝不依赖前端过滤（§0 后端强制铁律）。
 *
 * expired 是<b>同表</b>派生（expiry_date &lt; 今天），本批即实现；归二期的只是 SupplierResponse
 * 的三枚跨表派生字段（用户口径 2026-10-14）。审核痕迹三列本批只读不写（批复留痕归模块二）。
 * 本批两接口不落 AuditLog，不新增 AuditAction 码（同一裁决）。
 */
@Service
public class QualificationServiceImpl implements QualificationService {

    /** 供应商状态：§1.7 允许集 DRAFT / RETURNED / NORMAL（裁决 20261011 解除"准入后资质无法维护"死锁） */
    private static final String SUP_DRAFT = "DRAFT";
    private static final String SUP_RETURNED = "RETURNED";
    private static final String SUP_NORMAL = "NORMAL";

    /** 资质三态（裁决 20261012，勿与供应商/绩效的 PENDING_REVIEW 混用） */
    private static final String QUAL_PENDING = "PENDING";
    private static final String QUAL_APPROVED = "APPROVED";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    /** 契约 §1.7 错误码矩阵无 Message 列，40302 取契约 §2.1 已给的全局固定文案 */
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";

    private final SupplierMapper supplierMapper;
    private final SupplierQualificationMapper qualificationMapper;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;

    public QualificationServiceImpl(SupplierMapper supplierMapper,
                                    SupplierQualificationMapper qualificationMapper,
                                    RoleGuard roleGuard,
                                    CurrentUserProvider currentUserProvider) {
        this.supplierMapper = supplierMapper;
        this.qualificationMapper = qualificationMapper;
        this.roleGuard = roleGuard;
        this.currentUserProvider = currentUserProvider;
    }

    // ==================== §1.7 上传资质 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualificationResponse create(Long supplierId, QualificationCreateRequest request) {
        roleGuard.requireRole(Role.STAFF);
        Supplier supplier = requireSupplier(supplierId);
        // 资质行无 created_by，归属判定走供应商建档人；本步必须先于状态门槛（判序由单测锁定）
        roleGuard.requireSelf(supplier.getCreatedBy());
        roleGuard.requireStatusAllowed(isUploadable(supplier.getStatus()), MSG_STATUS_NOT_ALLOWED);

        LocalDate effectiveDate = requireDate(request.getEffectiveDate());
        LocalDate expiryDate = requireDate(request.getExpiryDate());
        requireDateOrder(effectiveDate, expiryDate);

        SupplierQualification qualification = new SupplierQualification();
        qualification.setSupplierId(supplier.getId());
        qualification.setDocType(request.getDocType());
        qualification.setFileUrl(request.getFileUrl());
        qualification.setEffectiveDate(effectiveDate);
        qualification.setExpiryDate(expiryDate);
        // 上传即待审：状态与版本号都由后端定死，不接受任何入参覆盖（契约 §1.7"新记录一律 PENDING"）
        qualification.setStatus(QUAL_PENDING);
        qualification.setVersion(1);
        LocalDateTime now = LocalDateTime.now();
        qualification.setCreatedAt(now);
        qualification.setUpdatedAt(now);
        qualification.setDeleted(0);
        // 批复三列（reviewed_by / reviewed_at / review_comment）本批不写，留模块二 §2.2/§2.3 回写

        qualificationMapper.insert(qualification);
        return toResponse(qualification);
    }

    // ==================== §1.8 资质列表 ====================

    @Override
    public List<QualificationResponse> list(Long supplierId) {
        UserPrincipal me = currentUserProvider.require();
        Supplier supplier = requireSupplier(supplierId);
        if (me.getRole() == Role.STAFF && isPrivateInFlight(supplier.getStatus())) {
            // §0 规则 A 后端强制（只卡 STAFF，裁决 2026-10-14 晚）：他人私有在途区不可读
            roleGuard.requireSelf(supplier.getCreatedBy());
        }

        LambdaQueryWrapper<SupplierQualification> wrapper = new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, supplierId);
        if (me.getRole() == Role.STAFF && !me.getId().equals(supplier.getCreatedBy())) {
            // REJECTED 仅建档本人可追溯：排除条件下推 SQL，而非查全量再在内存/前端过滤
            wrapper.in(SupplierQualification::getStatus, QUAL_APPROVED, QUAL_PENDING);
        }
        // 契约未规定排序，按主键升序保证分页与前端展示稳定
        wrapper.orderByAsc(SupplierQualification::getId);

        return qualificationMapper.selectList(wrapper).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ==================== 内部方法 ====================

    private Supplier requireSupplier(Long id) {
        Supplier supplier = supplierMapper.selectById(id);
        if (supplier == null) {
            // 已逻辑删除的供应商由 @TableLogic 自动隐身，同样落此分支 → 40401
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_SUPPLIER_NOT_FOUND);
        }
        return supplier;
    }

    private boolean isUploadable(String status) {
        return SUP_DRAFT.equals(status) || SUP_RETURNED.equals(status) || SUP_NORMAL.equals(status);
    }

    private boolean isPrivateInFlight(String status) {
        return SUP_DRAFT.equals(status) || SUP_RETURNED.equals(status);
    }

    /** 语法/语义非法日期一律折 40001 契约文案，不得冒 50001（入参层已校验，此为第二道闸） */
    private LocalDate requireDate(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, QualificationValidationPatterns.DATE_MSG);
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, QualificationValidationPatterns.DATE_MSG);
        }
    }

    /**
     * 到期日必须<b>晚于</b>生效日（契约 §1.7 用词"晚于"，相等亦非法）。
     * 两参数由 requireDate 保证非 null，故此处不再设空值分支（B1 的空值放行是给 Partial 合并
     * 与存量 NULL 行留的，§1.7 两端点都必填，留着只会成为测不到的死分支）。
     */
    private void requireDateOrder(LocalDate effectiveDate, LocalDate expiryDate) {
        if (!expiryDate.isAfter(effectiveDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, QualificationValidationPatterns.DATE_MSG);
        }
    }

    /** 同表派生位：expiry_date < 今天；NULL（长期有效）不算过期，且必须空安全 */
    private boolean isExpired(LocalDate expiryDate) {
        return expiryDate != null && expiryDate.isBefore(LocalDate.now());
    }

    private String format(LocalDate date) {
        return date == null ? null : date.format(DATE_FORMAT);
    }

    private String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATE_TIME_FORMAT);
    }

    /** 附录 A2 的 13 字段一个不能少；审核痕迹三列原样透传（§1.7 新件恒 null，存量批复行有值） */
    private QualificationResponse toResponse(SupplierQualification q) {
        return QualificationResponse.builder()
                .id(q.getId())
                .supplierId(q.getSupplierId())
                .docType(q.getDocType())
                .fileUrl(q.getFileUrl())
                .effectiveDate(format(q.getEffectiveDate()))
                .expiryDate(format(q.getExpiryDate()))
                .status(q.getStatus())
                .version(q.getVersion())
                .expired(isExpired(q.getExpiryDate()))
                .createdAt(format(q.getCreatedAt()))
                .reviewedBy(q.getReviewedBy())
                .reviewedAt(format(q.getReviewedAt()))
                .reviewComment(q.getReviewComment())
                .build();
    }
}
