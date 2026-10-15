package com.srm.core.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.srm.core.audit.AuditAction;
import com.srm.core.audit.AuditResult;
import com.srm.core.audit.EntityType;
import com.srm.core.common.BusinessException;
import com.srm.core.common.ErrorCode;
import com.srm.core.common.enums.Role;
import com.srm.core.dto.qualification.QualificationCreateRequest;
import com.srm.core.dto.qualification.QualificationResponse;
import com.srm.core.dto.qualification.QualificationReviewRequest;
import com.srm.core.dto.qualification.QualificationValidationPatterns;
import com.srm.core.entity.Supplier;
import com.srm.core.entity.SupplierQualification;
import com.srm.core.mapper.SupplierMapper;
import com.srm.core.mapper.SupplierQualificationMapper;
import com.srm.core.security.CurrentUserProvider;
import com.srm.core.security.RoleGuard;
import com.srm.core.security.UserPrincipal;
import com.srm.core.service.AuditLogService;
import com.srm.core.service.QualificationService;
import org.springframework.dao.DuplicateKeyException;
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
 * 同类型在途顶替（裁决 20261015，契约 §1.7 业务约束新增行）：本接口事务内，全部校验通过后、插入新行前，
 * 先逻辑删除该供应商名下同 {@code doc_type} 的旧 {@code PENDING} 行——{@code @TableLogic} 下
 * {@code BaseMapper.delete(wrapper)} 编译为 {@code UPDATE deleted=1}（物理行保留可查，非物理 DELETE），
 * 即"同类型待审资质仅一条，新提交顶替旧件"。边界：仅 {@code PENDING} 被顶，{@code APPROVED}/{@code REJECTED}
 * 不受影响（delete 条件三 eq 精确划界）；顶替与 insert 同一 {@code @Transactional}，任何校验失败先于顶替
 * 抛出，旧在途件分毫无损；被顶旧件经 {@code @TableLogic} 自动从 §1.8 隐身，前端不提供独立删除入口。
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
 * 本批 §1.7/§1.8 不落 AuditLog、不新增 AuditAction 码（同一裁决）；§2.3 则<b>必须</b>留痕，
 * 且复用既有 REVIEW_APPROVE/REVIEW_REJECT 两码（方案甲裁决 20261012，零新增码）。
 *
 * 模块二 §2.3 存续期资质单独审批（本类新增 review）设计要点：
 * - <b>范围闸门</b>（裁决 20261012）：只有「供应商 NORMAL + 资质 PENDING」的存续期补传件准入本接口；
 *   准入期件必须随 §2.2 audit 连带批复，此处双重 40302 封堵，两条生效路径互不打架。
 * - <b>入参 action ≠ 审计 action</b>（契约 §2.3 L384 术语区分铁律）：入参二值 APPROVE/REJECT 只作分支判定，
 *   落审计一律经 {@code REVIEW_} 前缀映射，杜绝裸 APPROVE 进 audit_log。
 * - <b>旧件退位只在 APPROVE 分支</b>：REJECT 时旧 APPROVED 件必须保持生效位（api-spec L281「批复真空」约束），
 *   因此退位与生效位竞态兜底（uk_supplier_approved_doc → 40902）都只可能出现在 APPROVE 路径。
 * - <b>本接口不改供应商状态</b>：资质批复与供应商六态完全解耦（NORMAL 保持 NORMAL），
 *   supplier.status 的唯一写入口仍是 §2.1/§2.2 与 §2.6 生命周期终审。
 * - <b>乐观锁</b>：资质 version 走 {@code @Version}，影响 0 行即 40901；缺失同码（与 §2.1 候选 B 同口径，
 *   契约 §0 L36 文案权威注记：实回「数据已被修改，请刷新后重试」）。
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
    private static final String QUAL_REJECTED = "REJECTED";

    /** §2.3 入参 action 二值（与 §2.2 decision 同码值，值域正则见 QualificationValidationPatterns.ACTION_REGEX） */
    private static final String ACTION_APPROVE = "APPROVE";
    private static final String ACTION_REJECT = "REJECT";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    private static final String MSG_SUPPLIER_NOT_FOUND = "供应商不存在";
    /** 契约 §1.7 错误码矩阵无 Message 列，40302 取契约 §2.1 已给的全局固定文案 */
    private static final String MSG_STATUS_NOT_ALLOWED = "当前状态不允许该操作";
    /**
     * §2.3 资质记录不存在的 40401 文案：契约 §2.3 L427 的矩阵原文即泛义「资源不存在」，
     * 且契约 §0 L36 权威注记只把「供应商不存在」登记为<b>供应商域</b>实回文案（资质域无单列文案），
     * 故本域按矩阵字面取泛义，不自拟新文案；供应商行缺失（孤儿资质/已逻辑删除）仍回供应商域既有文案。
     */
    private static final String MSG_QUALIFICATION_NOT_FOUND = "资源不存在";
    /** 契约 §0 L36 文案权威注记：40901 实回「数据已被修改，请刷新后重试」（无“他人”二字，与模块一同源） */
    private static final String MSG_VERSION_CONFLICT = "数据已被修改，请刷新后重试";
    /** 契约 §2.3 L425 原文文案 */
    private static final String MSG_REJECT_COMMENT_REQUIRED = QualificationValidationPatterns.REJECT_COMMENT_MSG;
    /** 契约 §2.3 L429 的 40902 文案（生效位并发竞态，DB 唯一索引兜底） */
    private static final String MSG_APPROVED_RACE = "业务冲突";
    /** 40001 泛义文案（action 值域第二道闸；入参层 @Pattern 已拦一次，Service 不得把未知值默认当 REJECT） */
    private static final String MSG_PARAM_INVALID = QualificationValidationPatterns.PARAM_INVALID_MSG;

    private final SupplierMapper supplierMapper;
    private final SupplierQualificationMapper qualificationMapper;
    private final RoleGuard roleGuard;
    private final CurrentUserProvider currentUserProvider;
    private final AuditLogService auditLogService;

    public QualificationServiceImpl(SupplierMapper supplierMapper,
                                    SupplierQualificationMapper qualificationMapper,
                                    RoleGuard roleGuard,
                                    CurrentUserProvider currentUserProvider,
                                    AuditLogService auditLogService) {
        this.supplierMapper = supplierMapper;
        this.qualificationMapper = qualificationMapper;
        this.roleGuard = roleGuard;
        this.currentUserProvider = currentUserProvider;
        this.auditLogService = auditLogService;
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

        // 同类型在途顶替（裁决 20261015）：必须位于全部校验之后、insert 之前——判序由单测+集成三层锁定；
        // @TableLogic 使本步为 UPDATE deleted=1（H2 集成层绕过逻辑删除直查物理列实证），且与下方 insert
        // 同处本方法 @Transactional，异常路径整体回滚，校验失败绝不顶掉旧件
        qualificationMapper.delete(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, supplier.getId())
                .eq(SupplierQualification::getDocType, request.getDocType())
                .eq(SupplierQualification::getStatus, QUAL_PENDING));

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

    // ==================== §2.3 存续期资质单独审批 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualificationResponse review(Long id, QualificationReviewRequest request) {
        roleGuard.requireRole(Role.AUDITOR);
        SupplierQualification qualification = requireQualification(id);
        Supplier supplier = requireSupplier(qualification.getSupplierId());
        // 两道 40302 状态闸门（契约 §2.3 L382）：先供应商后资质，任一不符即拒——
        // 供应商非 NORMAL = 准入期件（须走 §2.2 连带），资质非 PENDING = 已批/已驳不可重复审。
        roleGuard.requireStatusAllowed(SUP_NORMAL.equals(supplier.getStatus()), MSG_STATUS_NOT_ALLOWED);
        roleGuard.requireStatusAllowed(QUAL_PENDING.equals(qualification.getStatus()), MSG_STATUS_NOT_ALLOWED);

        boolean approve = isApprove(request.getAction());
        String comment = trimToNull(request.getReviewComment());
        if (!approve) {
            // 必须先于任何写操作：驳回不给理由 → 状态与审计都不得动
            requireRejectComment(comment);
        }
        requireVersionPresent(request.getVersion());

        UserPrincipal me = currentUserProvider.require();
        LocalDateTime now = LocalDateTime.now();
        if (approve) {
            // 生效位让路：同 supplier + 同 doc_type 的旧 APPROVED 件先逻辑退位（@TableLogic → UPDATE deleted=1，
            // 历史保留不物理删）。必须早于本件更新，否则两件同时占 uk_supplier_approved_doc 唯一位。
            retirePreviousApproved(qualification);
        }
        qualification.setStatus(approve ? QUAL_APPROVED : QUAL_REJECTED);
        qualification.setReviewedBy(me.getId());
        qualification.setReviewedAt(now);
        qualification.setReviewComment(comment);
        qualification.setUpdatedAt(now);
        qualification.setVersion(request.getVersion());
        try {
            if (qualificationMapper.updateById(qualification) == 0) {
                throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
            }
        } catch (DuplicateKeyException ex) {
            // 应用层退位后仍有并发窗口（两个 AUDITOR 同时批不同件），由 DB 唯一索引兜底折 40902
            throw new BusinessException(ErrorCode.DUPLICATE_IN_PROGRESS, MSG_APPROVED_RACE);
        }

        // 审计只写 SUPPLIER_QUALIFICATION 域一笔，动作码一律带 REVIEW_ 前缀（契约 §2.3 L384 术语区分）；
        // old/new_status 承载资质三态迁移，supplier_id 冗余列供 §0 行级隔离单条件覆盖
        auditLogService.record(EntityType.SUPPLIER_QUALIFICATION, qualification.getId(),
                qualification.getSupplierId(), me,
                (approve ? AuditAction.REVIEW_APPROVE : AuditAction.REVIEW_REJECT).name(),
                QUAL_PENDING, qualification.getStatus(), AuditResult.SUCCESS, comment);
        return toResponse(qualification);
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

    private SupplierQualification requireQualification(Long id) {
        SupplierQualification qualification = qualificationMapper.selectById(id);
        if (qualification == null) {
            // 已逻辑删除（含被 §1.7 同类型在途顶替）的资质行由 @TableLogic 自动隐身，同样落此分支 → 40401
            throw new BusinessException(ErrorCode.NOT_FOUND, MSG_QUALIFICATION_NOT_FOUND);
        }
        return qualification;
    }

    /** 入参 action 第二道闸：未知码值绝不默认当成 REJECT（REJECT 有必填意见等副作用，猜错代价不对称） */
    private boolean isApprove(String action) {
        if (ACTION_APPROVE.equals(action)) {
            return true;
        }
        if (ACTION_REJECT.equals(action)) {
            return false;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_PARAM_INVALID);
    }

    /** 纯空白等同未填写（与 §2.2 comment 同一口径） */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private void requireRejectComment(String comment) {
        if (comment == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, MSG_REJECT_COMMENT_REQUIRED);
        }
    }

    /** version 缺失 → 40901（并发语义而非格式语义；0/负数已由入参层 @Min 折 40001） */
    private void requireVersionPresent(Integer version) {
        if (version == null) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, MSG_VERSION_CONFLICT);
        }
    }

    /**
     * 同类型旧 APPROVED 件逻辑退位（api-spec §2「审核通过联动」L282）。
     * 条件三 eq + ne 精确划界：只动本供应商、本证照类型、已批且<b>非本件</b>的行；
     * PENDING/REJECTED 件分毫不损。ne(id) 在当前判序下实为不可达的防御位（本件此刻必为 PENDING），
     * 留着是为了"若将来闸门放宽到允许重审批 APPROVED 件，也不会把新件自己退位"。
     */
    private void retirePreviousApproved(SupplierQualification qualification) {
        qualificationMapper.delete(new LambdaQueryWrapper<SupplierQualification>()
                .eq(SupplierQualification::getSupplierId, qualification.getSupplierId())
                .eq(SupplierQualification::getDocType, qualification.getDocType())
                .eq(SupplierQualification::getStatus, QUAL_APPROVED)
                .ne(SupplierQualification::getId, qualification.getId()));
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
