package io.yunxi.platform.agent.config;

import io.agentscope.extensions.redis.RedisDistributedStore;
import io.agentscope.harness.agent.DistributedStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis 分布式后端配置（AgentStateStore + BaseStore + SnapshotSpec 一站式配置）。
 *
 * <p>
 * RC2+ 框架使用 {@link DistributedStore} 统一接口收敛所有分布式存储组件：
 * <ul>
 * <li>{@link DistributedStore#agentStateStore()} — Agent 运行时状态持久化</li>
 * <li>{@link DistributedStore#baseStore()} — 工作区文件系统 KV 存储</li>
 * <li>{@link DistributedStore#sandboxSnapshotSpec()} — 沙箱快照存储</li>
 * <li>{@link DistributedStore#sandboxExecutionGuard()} — 沙箱并发锁</li>
 * </ul>
 * </p>
 *
 * <p>
 * 自动激活条件（同时满足）：
 * <ul>
 * <li>{@code agentscope-extensions-redis} 在 classpath 中</li>
 * <li>{@code agentscope.core.session.type=redis} 配置项（保留原配置语义）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>使用方式：</b>配置 {@code agentscope.core.session.type=redis} 后，
 * 需要提供一个 {@link UnifiedJedis} Bean（如 {@code JedisPooled}），
 * 此配置返回的 {@link DistributedStore} 将自动整合 stateStore + baseStore + snapshotSpec。
 * 若当前项目通过 Spring RedisTemplate 管理连接，需自行适配。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.agentscope.extensions.redis.RedisDistributedStore")
@ConditionalOnProperty(name = "agentscope.core.session.type", havingValue = "redis")
public class RedisDistributedBackendConfig {

    /**
     * 创建 Redis DistributedStore Bean。
     *
     * <p>
     * 通过 {@link RedisDistributedStore#fromJedis(UnifiedJedis)} 一行配置
     * stateStore + baseStore + snapshotSpec + executionGuard。
     * 当前项目通过 Spring RedisTemplate 管理连接，暂未提供内置 JedisPooled Bean，
     * 外部配置声明 JedisPooled Bean 后即可启用。
     * </p>
     *
     * @param jedis Jedis 统一连接实例（由外部提供）
     * @return DistributedStore 实例
     * @see RedisDistributedStore#fromJedis(UnifiedJedis)
     */
    @Bean
    public DistributedStore redisDistributedStore(UnifiedJedis jedis) {
        return RedisDistributedStore.fromJedis(jedis);
    }
}
