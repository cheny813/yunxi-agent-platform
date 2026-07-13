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

    /**
     * 创建 AgentScope 工具箱实例。
     * <p>声明为原型作用域（prototype），每次注入都会获得一个全新的 {@link Toolkit}，
     * 避免多租户/多会话场景下工具组注册相互污染。</p>
     *
     * @return 全新的 Toolkit 实例
     */
    @Bean @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public Toolkit agentscopeToolkit() { return new Toolkit(); }

    /**
     * 模型生命周期管理器（执行顺序 0，最先启动）。
     *
     * @return 管理模型相关资源的生命周期管理器
     */
    @Bean public AgentscopeLifecycleManager agentscopeModelLifecycle() { return new AgentscopeLifecycleManager(0); }

    /**
     * 工具箱生命周期管理器（执行顺序 1）。
     *
     * @return 管理工具组相关资源的生命周期管理器
     */
    @Bean public AgentscopeLifecycleManager agentscopeToolkitLifecycle() { return new AgentscopeLifecycleManager(1); }

    /**
     * 记忆生命周期管理器（执行顺序 2）。
     *
     * @return 管理记忆相关资源的生命周期管理器
     */
    @Bean public AgentscopeLifecycleManager agentscopeMemoryLifecycle() { return new AgentscopeLifecycleManager(2); }

    /**
     * 会话生命周期管理器（执行顺序 3）。
     *
     * @return 管理会话相关资源的生命周期管理器
     */
    @Bean public AgentscopeLifecycleManager agentscopeSessionLifecycle() { return new AgentscopeLifecycleManager(3); }

    /**
     * 智能体生命周期管理器（执行顺序 4，最后启动）。
     *
     * @return 管理智能体相关资源的生命周期管理器
     */
    @Bean public AgentscopeLifecycleManager agentscopeAgentLifecycle() { return new AgentscopeLifecycleManager(4); }
}
