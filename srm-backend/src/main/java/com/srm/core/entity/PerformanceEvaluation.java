package com.srm.core.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.srm.core.dto.performance.PerformanceFactRecord;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 绩效评价实体，映射 performance_evaluation 表（schema.sql §4 L106-151 建表实文，契约附录 A4）。
 *
 * 并发与删除保护（§0 乐观锁全量四实体裁决 20261013）：
 * - version 必须标注 {@link Version}——DDL 早已就绪（{@code version INT NOT NULL DEFAULT 1}），
 *   若实体漏标注则拦截器不生效、§3.2/§3.3 的 40901 永无触发基础
 *   （已知坑，契约《裁决记录与样例差异》一.2 点名；范式与 Supplier.java L63 逐字一致）。
 * - {@code @TableLogic}：逻辑删除行自动从所有查询隐身；DB 生成列 active_period_key
 *   随 deleted/status 计算，逻辑删除即在途单自动让位，与 lifecycle_request.active_key 同族范式。
 *
 * 防篡改铁律（api-spec §4 / rules.md §4.3）：本实体承载的 9 枚分数/评级/预警列全部由
 * PerformanceScoreCalculator 服务端算出后写入，任何入站 DTO 物理上不含分数字段，
 * 不存在"前端传值直落本实体"的通道。
 *
 * 有意不映射的列：
 * - active_period_key：生成列（{@code CASE WHEN deleted=0 AND status IN ('PENDING_REVIEW','RETURNED')
 *   THEN CONCAT(supplier_id,'_',period_start,'_',period_end)}），唯一索引 uk_supplier_active_period
 *   的载体，即"同供应商同周期仅一张在途评价单"的 DB 兜底，不对业务代码暴露（schema.sql L131-143 注释同口径）。
 *
 * fact_record 映射方案（B1 授权自定，取 JacksonTypeHandler 路线）：
 * {@code @TableName(autoResultMap = true)} + {@code @TableField(typeHandler = JacksonTypeHandler.class)}
 * 把 JSON 列直接结构化成 {@link PerformanceFactRecord}——序列化收口在框架层单一位置，业务代码
 * <b>不手写 try/catch(JsonProcessingException)</b>（那种不可达分支既测不到又拖覆盖率），
 * 实体也就不再需要"字符串 + 各处自行反序列化"的多份口径。列侧形态：主 schema 为 MySQL {@code JSON}，
 * 测试库 schema-h2.sql 用 {@code CLOB}（H2 2.x 的 JSON 类型不接受 JDBC 字符串直插，方言注记见该文件）。
 *
 * 状态口径：{@code status} 为<b>绩效三态</b> PENDING_REVIEW/APPROVED/RETURNED（api-spec §4.2
 * EvaluationStatus），与供应商六态、资质三态、申请单三态严格区分，四套枚举互不复用。
 */
@Data
@TableName(value = "performance_evaluation", autoResultMap = true)
public class PerformanceEvaluation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 被评供应商（supplier.id）；§0 规则 C 行级隔离与审计冗余列的数据源 */
    private Long supplierId;

    /** 考核周期起（周期无独立编码列，start+end 表达，契约 A4.2 注记） */
    private LocalDate periodStart;

    /** 考核周期止（必须晚于 periodStart，Service 层折 40001「事实数据校验失败」） */
    private LocalDate periodEnd;

    /** manual / mock（DB DEFAULT 'manual'）；裁决 20261013 方案 a：出参只读回显，写接口后置流程不接受切换 */
    private String mode;

    /** 客观事实 8 字段（A4.1）；JSON 列的结构化形态，分数物理不存在于本列 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private PerformanceFactRecord factRecord;

    private BigDecimal scoreQuality;

    private BigDecimal scoreDelivery;

    private BigDecimal scorePrice;

    private BigDecimal scoreService;

    /** C 维度：后端自动读 APPROVED 资质 expiry_date 判定，前端不传（api-spec §4.1） */
    private BigDecimal scoreCompliance;

    private BigDecimal totalScore;

    /** A / B / C / D（契约名 grade，非模板样例的 rating） */
    private String grade;

    /** 当期无业务整体免考标识（历史列名；total_batches=0 复用同一标识，不拆 delivery_exempt，裁决 api-spec §4.1） */
    private Boolean qualityExempt;

    /** 风险预警打标（存在过期已批资质 → true；只打标，不改供应商状态） */
    private Boolean riskWarning;

    /** 绩效三态 PENDING_REVIEW/APPROVED/RETURNED */
    private String status;

    private Long createdBy;

    /**
     * 复核人（§3.3 回写；未复核恒 NULL）。
     * {@code updateStrategy = FieldStrategy.IGNORED}：退回重提（§3.2）须把上一次复核痕迹清成 NULL
     * （用户 2026-10-15 18:50 拍板裁决③）。MyBatis-Plus updateById 默认 NOT_NULL 会跳过 null 字段、
     * 令 {@code SET reviewed_by = NULL} 静默不生效，故显式声明"null 亦入 SET"；真实落库由集成测试断言。
     * 复核（§3.3）路径本就对三列显式赋值，故本策略不改变其可观察行为。
     */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private Long reviewedBy;

    /** 复核时间（重提清 NULL，策略同 {@link #reviewedBy}） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime reviewedAt;

    /** 复核意见（§3.3 REJECT 必填、APPROVE 选填，回写本列；重提清 NULL，策略同上） */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String reviewComment;

    /** 乐观锁版本；§3.2/§3.3 入参 version 的唯一取值链（经 PerformanceEvaluationResponse 回显） */
    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
