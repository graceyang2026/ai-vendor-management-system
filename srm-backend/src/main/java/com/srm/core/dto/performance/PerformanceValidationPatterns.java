package com.srm.core.dto.performance;

/**
 * 模块三绩效评价入参校验的<b>唯一常量源</b>（契约 §3.1–§3.3 错误码矩阵 + 附录 A4.1，
 * docs/api-spec.md §4 / §8）。
 *
 * 与 SupplierValidationPatterns / QualificationValidationPatterns / LifecycleValidationPatterns
 * 同一铁律：固定中文 message 与正则只在本类出现一次，Bean Validation、适配器校验与 Service
 * 二道闸全部引用本处常量，绝不允许"同一规则两套文案"的漂移。
 *
 * 文案取用规则（逐字对照契约矩阵，不自拟）：
 * <ul>
 *   <li>{@link #FACT_INVALID_MSG}：契约 §3.1/§3.2 矩阵里 40001 的 Message 列原文
 *       {@code 事实数据校验失败}，覆盖"缺 fact_record、事实负值、子集大于全集、周期区间非法"
 *       四类——故<b>周期缺失/格式非法/起止倒挂也归本条</b>，不借用供应商域的「日期格式不合法…」，
 *       否则同一次 40001 会在两个域之间给出两种文案；</li>
 *   <li>{@link #PARAM_INVALID_MSG}：泛义「参数校验失败」，用于契约未单列文案的<b>枚举码值格式失败</b>
 *       （如 {@code mode=external}、supplier_id 必填缺失），与模块二 §2.4/§2.6 同一分层口径；</li>
 *   <li>{@link #REJECT_COMMENT_MSG}：契约 §3.3 矩阵原文 {@code 驳回时必须填写意见}，
 *       引用供应商域单一事实源（同码值同文案，跨域复用）。</li>
 * </ul>
 */
public final class PerformanceValidationPatterns {

    /** 取数模式两码（api-spec §4.2 mode: 'manual' | 'mock'），小写、中文、external 一律不接受 */
    public static final String MODE_REGEX = "^(manual|mock)$";

    /** 绩效三态过滤器码值（契约 §3.4，与供应商/资质/申请单四套枚举互不复用） */
    public static final String STATUS_REGEX = "^(PENDING_REVIEW|APPROVED|RETURNED)$";

    /** §3.3 复核决策二值：与 §2.2 准入审核、§2.6 生命周期终审同码值，引用全局单一事实源 */
    public static final String DECISION_REGEX =
            com.srm.core.dto.supplier.SupplierValidationPatterns.DECISION_REGEX;

    /** 契约 §3.1/§3.2 矩阵 40001 原文文案（事实与周期两类校验共用） */
    public static final String FACT_INVALID_MSG = "事实数据校验失败";

    /** 契约 §3.3 矩阵 40001 原文文案（REJECT 缺 comment） */
    public static final String REJECT_COMMENT_MSG =
            com.srm.core.dto.supplier.SupplierValidationPatterns.REJECT_COMMENT_MSG;

    /** 泛义 40001 文案（mode 非法码值、必填标量缺失这类格式失败；引用供应商域单一事实源） */
    public static final String PARAM_INVALID_MSG =
            com.srm.core.dto.supplier.SupplierValidationPatterns.PARAM_INVALID_MSG;

    /** 契约 §0 文案权威注记：40901 实回「数据已被修改，请刷新后重试」（无"他人"二字） */
    public static final String VERSION_CONFLICT_MSG = "数据已被修改，请刷新后重试";

    /** 契约 §3.1/§3.2/§3.3/§3.5 的 40302 泛义文案（状态闸与归属闸共用） */
    public static final String STATUS_NOT_ALLOWED_MSG = "当前状态不允许该操作";

    /** 契约 §3.1 矩阵 40902 原文文案（同周期在途唯一） */
    public static final String IN_FLIGHT_EXISTS_MSG = "该供应商同期已有在途评价单";

    /** 绩效域 40401 子场景文案（用户 2026-10-15 16:42 拍板 B5：被评供应商不存在） */
    public static final String SUPPLIER_NOT_FOUND_MSG = "供应商不存在";

    /** 绩效域 40401 子场景文案（用户 2026-10-15 16:42 拍板 B5：评价单不存在，§3.2/§3.3/§3.5） */
    public static final String EVALUATION_NOT_FOUND_MSG = "评价记录不存在";

    private PerformanceValidationPatterns() {
    }
}
