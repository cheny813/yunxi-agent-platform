-- ============================================================
-- Agent Platform 数据库初始化脚本
-- 版本: 1.0.0
-- 数据库: MySQL 8.0+
-- ============================================================
-- 使用方法：
-- 1. 连接到MySQL服务器：mysql -u root -p
-- 2. 执行此脚本：source schema.sql
-- 或直接执行：mysql -u root -p < schema.sql
-- ============================================================

-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS yunxi_agent_platform
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE yunxi_agent_platform;

-- -----------------------------------------------------
-- 表: agent_agents
-- 描述: Agent 配置表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_agents (
    name VARCHAR(100) PRIMARY KEY COMMENT 'Agent 名称',
    prompt TEXT COMMENT '系统提示词',
    model_name VARCHAR(100) NOT NULL COMMENT '模型名称',
    provider VARCHAR(50) NOT NULL COMMENT '模型提供商',
    temperature DOUBLE COMMENT '温度参数',
    max_tokens INT COMMENT '最大 Token 数',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    description VARCHAR(500) COMMENT 'Agent 描述',
    api_key_hash VARCHAR(255) COMMENT 'API Key 哈希值',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_enabled (enabled),
    INDEX idx_provider (provider),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 配置表';

-- -----------------------------------------------------
-- 表: agent_conversations
-- 描述: 会话表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_conversations (
    id VARCHAR(100) PRIMARY KEY COMMENT '会话 ID',
    agent_name VARCHAR(100) NOT NULL COMMENT '关联的 Agent 名称',
    user_id VARCHAR(100) COMMENT '用户 ID',
    title VARCHAR(500) COMMENT '会话标题',
    messages TEXT COMMENT '消息历史（JSON 格式）',
    message_count INT DEFAULT 0 COMMENT '消息数量',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    last_updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    expires_at TIMESTAMP DEFAULT (CURRENT_TIMESTAMP + INTERVAL 24 HOUR) COMMENT '过期时间',

    INDEX idx_user_id (user_id),
    INDEX idx_agent_name (agent_name),
    INDEX idx_expires_at (expires_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话表';

-- -----------------------------------------------------
-- 表: agent_tool_configs
-- 描述: 工具配置表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_tool_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '配置 ID',
    tool_name VARCHAR(100) UNIQUE NOT NULL COMMENT '工具名称',
    enabled BOOLEAN DEFAULT TRUE COMMENT '是否启用',
    config TEXT COMMENT '工具配置（JSON 格式）',
    description VARCHAR(500) COMMENT '工具描述',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    INDEX idx_tool_name (tool_name),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具配置表';

-- -----------------------------------------------------
-- 表: agent_chat_logs（可选，用于审计和调试）
-- 描述: 对话日志表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_chat_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '日志 ID',
    conversation_id VARCHAR(100) COMMENT '会话 ID',
    agent_name VARCHAR(100) NOT NULL COMMENT 'Agent 名称',
    user_id VARCHAR(100) COMMENT '用户 ID',
    user_message TEXT COMMENT '用户消息',
    agent_response TEXT COMMENT 'Agent 响应',
    model_used VARCHAR(100) COMMENT '使用的模型',
    duration_ms BIGINT COMMENT '响应耗时（毫秒）',
    success BOOLEAN DEFAULT TRUE COMMENT '是否成功',
    error_message VARCHAR(500) COMMENT '错误信息（如果失败）',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_conversation_id (conversation_id),
    INDEX idx_agent_name (agent_name),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话日志表';

-- -----------------------------------------------------
-- 初始化数据
-- -----------------------------------------------------

-- 插入示例工具配置
INSERT INTO agent_tool_configs (tool_name, enabled, description, config) VALUES
('http_request', TRUE, 'HTTP 请求工具', '{"timeout": 30, "maxRedirects": 5}'),
('calculator', TRUE, '计算器工具', '{"precision": 10}'),
('database_query', FALSE, '数据库查询工具', '{"limit": 100}')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

-- -----------------------------------------------------
-- 创建视图（用于统计）
-- -----------------------------------------------------

-- 会话统计视图
CREATE OR REPLACE VIEW v_conversation_stats AS
SELECT
    DATE(created_at) as date,
    COUNT(*) as total_conversations,
    COUNT(DISTINCT user_id) as unique_users,
    SUM(message_count) as total_messages,
    AVG(message_count) as avg_messages_per_conversation
FROM agent_conversations
WHERE expires_at > NOW()
GROUP BY DATE(created_at);

-- -----------------------------------------------------
-- 完成
-- -----------------------------------------------------
SELECT '数据库初始化完成！' AS status;
