package com.srm.core.dto.lifecycle;

/**
 * 生命周期申请入参校验的<b>唯一常量源</b>（契约 §2.4/§2.6 请求体字段表，docs/api-spec.md §3）。
 *
 * 与 SupplierValidationPatterns / QualificationValidationPatterns 同一铁律：正则与固定中文
 * message 只在本文件出现一次，Bean Validation 与前端表单必须同源；跨域复用的码值一律
 * 引用既有常量，不另写一份（历史上"同一规则两套文案"就是漂移起点）。
 */
public final class LifecycleValidationPatterns {

    /** 申请类型三码（api-spec §3 LifecycleRequestType），中文/小写不接受 */
    public static final String TYPE_REGEX = "^(SUSPEND|RESUME|ELIMINATE)$";

    /**
     * §2.6 决策二值与 §2.2 供应商审核、§2.3 资质审批完全同码值（APPROVE/REJECT），
     * 属全局码值，直接引用供应商域单一事实源，禁止三个域各写一套正则。
     */
    public static final String DECISION_REGEX =
            com.srm.core.dto.supplier.SupplierValidationPatterns.DECISION_REGEX;

    /**
     * 模块二 40001 泛义文案：契约 §2.4 L481 与 §2.6 L552 的 Message 列逐字均为「参数校验失败」
     * （type/decision 非法码值、reason 缺失这类格式失败共用本条）。
     */
    public static final String PARAM_INVALID_MSG =
            com.srm.core.dto.supplier.SupplierValidationPatterns.PARAM_INVALID_MSG;

    private LifecycleValidationPatterns() {
    }
}
