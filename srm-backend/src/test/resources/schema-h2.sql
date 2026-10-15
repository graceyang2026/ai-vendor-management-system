-- =============================================================
-- 测试环境专用 H2 schema（模块 1 登录/Security 底座 + 模块一/二业务表）
-- 与 src/main/resources/schema.sql 保持列结构等价，
-- 但使用 H2 兼容语法（主 schema.sql 含 MySQL 生成列语法，无法在 H2 上执行）
-- =============================================================

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    real_name     VARCHAR(50)  NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'STAFF',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    UNIQUE (username)
);

-- -------------------------------------------------------------
-- 供应商表 supplier（模块一供应商主链；列结构与主 schema.sql §2 等价）
-- H2 差异说明（均为语法层适配，语义与 MySQL 版一致）：
--   1. 生成列：MySQL 的 IF(deleted=0, tax_no, NULL) 改 H2 标准 CASE WHEN 写法；
--      H2 2.x 不需要（也不接受）MySQL 的 STORED 关键字，GENERATED ALWAYS AS 即计算列。
--   2. 唯一索引：MySQL 内联 UNIQUE KEY uk_tax_no_active 改独立 CREATE UNIQUE INDEX；
--      H2 唯一索引允许多行 NULL，逻辑删除行（tax_no_active=NULL）不占用税号，
--      与主 schema 生成列设计语义一致（已用 H2 2.3.232 实测：重复税号 23505 拦截、
--      deleted=1 后同税号可重建）。
--   3. TEXT 改 CLOB；DATETIME 改 TIMESTAMP；去掉 ENGINE/CHARSET/COMMENT 等 MySQL 方言。
-- 该列不对业务代码暴露（Supplier 实体不映射 tax_no_active，与主 schema 注释同口径）。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier (
    id                       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                     VARCHAR(200) NOT NULL,
    tax_no                   VARCHAR(50)  NOT NULL,
    type                     VARCHAR(50)  NULL,
    contact_name             VARCHAR(100) NULL,
    contact_phone            VARCHAR(30)  NULL,
    contact_email            VARCHAR(100) NULL,
    address                  VARCHAR(500) NULL,
    remark                   CLOB         NULL,
    effective_date           DATE         NULL,
    expiry_date              DATE         NULL,
    status                   VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    created_by               BIGINT       NOT NULL,
    latest_performance_grade VARCHAR(10)  NULL,
    risk_warning             TINYINT      NOT NULL DEFAULT 0,
    version                  INT          NOT NULL DEFAULT 1,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                  TINYINT      NOT NULL DEFAULT 0,
    tax_no_active            VARCHAR(50)  GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN tax_no ELSE NULL END)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tax_no_active ON supplier (tax_no_active);

