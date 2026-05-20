-- 测试数据库模式
-- 创建规则相关表结构

-- 规则定义表
CREATE TABLE IF NOT EXISTS rule_definition (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    condition_expression VARCHAR(1000) NOT NULL,
    action_expression VARCHAR(1000) NOT NULL,
    phase VARCHAR(50) NOT NULL, -- PRE, RUNTIME, POST
    priority INT DEFAULT 0,
    enabled BOOLEAN DEFAULT TRUE,
    created_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 规则执行日志表
CREATE TABLE IF NOT EXISTS rule_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_name VARCHAR(255) NOT NULL,
    execution_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    success BOOLEAN NOT NULL,
    error_message TEXT,
    execution_duration_ms BIGINT,
    input_params TEXT,
    output_result TEXT
);

-- 规则组表
CREATE TABLE IF NOT EXISTS rule_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL UNIQUE,
    description TEXT,
    enabled BOOLEAN DEFAULT TRUE
);

-- 规则与组关联表
CREATE TABLE IF NOT EXISTS rule_group_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    FOREIGN KEY (rule_id) REFERENCES rule_definition(id),
    FOREIGN KEY (group_id) REFERENCES rule_group(id),
    UNIQUE KEY uk_rule_group (rule_id, group_id)
);

-- 索引创建
CREATE INDEX idx_rule_phase ON rule_definition(phase);
CREATE INDEX idx_rule_enabled_phase ON rule_definition(enabled, phase);
CREATE INDEX idx_execution_time ON rule_execution_log(execution_time);
CREATE INDEX idx_rule_name ON rule_execution_log(rule_name);