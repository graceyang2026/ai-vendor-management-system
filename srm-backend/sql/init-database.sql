-- =============================================================
-- SRM 数据库初始化脚本（手工执行一次）
-- 职责: 只创建数据库 vendor_db 本身。
--       建表由 src/main/resources/schema.sql 负责(Spring Boot 启动自动执行,
--       也可手工执行,见下方"手工建表"用法)。
--
-- 用法(在项目根目录):
--   # 1. 创建数据库
--   mysql -uroot -p -h127.0.0.1 -P3306 < srm-backend/sql/init-database.sql
--   # 2. 建表(二选一)
--   mysql -uroot -p -h127.0.0.1 -P3306 vendor_db < srm-backend/src/main/resources/schema.sql
--   #    或直接启动后端应用(spring.sql.init.mode=always 会自动执行 schema.sql)
-- =============================================================

CREATE DATABASE IF NOT EXISTS vendor_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE vendor_db;
