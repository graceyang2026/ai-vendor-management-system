package com.srm.core.dto.lifecycle;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import static com.srm.core.dto.lifecycle.LifecycleValidationPatterns.PARAM_INVALID_MSG;
import static com.srm.core.dto.lifecycle.LifecycleValidationPatterns.TYPE_REGEX;

/**
 * §2.4 创建生命周期申请请求体（契约 §2.4 L452-464，两字段 {@code type} / {@code reason}）。
 *
 * <b>本请求体刻意没有 status，也没有 version</b>：
 * - 没有 status：两步流程铁律——STAFF 只能新建 {@code PENDING} 申请单，<b>绝不动供应商状态</b>
 *   （契约 §2.4 L440"禁止绕过申请直接改状态"的另一面：申请入口本身也不提供改状态能力）；
 * - 没有 version：§2.4 是纯 insert（新单 version 由后端定 1），供应商状态未被触碰，无乐观锁对象；
 *   并发保护点落在"同类型不得有两张在途单"，由应用层预检 + uk_supplier_active_lifecycle 兜底 → 40902。
 *
 * 校验分工：单字段格式（type 值域、reason 非空）在入参层一次折 40001「参数校验失败」
 * （契约 §2.4 错误码矩阵 L481）；类型-状态门槛与在途唯一性属跨行判定，只能在 Service。
 *
 * JSON 字段物理 snake_case：逐字段显式 {@code @JsonProperty}（本批两字段名虽与驼峰同名，
 * 仍按模块二统一范式显式标注，避免依赖全局命名策略造成隐式漂移）。
 */
@Data
public class LifecycleRequestCreateRequest {

    /** SUSPEND / RESUME / ELIMINATE */
    @NotBlank(message = PARAM_INVALID_MSG)
    @Pattern(regexp = TYPE_REGEX, message = PARAM_INVALID_MSG)
    @JsonProperty("type")
    private String type;

    /** 申请理由：停用说原因 / 恢复说整改情况 / 淘汰说依据（契约字段表标"非空"必填） */
    @NotBlank(message = PARAM_INVALID_MSG)
    @JsonProperty("reason")
    private String reason;
}
