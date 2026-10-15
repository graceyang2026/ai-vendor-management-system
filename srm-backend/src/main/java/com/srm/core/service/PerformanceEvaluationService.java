package com.srm.core.service;

import com.srm.core.common.PageResult;
import com.srm.core.dto.performance.PerformanceEvaluationCreateRequest;
import com.srm.core.dto.performance.PerformanceEvaluationResponse;
import com.srm.core.dto.performance.PerformanceEvaluationUpdateRequest;
import com.srm.core.dto.performance.PerformanceReviewDecisionRequest;

/**
 * 模块三·绩效评价服务（docs/api-spec.md §4.3 五接口，契约 §3.1–§3.5）。
 *
 * 本接口的<b>签名即防篡改边界</b>：写方法只接收"客观事实 + 版本号 + 复核决策"三形态入参
 * （{@link PerformanceEvaluationCreateRequest}/{@link PerformanceEvaluationUpdateRequest}/
 * {@link PerformanceReviewDecisionRequest}），三个 DTO 物理上都没有分数/评级字段，
 * 因此不存在任何"由调用方把分数写进库"的通道（rules.md §4.3、验收 §2.1）。
 */
public interface PerformanceEvaluationService {

    /**
     * §3.1 发起评价（STAFF 一步式）：按 mode 取数 → 唯一算分引擎算分 → 直接落 {@code PENDING_REVIEW}。
     * 同供应商同周期已有在途单（PENDING_REVIEW/RETURNED）→ 40902。
     */
    PerformanceEvaluationResponse create(PerformanceEvaluationCreateRequest request);

    /**
     * §3.2 退回重提（STAFF 仅本人、仅 RETURNED）：沿用同一条记录重新取数、重新算分、回到 PENDING_REVIEW，
     * 不新建；成功写审计 {@code SUBMIT}（用户 2026-10-15 16:42 拍板 B2）。
     */
    PerformanceEvaluationResponse update(Long id, PerformanceEvaluationUpdateRequest request);

    /**
     * §3.3 复核（AUDITOR 专属）：APPROVE 归档并同事务回写供应商 latest_performance_grade/risk_warning；
     * REJECT 退回 STAFF 重提。非法状态 40302，version 不匹配 40901。
     */
    PerformanceEvaluationResponse review(Long id, PerformanceReviewDecisionRequest request);

    /**
     * §3.4 分页列表：§0 行级隔离后端强制（STAFF 只看本人创建，AUDITOR/ADMIN 全量）。
     */
    PageResult<PerformanceEvaluationResponse> list(Long supplierId, String status, int page, int pageSize);

    /**
     * §3.5 详情（只读）：不存在 40401，STAFF 命中他人单据 40302。
     */
    PerformanceEvaluationResponse get(Long id);
}
