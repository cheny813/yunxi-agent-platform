package io.yunxi.agent.rule.builtin;

import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.model.RuleType;
import lombok.extern.slf4j.Slf4j;
import org.jeasy.rules.api.Facts;
import org.springframework.stereotype.Component;

/**
 * 审计日志规则
 * 
 * 自动记录规则执行过程的审计日志
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Slf4j
@Component
public class AuditLogRule implements RuleDefinition {

    @Override
    public String getName() {
        return "audit_log";
    }

    @Override
    public String getDescription() {
        return "审计日志规则 - 自动记录规则执行的审计信息";
    }

    @Override
    public RuleType getType() {
        return RuleType.RUNTIME;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean evaluate(Facts facts) {
        // 检查是否需要记录审计
        Object userId = facts.get("userId");
        Object executionTime = facts.get("executionTime");
        return userId != null && executionTime != null;
    }

    @Override
    public void execute(Facts facts) {
        // 记录审计日志
        Object userId = facts.get("userId");
        Object ruleType = facts.get("ruleType");
        Object executionTime = facts.get("executionTime");

        log.info("规则审计: 用户={}, 规则类型={}, 执行耗时={}ms", 
                userId, ruleType, executionTime);
    }
}