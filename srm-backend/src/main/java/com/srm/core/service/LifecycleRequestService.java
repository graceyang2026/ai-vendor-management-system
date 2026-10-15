package com.srm.core.service;

import com.srm.core.common.PageResult;
import com.srm.core.dto.lifecycle.LifecycleDecisionRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestCreateRequest;
import com.srm.core.dto.lifecycle.LifecycleRequestResponse;
import com.srm.core.dto.lifecycle.LifecycleRequestTodoItem;

import java.util.List;

/**
 * 模块二 §2.4/§2.5/§2.6/§2.7：生命周期申请服务（契约 §2.4-§2.7 + 附录 A3，docs/api-spec.md §3）。
 *
 * 两步流程铁律：STAFF 只<b>提申请</b>（§2.4 新建 PENDING 单），AUDITOR 只<b>做决策</b>（§2.6 终审联动供应商状态），
 * 中间的 §2.5 是只读历史。除本服务与 §2.1/§2.2 准入流转外，没有任何代码路径可以改 supplier.status。
 */
public interface LifecycleRequestService {

    /**
     * §2.4 创建申请（STAFF）：类型-状态门槛 SUSPEND 仅 NORMAL / RESUME 仅 SUSPENDED /
     * ELIMINATE 仅 NORMAL 或 SUSPENDED，不符 {@code 40302}；供应商已 {@code ELIMINATED}（终态）
     * 之后一律禁建任何申请 {@code 40302}。同供应商同类型已有 PENDING 在途 → {@code 40902}
     * （应用层预检 + uk_supplier_active_lifecycle 兜底）。
     * <b>本接口绝不改供应商状态</b>，只新建 PENDING 申请单（version=1）并写 SUBMIT 审计。
     * 供应商不存在 {@code 40401}。
     */
    LifecycleRequestResponse create(Long supplierId, LifecycleRequestCreateRequest request);

    /**
     * §2.5 申请历史（ALL 登录角色，只读）：返回该供应商全部申请单（含三态），<b>按申请时间倒序</b>；
     * 每行携带 version（§2.6 入参唯一取值链）。不写审计、不改任何状态。供应商不存在 {@code 40401}。
     */
    List<LifecycleRequestResponse> listBySupplier(Long supplierId);

    /**
     * §2.6 终审决策（AUDITOR）：申请单必须 PENDING，否则 {@code 40302}；入参 {@code version} 为
     * <b>申请单</b>版本，缺失/不匹配 {@code 40901}。
     * APPROVE → 同事务联动供应商状态（SUSPEND→SUSPENDED / RESUME→NORMAL / ELIMINATE→ELIMINATED，
     * 供应商 version 同步自增；供应商状态已被并发改动而不再满足类型门槛 → {@code 40302}）；
     * REJECT → 只改申请单状态，供应商状态与 version 一律不动。
     * 两分支均回写 decided_by/decided_at/decision_comment，并写 DECISION_APPROVE/DECISION_REJECT 审计。
     * 申请单不存在 {@code 40401}。
     */
    LifecycleRequestResponse decide(Long id, LifecycleDecisionRequest request);

    /**
     * §2.7 跨供应商聚合查询（终审待办队列，AUDITOR 专属，裁决 20261015 增项；契约 §2.7 + api-spec §3）：
     * <ul>
     *   <li>非 AUDITOR {@code 40301}（与 @PreAuthorize 双闸，Service 二验兜底）；</li>
     *   <li>{@code status}（PENDING/APPROVED/REJECTED）与 {@code type}（SUSPEND/RESUME/ELIMINATE）
     *       均为可选过滤，缺省不过滤；传了非法枚举码值 {@code 40001}「参数校验失败」；</li>
     *   <li>{@code supplier_id} 可选收敛到单供应商（与 §2.5 行为重叠但本接口额外回
     *       supplier_name/supplier_status）；</li>
     *   <li>分页 page 默认 1、page_size 默认 20 上限 100，收敛口径与 §1.3 供应商列表同一范式；</li>
     *   <li>返回 {@code PageResult<LifecycleRequestTodoItem>}（附录 A3 全 13 字段 + 供应商侧冗余两列），
     *       按 applied_at 倒序（同刻并发以主键倒序保证行序稳定，与 §2.5 同法）；</li>
     *   <li><b>只读、不写审计、不改任何状态</b>；逻辑删除行（deleted=1）经 @TableLogic 自动隐身；</li>
     *   <li>供应商已逻辑删除/不存在：该行仍返回，supplier_name/supplier_status 回 null，不报错
     *       （AUDITOR 全量终审视图，不受 §0 规则 A 行级隔离限制）。</li>
     * </ul>
     */
    PageResult<LifecycleRequestTodoItem> listTodo(String status, String type, Long supplierId,
                                                  int page, int pageSize);
}
