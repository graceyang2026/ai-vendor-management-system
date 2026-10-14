-- =============================================================
-- 测试环境专用 H2 schema（仅模块 1 登录/Security 底座所需表）
-- 与 src/main/resources/schema.sql 中 sys_user 定义保持一致，
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
-- 供应商资质表 supplier_qualification（模块一 §1.7/§1.8；列结构与主 schema.sql §3 等价）
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
