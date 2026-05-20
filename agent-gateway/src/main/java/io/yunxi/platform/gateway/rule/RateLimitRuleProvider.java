package io.yunxi.platform.gateway.rule;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import io.yunxi.agent.rule.spi.RuleDefinitionProvider;

/**
 * 速率限制规则提供者
 * 
 * <p>
 * 功能：提供API网关的速率限制规则实现，通过实现RuleDefinitionProvider接口
 * 将RateLimitRule规则注册到规则引擎中，优先级设置为5（中等优先级）。
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @since 2024-01-01
 */
@Component
public class RateLimitRuleProvider implements RuleDefinitionProvider {

    /** 速率限制规则实例 */
    private final RateLimitRule rateLimitRule;

    /**
     * 构造函数，通过依赖注入获取RateLimitRule实例
     * 
     * @param rateLimitRule 速率限制规则实例
     */
    public RateLimitRuleProvider(RateLimitRule rateLimitRule) {
        this.rateLimitRule = rateLimitRule;
    }

    /**
     * 获取规则定义列表
     * 
     * @return 包含速率限制规则的单一元素列表
     */
    @Override
    public List getRuleDefinitions() {
        return Collections.singletonList(rateLimitRule);
    }

    /**
     * 获取提供者优先级
     * 
     * @return 优先级数值（5-中等优先级）
     */
    @Override
    public int getPriority() {
        return 5;
    }

    /**
     * 获取提供者名称
     * 
     * @return 提供者唯一标识名称
     */
    @Override
    public String getProviderName() {
        return "GatewayRateLimitRuleProvider";
    }
}
