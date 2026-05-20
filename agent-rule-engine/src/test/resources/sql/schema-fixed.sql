-- 规则引擎测试数据库表结构初始化

-- 规则表
CREATE TABLE IF NOT EXISTS `rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) NOT NULL,
    `description` VARCHAR(1000),
    `content` TEXT NOT NULL,
    `version` INT DEFAULT 1,
    `status` VARCHAR(50) DEFAULT 'ACTIVE',
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `created_by` VARCHAR(100),
    `updated_by` VARCHAR(100),
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_name_version` (`name`, `version`)
);

-- 规则执行日志表
CREATE TABLE IF NOT EXISTS `rule_execution_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `rule_id` BIGINT NOT NULL,
    `rule_name` VARCHAR(255) NOT NULL,
    `input_data` TEXT,
    `output_data` TEXT,
    `execution_time` BIGINT DEFAULT 0,
    `status` VARCHAR(50) DEFAULT 'SUCCESS',
    `error_message` TEXT,
    `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
    `created_by` VARCHAR(100),
    PRIMARY KEY (`id`),
    KEY `idx_rule_id` (`rule_id`),
    KEY `idx_created_at` (`created_at`)
);