-- -------------------------------------------------------------
-- 供应商资质表 supplier_qualification（模块一 §1.7/§1.8 + 模块二 §2.2/§2.3；列结构与主 schema.sql §3 等价）
-- 照抄上方 supplier 表的 H2 适配范式（裁决 20261012 DDL① + DDL④ version DEFAULT 1）：
--   1. 生成列 approved_key：MySQL CASE WHEN 写法原样可用；仅 deleted=0 且 status='APPROVED'
--      时取 CONCAT(supplier_id,'_',doc_type)，其余为 NULL——同供应商同证照类型只允许
--      一条 APPROVED 现行版（PENDING/REJECTED/已删除行 NULL 不占位）。
--   2. 唯一索引：MySQL 内联 UNIQUE KEY uk_supplier_approved_doc 改独立 CREATE UNIQUE INDEX。
--   3. DATETIME 改 TIMESTAMP；去掉 ENGINE/CHARSET/COMMENT 方言；主表无外键，H2 同不建。
-- 该生成列不对业务代码暴露（SupplierQualification 实体不映射 approved_key，与主 schema 注释同口径）。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS supplier_qualification (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    supplier_id    BIGINT       NOT NULL,
    doc_type       VARCHAR(30)  NOT NULL,
    file_url       VARCHAR(500) NOT NULL,
    effective_date DATE         NULL,
    expiry_date    DATE         NULL,
    status         VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    reviewed_by    BIGINT       NULL,
    reviewed_at    TIMESTAMP    NULL,
    review_comment VARCHAR(500) NULL,
    version        INT          NOT NULL DEFAULT 1,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    approved_key   VARCHAR(64)  GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 AND status = 'APPROVED'
             THEN CONCAT(supplier_id, '_', doc_type)
             ELSE NULL
        END
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_approved_doc ON supplier_qualification (approved_key);

-- -------------------------------------------------------------
-- 生命周期申请表 lifecycle_request（模块二 §2.4–§2.6；列结构与主 schema.sql §6 等价）
-- 同上方两表的 H2 适配范式（DDL⑤ 裁决 20261013 候选 A：version INT NOT NULL DEFAULT 1；
-- schema.sql L182-190：active_key 生成列 + uk_supplier_active_lifecycle 在途唯一兜底）：
--   1. active_key：仅 deleted=0 且 status='PENDING' 时取 CONCAT(supplier_id,'_',type)，
--      其余 NULL 不占位——同供应商同类型只允许一张在途申请（已决/已删除历史不阻塞新建）。
--   2. 唯一索引：MySQL 内联 UNIQUE KEY 改独立 CREATE UNIQUE INDEX（H2 唯一索引允许多行 NULL）。
--   3. applied_at/created_at/updated_at 的 DEFAULT CURRENT_TIMESTAMP 与主 schema 一致，
--      业务代码仍显式赋值，不依赖 DB 默认值。
-- active_key 不对业务代码暴露（LifecycleRequest 实体不映射该列，与 tax_no_active/approved_key 同口径）。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS lifecycle_request (
    id               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    supplier_id      BIGINT        NOT NULL,
    type             VARCHAR(20)   NOT NULL,
    reason           VARCHAR(1000) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    version          INT           NOT NULL DEFAULT 1,
    applied_by       BIGINT        NOT NULL,
    applied_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_by       BIGINT        NULL,
    decided_at       TIMESTAMP     NULL,
    decision_comment VARCHAR(1000) NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          TINYINT       NOT NULL DEFAULT 0,
    active_key       VARCHAR(64)   GENERATED ALWAYS AS (
        CASE WHEN deleted = 0 AND status = 'PENDING'
             THEN CONCAT(supplier_id, '_', type)
             ELSE NULL
        END
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_supplier_active_lifecycle ON lifecycle_request (active_key);

-- -------------------------------------------------------------
-- 审计日志表 audit_log（模块二全量留痕 + 集成测试断言依据；列结构与主 schema.sql §7 等价）
-- 只插入不更新，故无 updated_at / deleted（与主 schema 同口径）；
-- supplier_id 为裁决 20261012 DDL② 冗余列（§2.2 连带/§2.3/§2.4/§2.6 均须写归属供应商）。
-- DATETIME 改 TIMESTAMP、去掉 ENGINE/CHARSET/COMMENT 方言，索引改独立 CREATE INDEX。
-- -------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id            BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    entity_type   VARCHAR(50)   NOT NULL,
    entity_id     BIGINT        NOT NULL,
    supplier_id   BIGINT        NULL,
    operator_id   BIGINT        NOT NULL,
    operator_role VARCHAR(20)   NOT NULL,
    action        VARCHAR(50)   NOT NULL,
    old_status    VARCHAR(30)   NULL,
    new_status    VARCHAR(30)   NULL,
    result        VARCHAR(20)   NOT NULL DEFAULT 'SUCCESS',
    comment       VARCHAR(1000) NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_audit_entity_type_id ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_supplier_id ON audit_log (supplier_id);
CREATE INDEX IF NOT EXISTS idx_audit_operator_id ON audit_log (operator_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at ON audit_log (created_at);
