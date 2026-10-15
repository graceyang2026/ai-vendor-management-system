package com.srm.core.dto.supplier;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * §2.1 提交准入审核请求体（契约 §2.1 L273-283，<b>请求体仅 version 一字段</b>，裁决 20261013 候选 B）。
 *
 * 校验分工刻意分层（与既有测试 SupplierSubmitAuditIntegrationTest 逐条对齐）：
 * <ul>
 *   <li>{@code version} 取 {@code SupplierResponse.version}，字段表规则 {@code >= 1}；
 *       0/负数属<b>格式非法</b>，由 {@code @Min} 在入参层折 40001；</li>
 *   <li>刻意<b>不加 {@code @NotNull}</b>：契约 §2.1 错误码矩阵把「version 缺失或不匹配」一并归
 *       {@code 40901}（裁决 20261013 候选 B 原文），缺失属并发语义而非格式语义，
 *       因此必须放到 Service 层判定；入参层一旦以 40001 拦截，矩阵那一行就成了死码。</li>
 * </ul>
 * 状态迁移本身不接受任何入参覆盖（无 status 字段）：DRAFT/RETURNED → PENDING_REVIEW 由 Service 硬编码。
 *
 * JSON 字段物理 snake_case：逐字段显式 {@code @JsonProperty}，与模块一 DTO 同一范式。
 */
@Data
public class SupplierSubmitRequest {

    /** 乐观锁版本；缺失 → 40901（Service 侧），0/负数 → 40001（入参层） */
    @Min(value = 1, message = "版本号不合法（必须为正整数）")
    @JsonProperty("version")
    private Integer version;
}
