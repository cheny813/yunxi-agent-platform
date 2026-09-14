-- ============================================================
-- mcp-nutrition 最小化初始化脚本（可开源）
-- ============================================================
-- 用途：让 mcp-nutrition 在一键启动（本地 / 外部 docker 环境）场景下
--       能连上本地 MySQL 并跑起来，避免因「库/表不存在」而报错。
--
-- 生效方式（docker compose 一键启动）：
--   本文件由 docker-compose.yml 挂载到 mysql 容器的
--   /docker-entrypoint-initdb.d/ 下，在【数据卷首次初始化】时自动执行。
--   若 MySQL 数据卷已存在（非首次），请执行
--     docker compose down -v && docker compose up -d
--   重建以使初始化脚本重新生效，或手动在 MySQL 中执行本文件。
-- ============================================================

CREATE DATABASE IF NOT EXISTS yunxi_nutrition
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE yunxi_nutrition;

-- 菜品主表（DishQueryService / SchoolDishSyncRunner / 同步管道共用）
CREATE TABLE IF NOT EXISTS dish_library (
  id          BIGINT       PRIMARY KEY,
  name        VARCHAR(255),
  type        VARCHAR(64),
  school_id   BIGINT,
  deleted     TINYINT      DEFAULT 0,
  update_time DATETIME
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 菜品 <-> 食材关联（含用量 dosage，单位克）
CREATE TABLE IF NOT EXISTS dish_food_ingredient (
  id      BIGINT PRIMARY KEY,
  dish_id BIGINT,
  fi_id   BIGINT,
  dosage  DOUBLE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 食材主数据
CREATE TABLE IF NOT EXISTS food_ingredient (
  id            BIGINT       PRIMARY KEY,
  name          VARCHAR(255),
  main_class_id BIGINT,
  sub_class_id  BIGINT,
  deleted       TINYINT      DEFAULT 0,
  update_time   DATETIME
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 食材营养成分（nutrient_content 按每 100g 计）
CREATE TABLE IF NOT EXISTS food_ingredient_nutrient (
  fi_id           BIGINT,
  nutrient_id     BIGINT,
  nutrient_content DOUBLE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 营养素字典
CREATE TABLE IF NOT EXISTS nutrient (
  id   BIGINT       PRIMARY KEY,
  name VARCHAR(255),
  unit VARCHAR(32)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 菜品分类
CREATE TABLE IF NOT EXISTS dish_class (
  id      BIGINT       PRIMARY KEY,
  name    VARCHAR(255),
  pid     BIGINT,
  remark  VARCHAR(512),
  deleted TINYINT      DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 食材分类映射（主类/子类）
CREATE TABLE IF NOT EXISTS food_ingredient_class (
  id      BIGINT PRIMARY KEY,
  main_id BIGINT,
  sub_id  BIGINT,
  deleted TINYINT DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS food_ingredient_main_class (
  id      BIGINT       PRIMARY KEY,
  name    VARCHAR(255),
  deleted TINYINT      DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS food_ingredient_sub_class (
  id      BIGINT       PRIMARY KEY,
  name    VARCHAR(255),
  deleted TINYINT      DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 营养标准
CREATE TABLE IF NOT EXISTS nutrient_standard (
  id        BIGINT       PRIMARY KEY,
  code      VARCHAR(64),
  name      VARCHAR(255),
  age_group VARCHAR(64),
  region    VARCHAR(64),
  deleted   TINYINT      DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 营养标准明细（各营养素推荐摄入量）
CREATE TABLE IF NOT EXISTS nutrient_standard_dcn (
  id                           BIGINT       PRIMARY KEY,
  ns_id                        BIGINT,
  nsc_id                       BIGINT,
  n_id                         BIGINT,
  all_recommend_qty            DOUBLE,
  intake_suitable_suggestion   VARCHAR(512),
  intake_low_suggestion        VARCHAR(512),
  intake_high_suggestion       VARCHAR(512)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 就餐人群
CREATE TABLE IF NOT EXISTS nutritional_standard_dining_crowd (
  id   BIGINT       PRIMARY KEY,
  name VARCHAR(255)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 评分指标明细 / 类别
CREATE TABLE IF NOT EXISTS cook_book_score_index_detail (
  id            BIGINT       PRIMARY KEY,
  index_name    VARCHAR(255),
  index_ratio   DOUBLE,
  index_class_id BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS cook_book_score_index_class (
  id          BIGINT       PRIMARY KEY,
  index_class VARCHAR(255),
  index_ratio DOUBLE,
  dimension   VARCHAR(64)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 同步游标表（SyncCursorRepository 启动时也会 CREATE TABLE IF NOT EXISTS，此处一并建好以防万一）
CREATE TABLE IF NOT EXISTS sync_cursor (
  pipeline_name VARCHAR(128) PRIMARY KEY,
  cursor_value  LONGTEXT,
  synced_rows   BIGINT,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
