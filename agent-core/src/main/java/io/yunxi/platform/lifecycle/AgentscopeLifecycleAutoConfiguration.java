package io.yunxi.platform.lifecycle;

import io.agentscope.core.tool.Toolkit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

/**
 * AgentScope 生命周期自动配置
 * <p>注册有序的生命周期管理器 Bean。</p>
 *
 * @author yunxi-agent-platform
 */
@Configuration(proxyBeanMethods = false)
public class AgentscopeLifecycleAutoConfiguration {

    @Bean @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit agentscopeToolkit() { return new Toolkit(); }

    @Bean public AgentscopeLifecycleManager agentscopeModelLifecycle() { return new AgentscopeLifecycleManager(0); }
    @Bean public AgentscopeLifecycleManager agentscopeToolkitLifecycle() { return new AgentscopeLifecycleManager(1); }
    @Bean public AgentscopeLifecycleManager agentscopeMemoryLifecycle() { return new AgentscopeLifecycleManager(2); }
    @Bean public AgentscopeLifecycleManager agentscopeSessionLifecycle() { return new AgentscopeLifecycleManager(3); }
    @Bean public AgentscopeLifecycleManager agentscopeAgentLifecycle() { return new AgentscopeLifecycleManager(4); }
}
