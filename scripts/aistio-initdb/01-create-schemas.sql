-- Aistio 控制面 (2.1.0-BETA1) 的数据库迁移逻辑不会自动创建 schema，
-- 必须在 builder 库预先建好以下 schema，否则控制面/数据面/调度/网关启动会因
-- "no schema has been selected to create in" 或 "relation xxx does not exist" 崩溃。
--
-- 官方 postgres 镜像仅在数据目录为空（首次 init）时执行本目录下的脚本，
-- 因此执行 `docker compose --profile aistio down -v` 后再 `up` 也能自愈，
-- 无需手动补建 schema。CREATE SCHEMA IF NOT EXISTS 保证幂等。
CREATE SCHEMA IF NOT EXISTS rt;        -- 控制面 storage-driver=postgres 的 search_path
CREATE SCHEMA IF NOT EXISTS dp;        -- 数据面 / 调度面的 JDBC currentSchema=dp
CREATE SCHEMA IF NOT EXISTS builder;   -- 数据面自动建表时的 $user 默认 schema 兜底
CREATE SCHEMA IF NOT EXISTS scheduler; -- 调度面自有表兜底
CREATE SCHEMA IF NOT EXISTS gateway;   -- 网关自有表兜底
CREATE SCHEMA IF NOT EXISTS analytics; -- 分析面兜底
