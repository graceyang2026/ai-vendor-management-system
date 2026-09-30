-- =============================================================
-- SRM 数据库表结构（唯一事实来源）
-- 依据: docs/SRM 后端数据库表设计.md
-- 说明:
--   1. 本文件由 Spring Boot 自动执行(spring.sql.init.mode=always),
--      因此全部 DDL 必须幂等(CREATE TABLE IF NOT EXISTS),可重复执行。
--   2. 通用约定: 主键 BIGINT AUTO_INCREMENT; 时间字段 DATETIME;
--      逻辑删除 deleted TINYINT(1) 默认 0; 状态字段一律 VARCHAR(不用 ENUM)。
--   3. updated_at 的自动刷新由 MyBatis-Plus MetaObjectHandler 在应用层完成,
--      不依赖数据库 ON UPDATE CURRENT_TIMESTAMP。
--   4. 不建物理 FOREIGN KEY,表间关联仅为逻辑关系,引用完整性由应用层维护。
--   5. 数据库 vendor_db 本身需预先创建(见 srm-backend/sql/init-database.sql)。
-- =============================================================

-- -------------------------------------------------------------
-- 1. 用户表 sys_user
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username      VARCHAR(50)  NOT NULL                COMMENT '登录账号，唯一',
    password_hash VARCHAR(255) NOT NULL                COMMENT '加密后的密码',
    real_name     VARCHAR(50)  NOT NULL                COMMENT '姓名',
    role          VARCHAR(20)  NOT NULL DEFAULT 'STAFF' COMMENT '角色',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1      COMMENT '是否启用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username),
    KEY idx_role (role),
    KEY idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户表';

