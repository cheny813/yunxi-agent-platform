package io.yunxi.platform.business.rule;

import io.yunxi.agent.rule.core.RuleDefinition;
import io.yunxi.agent.rule.spi.RuleDefinitionProvider;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 权限规则提供者
 *
 * <p>
 * 提供权限检查规则，注册到规则引擎中。
 * 优先级设置为 10（高优先级），确保在大多数规则之前执行。
 * </p>
 */
@Component
public class PermissionRuleProvider implements RuleDefinitionProvider {

    @Override
    public List<RuleDefinition> getRuleDefinitions() {
        return Collections.singletonList(new PermissionRule());
    }

    @Override
    public int getPriority() {
        return 10; // 高优先级，确保权限检查先执行
    }

    @Override
    public String getProviderName() {
        return "BusinessPermissionRuleProvider";
    }
}
