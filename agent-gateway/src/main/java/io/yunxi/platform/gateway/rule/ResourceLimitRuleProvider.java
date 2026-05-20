package io.yunxi.platform.gateway.rule;

import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.spi.RuleDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 资源限制规则提供者
 *
 * <p>
 * 提供资源限制规则，注册到规则引擎中。
 * 优先级设置为 1（最高优先级），确保在网关层最先执行。
 * </p>
 */
@Component
public class ResourceLimitRuleProvider implements RuleDefinitionProvider {

    private final ResourceLimitRule resourceLimitRule;

    public ResourceLimitRuleProvider(ResourceLimitRule resourceLimitRule) {
        this.resourceLimitRule = resourceLimitRule;
    }

    @Override
    public List<RuleDefinition> getRuleDefinitions() {
        return Collections.singletonList(resourceLimitRule);
    }

    @Override
    public int getPriority() {
        return 1; // 最高优先级，在 RateLimit 之前
    }

    @Override
    public String getProviderName() {
        return "GatewayResourceLimitRuleProvider";
    }
}
