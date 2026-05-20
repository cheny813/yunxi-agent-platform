-- ========================================
-- Agent 规则引擎数据库表结构
-- ========================================
-- 版本：V1.0__Init_Schema.sql
-- 时间：2026-04-08
-- ========================================

-- ----------------------------------------
-- 规则表
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS `agent_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '规则名称（唯一）',
    `description` VARCHAR(500) COMMENT '规则描述',
    `type` VARCHAR(20) NOT NULL COMMENT '规则类型',
    `priority` INT DEFAULT 0 COMMENT '优先级',
    `condition_expression` TEXT COMMENT 'MVEL条件表达式',
    `action_expression` TEXT COMMENT 'MVEL动作表达式',
    `rule_class` VARCHAR(255) COMMENT 'Java规则实现类全限定名',
    `enabled` TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    `created_by` VARCHAR(100) COMMENT '创建人',
    `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by` VARCHAR(100) COMMENT '更新人',
    `updated_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_type` (`type`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则表';

-- ----------------------------------------
-- 规则执行日志表
-- ----------------------------------------
CREATE TABLE IF NOT EXISTS `agent_rule_execution_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `agent_id` VARCHAR(100) COMMENT 'Agent ID',
    `task_id` VARCHAR(100) COMMENT '任务ID',
    `user_id` VARCHAR(100) COMMENT '用户ID',
    `username` VARCHAR(100) COMMENT '用户名',
    `triggered` TINYINT(1) COMMENT '是否触发',
    `passed` TINYINT(1) COMMENT '是否通过',
    `error_message` TEXT COMMENT '错误信息',
    `execution_time` BIGINT COMMENT '执行时间（毫秒）',
    `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_rule_name` (`rule_name`),
    KEY `idx_agent_id` (`agent_id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则执行日志表';

-- ----------------------------------------
-- 初始化内置规则
-- ----------------------------------------
INSERT INTO `agent_rule` (`name`, `description`, `type`, `priority`, `rule_class`, `enabled`, `created_by`) VALUES
('permission-check', '权限检查规则', 'PRE', 100, 'io.yunxi.agent.rule.builtin.PermissionRule', 1, 'system'),
('rate-limit', '速率限制规则', 'PRE', 90, 'io.yunxi.agent.rule.builtin.RateLimitRule', 1, 'system'),
('timeout-check', '超时检查规则', 'RUNTIME', 80, 'io.yunxi.agent.rule.builtin.TimeoutRule', 1, 'system'),
('resource-limit', '资源限制规则', 'RUNTIME', 70, 'io.yunxi.agent.rule.builtin.ResourceLimitRule', 1, 'system'),
('audit-log', '审计日志规则', 'POST', 50, 'io.yunxi.agent.rule.builtin.AuditLogRule', 1, 'system');
