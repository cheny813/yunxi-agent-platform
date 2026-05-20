-- ========================================
-- Agent 规则引擎数据库迁移脚本
-- Version: 1.0
-- Author: yunxi Team
-- ========================================

-- ----------------------------
-- 1. 规则定义表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `agent_rule` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `name` VARCHAR(100) NOT NULL COMMENT '规则名称（唯一标识）',
    `description` VARCHAR(500) COMMENT '规则描述',
    `type` VARCHAR(20) NOT NULL COMMENT '规则类型：PRE/RUNTIME/POST',
    `priority` INT DEFAULT 50 COMMENT '优先级（1-100，数字越大优先级越高）',
    `condition_expression` TEXT COMMENT '条件表达式（MVEL格式，可选）',
    `action_expression` TEXT COMMENT '动作表达式（MVEL格式，可选）',
    `rule_class` VARCHAR(255) COMMENT '规则实现类全限定名（Java类方式）',
    `enabled` TINYINT DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    `created_by` VARCHAR(100) COMMENT '创建人',
    `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_by` VARCHAR(100) COMMENT '更新人',
    `updated_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY `uk_name` (`name`),
    INDEX `idx_type` (`type`),
    INDEX `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent规则定义表';

-- ----------------------------
-- 2. 规则执行日志表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `agent_rule_execution_log` (
    `id` BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    `rule_name` VARCHAR(100) NOT NULL COMMENT '规则名称',
    `agent_id` VARCHAR(100) COMMENT 'Agent ID',
    `task_id` VARCHAR(100) COMMENT '任务ID',
    `user_id` VARCHAR(100) COMMENT '用户ID',
    `username` VARCHAR(100) COMMENT '用户名',
    `triggered` TINYINT DEFAULT 0 COMMENT '是否触发：0-未触发，1-触发',
    `passed` TINYINT DEFAULT 1 COMMENT '是否通过：0-拒绝，1-通过',
    `error_message` TEXT COMMENT '错误信息',
    `execution_time` BIGINT COMMENT '执行耗时（毫秒）',
    `created_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_rule_name` (`rule_name`),
    INDEX `idx_agent_id` (`agent_id`),
    INDEX `idx_task_id` (`task_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_time` (`created_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则执行日志表';

-- ----------------------------
-- 3. 插入默认规则
-- ----------------------------

-- 3.1 权限检查规则（最高优先级）
INSERT INTO `agent_rule` (`name`, `description`, `type`, `priority`, `enabled`, `created_by`)
VALUES (
    'permission-check',
    '检查用户是否有权限执行当前任务',
    'PRE',
    100,
    1,
    'system'
);

-- 3.2 资源限制规则（高优先级）
INSERT INTO `agent_rule` (`name`, `description`, `type`, `priority`, `enabled`, `created_by`)
VALUES (
    'resource-limit',
    '检查系统资源是否充足（实例数、并发任务、内存）',
    'PRE',
    75,
    1,
    'system'
);

-- 3.3 超时控制规则（高优先级）
INSERT INTO `agent_rule` (`name`, `description`, `type`, `priority`, `enabled`, `created_by`)
VALUES (
    'timeout-control',
    '监控任务执行时间，超时自动中断',
    'RUNTIME',
    75,
    1,
    'system'
);

-- 3.4 审计日志规则（最低优先级）
INSERT INTO `agent_rule` (`name`, `description`, `type`, `priority`, `enabled`, `created_by`)
VALUES (
    'audit-log',
    '记录所有Agent操作审计日志',
    'POST',
    1,
    1,
    'system'
);

-- ----------------------------
-- 4. 示例：动态规则配置
-- ----------------------------

-- 4.1 禁止非管理员执行危险技能（MVEL 表达式示例）
INSERT INTO `agent_rule` (
    `name`, 
    `description`, 
    `type`, 
    `priority`, 
    `condition_expression`, 
    `action_expression`, 
    `enabled`, 
    `created_by`
)
VALUES (
    'forbidden-skill-check',
    '禁止非管理员执行危险技能',
    'PRE',
    90,
    'context.userInfo.roles.contains(''ADMIN'') == false && [''skill:delete-agent'', ''skill:modify-system''].contains(context.taskInfo.skillName)',
    'context.errorMessage = ''非管理员禁止执行该技能''; throw new RuleViolationException(context.errorMessage);',
    1,
    'system'
);

-- 4.2 调用频率限制（MVEL 表达式示例）
-- Note: 需要在规则上下文中注入 rateLimitService
INSERT INTO `agent_rule` (
    `name`, 
    `description`, 
    `type`, 
    `priority`, 
    `condition_expression`, 
    `action_expression`, 
    `enabled`, 
    `created_by`
)
VALUES (
    'rate-limit-check',
    '检查用户调用频率是否超限',
    'PRE',
    80,
    'rateLimitService.getCallCount(context.userInfo.userId) > 100',
    'context.errorMessage = ''调用频率超限，请稍后重试''; throw new RateLimitExceededException(context.errorMessage);',
    1,
    'system'
);

-- ----------------------------
-- 5. 创建视图（可选）
-- ----------------------------

-- 规则统计视图
CREATE OR REPLACE VIEW `v_rule_statistics` AS
SELECT 
    r.name AS rule_name,
    r.type AS rule_type,
    r.priority,
    r.enabled,
    COUNT(l.id) AS total_executions,
    SUM(CASE WHEN l.triggered = 1 THEN 1 ELSE 0 END) AS trigger_count,
    SUM(CASE WHEN l.passed = 0 THEN 1 ELSE 0 END) AS rejection_count,
    AVG(l.execution_time) AS avg_execution_time_ms
FROM `agent_rule` r
LEFT JOIN `agent_rule_execution_log` l ON r.name = l.rule_name
GROUP BY r.id, r.name, r.type, r.priority, r.enabled;
