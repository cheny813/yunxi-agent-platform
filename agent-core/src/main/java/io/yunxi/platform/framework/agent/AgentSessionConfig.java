package io.yunxi.platform.framework.agent;

import org.springframework.context.annotation.Configuration;

/**
 * Agent Session 持久化配置。
 *
 * <p>
 * 根据 {@code agentscope.core.session.type} 自动选择后端：
 * <ul>
 * <li>{@code workspace}（默认）— 文件系统，无需额外依赖
 * <li>{@code redis} — 跨实例共享，需 {@code spring-boot-starter-data-redis}
 * </ul>
 *
 * <p>
 * Redis 模式的实现在单独的 {@link RedisSessionConfig} 中，
 * 由 {@code @ConditionalOnClass} + {@code @ConditionalOnProperty} 控制加载。
 */
@Configuration(proxyBeanMethods = false)
public class AgentSessionConfig {
}
