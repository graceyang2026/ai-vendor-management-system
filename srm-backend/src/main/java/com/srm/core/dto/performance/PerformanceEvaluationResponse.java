package com.srm.core.dto.performance;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 绩效评价响应体（契约附录 A4.2 —— <b>出站 16 字段一个不能少</b>，表 performance_evaluation）。
 *
 * 与 A3 LifecycleRequestResponse 同族惯例：日期时间一律 String 化输出
 * （日期 {@code yyyy-MM-dd}、时间 {@code yyyy-MM-dd'T'HH:mm:ss}），
 * 派生人名字段 {@code created_by_name} 无物理列、经 sys_user 反查（缺失回退空串，绝不 NPE），
 * JSON 字段物理 snake_case 逐字段显式 {@code @JsonProperty}。
 *
 * 三个复合字段的口径要点：
 * <ul>
 *   <li>{@code fact_record}：客观事实对象（8 字段，A4.1）；DB 里是 JSON 列，
 *       由实体上的 JacksonTypeHandler 结构化，出参与入参同一形态，前端可直接比对"我传的与落库的是否一致"；</li>
 *   <li>{@code result}：嵌套算分结果（9 字段，A4.3）——<b>只读</b>，任何写接口都不接收本字段，
 *       前端夹带即被 ignoreUnknown 丢弃（验收 §2.1）；</li>
 *   <li>{@code mode}：裁决 20261013 方案 a 的<b>只读回显</b>，区分真实录入与演示生成；
 *       §3.2 重提不入参 mode，沿用库内既有值（不得借重提切换取数模式）。</li>
 * </ul>
 *
 * {@code version} 是 §3.2 重提与 §3.3 复核入参 version 的<b>唯一合法取值链</b>
 * （§0 乐观锁全量四实体裁决 20261013）；未复核单的 reviewed_by / reviewed_at / review_comment
 * 三枚恒 null（契约类型为可选，§3.3 才回写）。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class PerformanceEvaluationResponse {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("supplier_id")
    private Long supplierId;

    /** 考核周期起（周期无独立编码列，start+end 表达） */
    @JsonProperty("period_start")
    private String periodStart;

    @JsonProperty("period_end")
    private String periodEnd;

    /** manual / mock（只读回显） */
    @JsonProperty("mode")
    private String mode;

    /** 客观事实 8 字段（A4.1） */
    @JsonProperty("fact_record")
    private PerformanceFactRecord factRecord;

    /** 后端算分产物 9 字段（A4.3），前端只读 */
    @JsonProperty("result")
    private PerformanceEvaluationResult result;

    /** 绩效三态 PENDING_REVIEW / APPROVED / RETURNED */
    @JsonProperty("status")
    private String status;

    /** 乐观锁版本：§3.2/§3.3 入参 version 的唯一取值链 */
    @JsonProperty("version")
    private Integer version;

    @JsonProperty("created_by")
    private Long createdBy;

    /** 关联 sys_user 派生（非物理列） */
    @JsonProperty("created_by_name")
    private String createdByName;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    /** 复核人（§3.3 回写；未复核为 null） */
    @JsonProperty("reviewed_by")
    private Long reviewedBy;

    @JsonProperty("reviewed_at")
    private String reviewedAt;

    /** 复核意见（§3.3 REJECT 必填、APPROVE 选填；未复核为 null） */
    @JsonProperty("review_comment")
    private String reviewComment;
}