-- -------------------------------------------------------------
-- 2. 供应商表 supplier
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier (
    id                      BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name                    VARCHAR(200) NOT NULL                COMMENT '供应商名称',
    tax_no                  VARCHAR(50)  NOT NULL                COMMENT '统一社会信用代码',
    type                    VARCHAR(50)  NULL                    COMMENT '供应商类型',
    contact_name            VARCHAR(50)  NULL                    COMMENT '联系人',
    contact_phone           VARCHAR(30)  NULL                    COMMENT '联系电话',
    contact_email           VARCHAR(100) NULL                    COMMENT '联系邮箱',
    address                 VARCHAR(500) NULL                    COMMENT '地址',
    remark                  TEXT         NULL                    COMMENT '备注',
    effective_date          DATE         NULL                    COMMENT '合作开始日期',
    expiry_date             DATE         NULL                    COMMENT '合作结束日期',
    status                  VARCHAR(30)  NOT NULL DEFAULT 'DRAFT' COMMENT '供应商状态',
    created_by              BIGINT       NOT NULL                COMMENT '创建人',
    latest_performance_grade VARCHAR(10) NULL                    COMMENT '最新绩效等级',
    risk_warning            TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '是否有风险',
    version                 INT          NOT NULL DEFAULT 1      COMMENT '乐观锁版本',
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted                 TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    -- 生成列: 仅未删除行承载税号值,用于下方唯一索引;逻辑删除的行返回 NULL 不占用税号。
    -- 不对业务代码暴露(实体类不映射该列)。
    tax_no_active           VARCHAR(50)  GENERATED ALWAYS AS (IF(deleted = 0, tax_no, NULL)) STORED
                                         COMMENT '生成列:未删除行=税号,已删除行=NULL,承载唯一索引',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tax_no_active (tax_no_active),
    KEY idx_name (name),
    KEY idx_status (status),
    KEY idx_created_by (created_by),
    KEY idx_status_deleted (status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商表';

-- -------------------------------------------------------------
-- 3. 供应商资质表 supplier_qualification
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier_qualification (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    supplier_id    BIGINT       NOT NULL                COMMENT '供应商 ID',
    doc_type       VARCHAR(30)  NOT NULL                COMMENT '文件类型',
    file_url       VARCHAR(500) NOT NULL                COMMENT '文件地址',
    effective_date DATE         NULL                    COMMENT '生效日期',
    expiry_date    DATE         NULL                    COMMENT '到期日期',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted        TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_supplier_id (supplier_id),
    KEY idx_supplier_doc_type (supplier_id, doc_type),
    KEY idx_expiry_date (expiry_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商资质表';

-- -------------------------------------------------------------
-- 4. 绩效评价表 performance_evaluation
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS performance_evaluation (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    supplier_id      BIGINT        NOT NULL                COMMENT '供应商 ID',
    period_start     DATE          NOT NULL                COMMENT '评价开始日期',
    period_end       DATE          NOT NULL                COMMENT '评价结束日期',
    mode             VARCHAR(20)   NOT NULL DEFAULT 'manual' COMMENT '计算模式',
    fact_record      JSON          NOT NULL                COMMENT '原始事实数据',
    score_quality    DECIMAL(5,2)  NULL                    COMMENT '质量得分',
    score_delivery   DECIMAL(5,2)  NULL                    COMMENT '交付得分',
    score_price      DECIMAL(5,2)  NULL                    COMMENT '价格得分',
    score_service    DECIMAL(5,2)  NULL                    COMMENT '服务得分',
    score_compliance DECIMAL(5,2)  NULL                    COMMENT '合规得分',
    total_score      DECIMAL(5,2)  NULL                    COMMENT '总分',
    grade            VARCHAR(10)   NULL                    COMMENT '评级',
    quality_exempt   TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否质量豁免',
    risk_warning     TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '是否风险预警',
    status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT '状态',
    created_by       BIGINT        NOT NULL                COMMENT '创建人',
    reviewed_by      BIGINT        NULL                    COMMENT '审核人',
    reviewed_at      DATETIME      NULL                    COMMENT '审核时间',
    review_comment   VARCHAR(1000) NULL                    COMMENT '审核意见',
    version          INT           NOT NULL DEFAULT 1      COMMENT '乐观锁版本',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_supplier_id (supplier_id),
    KEY idx_status (status),
    KEY idx_created_by (created_by),
    KEY idx_supplier_period (supplier_id, period_start, period_end),
    KEY idx_supplier_status (supplier_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绩效评价表';

-- -------------------------------------------------------------
-- 5. 绩效评价草稿表 performance_evaluation_draft
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS performance_evaluation_draft (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    supplier_id  BIGINT      NOT NULL                COMMENT '供应商 ID',
    period_start DATE        NOT NULL                COMMENT '评价开始日期',
    period_end   DATE        NOT NULL                COMMENT '评价结束日期',
    mode         VARCHAR(20) NOT NULL DEFAULT 'manual' COMMENT '计算模式',
    fact_record  JSON        NULL                    COMMENT '原始事实数据',
    status       VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '草稿状态',
    created_by   BIGINT      NOT NULL                COMMENT '创建人',
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted      TINYINT(1)  NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_supplier_id (supplier_id),
    KEY idx_created_by (created_by),
    KEY idx_status (status),
    KEY idx_supplier_period (supplier_id, period_start, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='绩效评价草稿表';

-- -------------------------------------------------------------
-- 6. 生命周期申请表 lifecycle_request
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_request (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    supplier_id      BIGINT        NOT NULL                COMMENT '供应商 ID',
    type             VARCHAR(20)   NOT NULL                COMMENT '申请类型',
    reason           VARCHAR(1000) NOT NULL                COMMENT '申请原因',
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT '申请状态',
    applied_by       BIGINT        NOT NULL                COMMENT '申请人',
    applied_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    decided_by       BIGINT        NULL                    COMMENT '审核人',
    decided_at       DATETIME      NULL                    COMMENT '审核时间',
    decision_comment VARCHAR(1000) NULL                    COMMENT '审核意见',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted          TINYINT(1)    NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_supplier_id (supplier_id),
    KEY idx_status (status),
    KEY idx_supplier_type_status (supplier_id, type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生命周期申请表';

-- -------------------------------------------------------------
-- 7. 审计日志表 audit_log (只插入不更新,故无 updated_at / deleted)
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    entity_type   VARCHAR(50)   NOT NULL                COMMENT '业务对象类型',
    entity_id     BIGINT        NOT NULL                COMMENT '业务对象 ID',
    operator_id   BIGINT        NOT NULL                COMMENT '操作人',
    operator_role VARCHAR(20)   NOT NULL                COMMENT '操作人角色',
    action        VARCHAR(50)   NOT NULL                COMMENT '操作类型',
    old_status    VARCHAR(30)   NULL                    COMMENT '操作前状态',
    new_status    VARCHAR(30)   NULL                    COMMENT '操作后状态',
    result        VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS' COMMENT '操作结果',
    comment       VARCHAR(1000) NULL                    COMMENT '操作说明',
    created_at    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_entity_type_id (entity_type, entity_id),
    KEY idx_operator_id (operator_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志表';
