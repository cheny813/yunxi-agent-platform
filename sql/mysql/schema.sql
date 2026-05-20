-- ============================================================
-- yunxi Agent Platform 数据库初始化脚本
-- 版本: 2.0.0
-- 数据库: MySQL 8.0+
-- 数据库名: yunxi_agent_platform
-- ============================================================

CREATE DATABASE IF NOT EXISTS yunxi_agent_platform
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE yunxi_agent_platform;

-- ============================================================
-- 第一部分: Agent 核心表
-- ============================================================

-- -----------------------------------------------------
-- 表: agent_definition
-- 描述: Agent 定义表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_definition (
  id VARCHAR(64) NOT NULL COMMENT 'Agent ID',
  name VARCHAR(128) NOT NULL COMMENT 'Agent 名称',
  description VARCHAR(512) NULL COMMENT 'Agent 描述',
  type VARCHAR(64) NOT NULL COMMENT 'Agent 类型',
  config_json JSON NOT NULL COMMENT 'Agent 配置（JSON）',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (id),
  INDEX idx_agent_type (type),
  INDEX idx_agent_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent 定义表';

-- -----------------------------------------------------
-- 表: agent_session
-- 描述: 会话表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session (
  session_id VARCHAR(64) NOT NULL COMMENT '会话 ID',
  agent_id VARCHAR(64) NOT NULL COMMENT 'Agent ID',
  user_id VARCHAR(128) NOT NULL COMMENT '用户 ID',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  PRIMARY KEY (session_id),
  INDEX idx_session_agent (agent_id),
  INDEX idx_session_user (user_id),
  INDEX idx_session_created_at (created_at),
  CONSTRAINT fk_session_agent
    FOREIGN KEY (agent_id) REFERENCES agent_definition (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- -----------------------------------------------------
-- 表: extension_definition
-- 描述: 扩展定义表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS extension_definition (
  id VARCHAR(64) NOT NULL COMMENT '扩展 ID',
  name VARCHAR(128) NOT NULL COMMENT '扩展名称',
  type VARCHAR(64) NOT NULL COMMENT '扩展类型',
  version VARCHAR(64) NULL COMMENT '版本',
  source VARCHAR(256) NULL COMMENT '来源',
  config_json JSON NOT NULL COMMENT '扩展配置（JSON）',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  created_at DATETIME(3) NOT NULL COMMENT '创建时间',
  updated_at DATETIME(3) NOT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  INDEX idx_extension_type (type),
  INDEX idx_extension_enabled (enabled),
  INDEX idx_extension_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扩展定义表';

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
-- 表: agent_chat_logs
-- 描述: 对话日志表（可选，用于审计和调试）
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
-- 表: agent_user_files
-- 描述: 用户文件表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_user_files (
    id VARCHAR(36) PRIMARY KEY COMMENT '文件ID',
    user_id VARCHAR(100) NOT NULL COMMENT '用户ID',
    file_name VARCHAR(255) NOT NULL COMMENT '文件名',
    file_type VARCHAR(50) NOT NULL COMMENT '文件类型 (image|audio|video|document)',
    file_path VARCHAR(500) NOT NULL COMMENT '文件存储路径',
    file_size BIGINT NOT NULL COMMENT '文件大小（字节）',
    mime_type VARCHAR(100) COMMENT 'MIME类型',
    content_extracted BOOLEAN DEFAULT FALSE COMMENT '内容是否已提取',
    vectorized BOOLEAN DEFAULT FALSE COMMENT '是否已向量化',
    content TEXT COMMENT '提取的文本内容',
    metadata JSON COMMENT '元数据（JSON格式）',
    processing_mode VARCHAR(20) COMMENT '图像处理模式 (ocr|feature)',
    image_type VARCHAR(50) COMMENT '图像类型 (face|uniform|document|general)',
    feature_dimension INT COMMENT '特征向量维度',
    feature_model VARCHAR(100) COMMENT '特征提取模型名称',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_user_id (user_id),
    INDEX idx_file_type (file_type),
    INDEX idx_processing_mode (processing_mode),
    INDEX idx_image_type (image_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户文件表';

-- -----------------------------------------------------
-- 表: agent_node_command_audit
-- 描述: 节点命令审计日志表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_node_command_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    request_id VARCHAR(100) COMMENT '请求ID',
    operator_id VARCHAR(100) NOT NULL COMMENT '操作人ID',
    target_client_id VARCHAR(100) NOT NULL COMMENT '目标节点ID',
    target_node_type VARCHAR(20) COMMENT '节点类型(desktop/server)',
    command_type VARCHAR(50) NOT NULL COMMENT '命令类型(execute/list-dir/read-file/write-file)',
    command_text TEXT NOT NULL COMMENT '命令内容',
    safety_level VARCHAR(20) NOT NULL COMMENT '安全级别(safe/warning/dangerous/blocked)',
    status VARCHAR(20) NOT NULL COMMENT '执行状态(sent/confirmed/blocked/executed/failed)',
    confirmed TINYINT(1) DEFAULT 0 COMMENT '是否经过确认',
    result_summary TEXT COMMENT '执行结果摘要',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_operator_id (operator_id),
    INDEX idx_target_client_id (target_client_id),
    INDEX idx_safety_level (safety_level),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点命令审计日志';

-- -----------------------------------------------------
-- 表: agent_session_summaries
-- 描述: 会话摘要表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session_summaries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '摘要ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    agent_name VARCHAR(128) COMMENT 'Agent名称',
    user_id VARCHAR(128) COMMENT '用户ID',
    summary TEXT COMMENT '会话摘要',
    summary_type VARCHAR(32) COMMENT '摘要类型',
    keywords JSON COMMENT '关键词列表(JSON)',
    token_count INT COMMENT 'Token数量',
    turn_count INT COMMENT '对话轮数',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_agent_name (agent_name),
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话摘要表';

-- -----------------------------------------------------
-- 表: agent_session_tags
-- 描述: 会话标签表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_session_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '标签ID',
    conversation_id VARCHAR(64) NOT NULL COMMENT '会话ID',
    agent_name VARCHAR(128) COMMENT 'Agent名称',
    user_id VARCHAR(128) COMMENT '用户ID',
    tag_type VARCHAR(64) COMMENT '标签类型',
    tag_name VARCHAR(128) COMMENT '标签名',
    tag_value TEXT COMMENT '标签值',
    created_at DATETIME(3) DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_tag_type (tag_type),
    INDEX idx_tag_name (tag_name),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话标签表';

-- -----------------------------------------------------
-- 表: agent_node_profile
-- 描述: 节点画像表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_node_profile (
    client_id VARCHAR(100) PRIMARY KEY COMMENT '节点客户端ID',
    user_id VARCHAR(100) COMMENT '关联用户ID',
    node_type VARCHAR(20) COMMENT '节点类型(desktop/server)',
    tags JSON COMMENT '节点标签',
    os_info JSON COMMENT '操作系统信息',
    hardware JSON COMMENT '硬件信息',
    network JSON COMMENT '网络信息(hostname/localIp)',
    services JSON COMMENT '运行中服务列表(含manageType/configPath/logPath)',
    software JSON COMMENT '已安装软件(desktop only)',
    common_paths JSON COMMENT '常用路径(desktop only)',
    cloud_info JSON COMMENT '云服务商信息(provider/region/instanceId)',
    cloud_managed_services JSON COMMENT '云托管服务列表',
    last_collected_at DATETIME COMMENT '最后采集时间',
    last_online_at DATETIME COMMENT '最后在线时间',
    is_online BOOLEAN DEFAULT FALSE COMMENT '是否在线',
    INDEX idx_user_id (user_id),
    INDEX idx_node_type (node_type),
    INDEX idx_is_online (is_online),
    INDEX idx_last_online_at (last_online_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点画像表';

-- -----------------------------------------------------
-- 表: mcp_database_config
-- 描述: MCP 多数据库配置表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS mcp_database_config (
    id VARCHAR(64) PRIMARY KEY COMMENT '数据库配置ID',
    db_key VARCHAR(64) UNIQUE NOT NULL COMMENT '逻辑库标识（如 nutrition/finance）',
    display_name VARCHAR(128) COMMENT '显示名称',
    host VARCHAR(255) NOT NULL COMMENT '数据库主机',
    port INT DEFAULT 3306 COMMENT '端口',
    database_name VARCHAR(128) NOT NULL COMMENT '数据库名',
    username VARCHAR(128) NOT NULL COMMENT '用户名',
    password VARCHAR(255) NOT NULL COMMENT '密码',
    enabled TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP 多数据库配置表';

-- ============================================================
-- 第二部分: 规则引擎表
-- ============================================================

-- -----------------------------------------------------
-- 表: agent_rule
-- 描述: 规则定义表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    name VARCHAR(100) NOT NULL COMMENT '规则名称（唯一标识）',
    description VARCHAR(500) COMMENT '规则描述',
    type VARCHAR(20) NOT NULL COMMENT '规则类型：PRE/RUNTIME/POST',
    priority INT DEFAULT 50 COMMENT '优先级（1-100，数字越大优先级越高）',
    condition_expression TEXT COMMENT '条件表达式（SpEL格式，可选）',
    action_expression TEXT COMMENT '动作表达式（SpEL格式，可选）',
    rule_class VARCHAR(255) COMMENT '规则实现类全限定名（Java类方式）',
    enabled TINYINT DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    created_by VARCHAR(100) COMMENT '创建人',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_by VARCHAR(100) COMMENT '更新人',
    updated_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_name (name),
    INDEX idx_type (type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent规则定义表';

-- -----------------------------------------------------
-- 表: agent_rule_execution_log
-- 描述: 规则执行日志表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_rule_execution_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    rule_name VARCHAR(100) NOT NULL COMMENT '规则名称',
    agent_id VARCHAR(100) COMMENT 'Agent ID',
    task_id VARCHAR(100) COMMENT '任务ID',
    user_id VARCHAR(100) COMMENT '用户ID',
    username VARCHAR(100) COMMENT '用户名',
    triggered TINYINT DEFAULT 0 COMMENT '是否触发：0-未触发，1-触发',
    passed TINYINT DEFAULT 1 COMMENT '是否通过：0-拒绝，1-通过',
    error_message TEXT COMMENT '错误信息',
    execution_time BIGINT COMMENT '执行耗时（毫秒）',
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_rule_name (rule_name),
    INDEX idx_agent_id (agent_id),
    INDEX idx_task_id (task_id),
    INDEX idx_user_id (user_id),
    INDEX idx_created_time (created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则执行日志表';

-- ============================================================
-- 第三部分: 初始化数据
-- ============================================================

INSERT INTO agent_tool_configs (tool_name, enabled, description, config) VALUES
('http_request', TRUE, 'HTTP 请求工具', '{"timeout": 30, "maxRedirects": 5}'),
('calculator', TRUE, '计算器工具', '{"precision": 10}'),
('database_query', FALSE, '数据库查询工具', '{"limit": 100}')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

INSERT INTO agent_rule (name, description, type, priority, enabled, created_by)
VALUES
('permission-check', '检查用户是否有权限执行当前任务', 'PRE', 100, 1, 'system'),
('resource-limit', '检查系统资源是否充足（实例数、并发任务、内存）', 'PRE', 75, 1, 'system'),
('timeout-control', '监控任务执行时间，超时自动中断', 'RUNTIME', 75, 1, 'system'),
('audit-log', '记录所有Agent操作审计日志', 'POST', 1, 1, 'system')
ON DUPLICATE KEY UPDATE enabled = VALUES(enabled);

-- ============================================================
-- 第四部分: 视图
-- ============================================================

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

CREATE OR REPLACE VIEW v_rule_statistics AS
SELECT
    r.name AS rule_name,
    r.type AS rule_type,
    r.priority,
    r.enabled,
    COUNT(l.id) AS total_executions,
    SUM(CASE WHEN l.triggered = 1 THEN 1 ELSE 0 END) AS trigger_count,
    SUM(CASE WHEN l.passed = 0 THEN 1 ELSE 0 END) AS rejection_count,
    AVG(l.execution_time) AS avg_execution_time_ms
FROM agent_rule r
LEFT JOIN agent_rule_execution_log l ON r.name = l.rule_name
GROUP BY r.id, r.name, r.type, r.priority, r.enabled;

SELECT 'yunxi Agent Platform 数据库初始化完成！' AS status;