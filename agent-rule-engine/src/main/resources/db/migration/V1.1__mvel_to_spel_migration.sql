-- ========================================
-- V1.1: 将 MVEL 表达式迁移为 SpEL 格式
-- 修复 CVE-2023-50571（MVEL 远程代码执行漏洞）
-- ========================================

-- 更新列注释
ALTER TABLE `agent_rule`
    MODIFY COLUMN `condition_expression` TEXT COMMENT 'SpEL条件表达式',
    MODIFY COLUMN `action_expression` TEXT COMMENT 'SpEL动作表达式';

-- 更新示例规则：禁止非管理员执行危险技能
-- MVEL: context.userInfo.roles.contains('ADMIN') == false && ...
-- SpEL: #facts.get('userInfo').roles.contains('ADMIN') == false && ...
UPDATE `agent_rule`
SET `condition_expression` = '#facts.get(''userInfo'').roles.contains(''ADMIN'') == false && {''skill:delete-agent'', ''skill:modify-system''}.contains(#facts.get(''taskInfo'').skillName)',
    `action_expression` = '#facts.put(''errorMessage'', ''非管理员禁止执行该技能'')'
WHERE `name` = 'forbidden-skill-check';

-- 更新示例规则：调用频率限制
-- MVEL: rateLimitService.getCallCount(context.userInfo.userId) > 100
-- SpEL: #facts.get('rateLimitService').getCallCount(#facts.get('userInfo').userId) > 100
UPDATE `agent_rule`
SET `condition_expression` = '#facts.get(''rateLimitService'').getCallCount(#facts.get(''userInfo'').userId) > 100',
    `action_expression` = '#facts.put(''errorMessage'', ''调用频率超限，请稍后重试'')'
WHERE `name` = 'rate-limit-check';
