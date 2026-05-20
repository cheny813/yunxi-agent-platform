package io.yunxi.agent.rule.config;

import io.yunxi.agent.rule.builtin.AuditLogRule;
import io.yunxi.agent.rule.core.RuleEngine;
import io.yunxi.agent.rule.repository.InMemoryRuleRepository;
import io.yunxi.agent.rule.repository.RuleRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 规则引擎自动配置类
 * 
 * 自动配置规则引擎相关组件
 * 
 * @author yunxi Team
 * @since 1.0.0
 */
@Configuration
public class RuleEngineAutoConfiguration {
    
    /**
     * 内存规则仓库 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    public InMemoryRuleRepository ruleRepository() {
        return new InMemoryRuleRepository();
    }
    
    /**
     * 规则引擎核心 Bean
     */
    @Bean
    @ConditionalOnMissingBean
    public RuleEngine ruleEngine(RuleRepository ruleRepository) {
        return new RuleEngine(ruleRepository);
    }
    
    /**
     * 审计日志规则 Bean
     */
    @Bean
    public AuditLogRule auditLogRule() {
        return new AuditLogRule();
    }
}