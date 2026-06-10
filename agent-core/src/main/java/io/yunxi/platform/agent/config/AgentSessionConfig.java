package io.yunxi.platform.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * Agent Session 配置入口。
 *
 * <p>
 * 根据 {@code agentscope.core.session.type} 决定使用哪种会话实现：
 * <ul>
 * <li>{@code workspace} — 本地文件存储（默认，无需额外依赖）</li>
 * <li>{@code redis} — 分布式会话，需要 {@code spring-boot-starter-data-redis}</li>
 * </ul>
 * </p>
 *
 * <p>
 * Redis 配置由 {@link RedisSessionConfig} 处理，
 * 通过 {@code @ConditionalOnClass} + {@code @ConditionalOnProperty} 自动激活。
 * 当不满足 Redis 条件时，AgentScope 框架自动使用 Workspace 本地会话。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentSessionConfig {
}
