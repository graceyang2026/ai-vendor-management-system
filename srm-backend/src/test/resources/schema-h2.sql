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
