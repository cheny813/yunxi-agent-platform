package io.yunxi.platform.business.rule;

import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.spi.RuleDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 审计日志规则提供者
 *
 * <p>
 * 提供审计日志规则，注册到规则引擎中。
 * 优先级设置为 100（低优先级），确保在 POST 规则中最后执行。
 * </p>
 */
@Component
public class AuditLogRuleProvider implements RuleDefinitionProvider {

    private final RuleDefinition auditLogRule;

    public AuditLogRuleProvider(RuleDefinition auditLogRule) {
        this.auditLogRule = auditLogRule;
    }

    @Override
    public List<RuleDefinition> getRuleDefinitions() {
        return Collections.singletonList(auditLogRule);
    }

    /**
     * 获取审计日志规则（类型安全转换）
     *
     * @return AuditLogRule 实例
     */
    public AuditLogRule getAuditLogRule() {
        return (AuditLogRule) auditLogRule;
    }

    @Override
    public int getPriority() {
        return 100; // 低优先级，POST 规则最后执行
    }

    @Override
    public String getProviderName() {
        return "BusinessAuditLogRuleProvider";
    }
}
