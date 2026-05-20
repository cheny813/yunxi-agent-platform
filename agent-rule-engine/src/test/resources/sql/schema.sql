-- 规则引擎测试数据库表结构初始化
-- H2数据库兼容版本

-- 规则表
CREATE TABLE IF NOT EXISTS rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    content TEXT NOT NULL,
    version INT DEFAULT 1,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    PRIMARY KEY (id),
    CONSTRAINT uk_rule_name_version UNIQUE (name, version)
);

-- 规则执行日志表
CREATE TABLE IF NOT EXISTS rule_execution_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    rule_name VARCHAR(255) NOT NULL,
    input_data TEXT,
    output_data TEXT,
    execution_time BIGINT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'SUCCESS',
    error_message TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100),
    PRIMARY KEY (id)
);

-- 单独创建索引（H2兼容语法）
CREATE INDEX IF NOT EXISTS idx_rule_execution_log_rule_id ON rule_execution_log(rule_id);
CREATE INDEX IF NOT EXISTS idx_rule_execution_log_created_at ON rule_execution_log(created_at);