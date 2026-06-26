package io.yunxi.platform.agent.config;

import org.springframework.context.annotation.Configuration;

/**
 * Agent Session 配置入口（V2.0-RC3: 由 AgentStateStore / DistributedStore 替代）。
 *
 * <p>
 * 根据 {@code agentscope.core.session.type} 决定使用哪种持久化后端：
 * <ul>
 * <li>{@code workspace} — 本地文件存储（默认，通过 HarnessAgent WorkspaceManager 自动管理）</li>
 * <li>{@code redis} — 分布式后端，需要 {@code agentscope-extensions-redis}，由
 * {@link RedisDistributedBackendConfig} 处理</li>
 * </ul>
 * </p>
 *
 * <p>
 * V2.0-RC3: Session 体系已完全迁移到 AgentStateStore / DistributedStore 接口。
 * 旧 {@code RedisSessionConfig} 已删除，改用 {@link RedisDistributedBackendConfig}。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
public class AgentSessionConfig {
}
