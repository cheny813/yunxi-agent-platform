-- 测试数据
-- 插入预定义的测试规则

-- 前置检查规则（PRE）
INSERT INTO rule_definition (name, description, condition_expression, action_expression, phase, priority) 
VALUES 
('pre-rule-1', '权限校验规则', "#request.hasPermission('READ')", "#log.info('权限校验通过')", 'PRE', 10),
('pre-rule-2', '参数校验规则', "#request.param != null and #request.param.length() > 0", "#context.setValidated(true)", 'PRE', 20);

-- 运行时规则（RUNTIME）
INSERT INTO rule_definition (name, description, condition_expression, action_expression, phase, priority) 
VALUES 
('runtime-rule-1', '限流规则', "#rateLimiter.tryAcquire()", "#metrics.increment('requests')", 'RUNTIME', 30),
('runtime-rule-2', '缓存规则', "#cache.get(#key) == null", "#cache.put(#key, #result)", 'RUNTIME', 40),
('runtime-rule-3', '监控规则', "true", "#metrics.recordExecutionTime(#startTime)", 'RUNTIME', 50);

-- 后置处理规则（POST）
INSERT INTO rule_definition (name, description, condition_expression, action_expression, phase, priority) 
VALUES 
('post-rule-1', '审计日志规则', "#result != null", "#auditLogger.log(#request, #result)", 'POST', 60),
('post-rule-2', '结果处理规则', "#result instanceof java.util.Map", "#result.remove('sensitive')", 'POST', 70);

-- 插入规则组数据
INSERT INTO rule_group (name, description) 
VALUES 
('validation-group', '校验相关规则组'),
('security-group', '安全相关规则组'),
('performance-group', '性能优化规则组');

-- 插入规则与组关联数据
INSERT INTO rule_group_mapping (rule_id, group_id) 
SELECT rd.id, rg.id 
FROM rule_definition rd, rule_group rg 
WHERE rd.phase = 'PRE' AND rg.name = 'validation-group';

INSERT INTO rule_group_mapping (rule_id, group_id) 
SELECT rd.id, rg.id 
FROM rule_definition rd, rule_group rg 
WHERE rd.name LIKE '%security%' AND rg.name = 'security-group';

INSERT INTO rule_group_mapping (rule_id, group_id) 
SELECT rd.id, rg.id 
FROM rule_definition rd, rule_group rg 
WHERE rd.name LIKE '%monitor%' OR rd.name LIKE '%cache%' OR rd.name LIKE '%rate%' AND rg.name = 'performance-group';