package io.yunxi.fabric;

import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import io.yunxi.runtime.DistributedRuntimeConfig;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis 的分布式运行时后端实现。
 *
 * <p>通过 AgentScope-Java 的 {@link DistributedStore} 组合能力（mixed-stores）装配：会话状态存储用
 * 框架原生的 {@link RedisAgentStateStore}（经 {@code .lettuceClient} 对接全局 Lettuce 工厂的 native
 * RedisClient），RAG 分块共享区用 {@link YunxiRedisBaseStore}（基于全局 StringRedisTemplate）。
 *
 * <p>全局 {@code RedisConnectionFactory}（Spring Boot 默认 Lettuce，spring.data.redis.*）由会话状态存储
 * 与 RAG 共享区共用。框架 {@code RedisStore} 仅提供 {@code UnifiedJedis} 构造器、无 Lettuce 入口，
 * 故 baseStore 由 {@link YunxiRedisBaseStore} 对接全局工厂实现。</p>
 */
public class RedisDistributedRuntimeConfig implements DistributedRuntimeConfig {

    private final DistributedStore distributedStore;
    private final SessionTurnGate sessionTurnGate;
    private final BaseStore sharedChunkStore;
    private final RedisSandboxExecutionGuard sandboxGuard;

    /**
     * 构建分布式运行时后端：全部对接全局共享 Redis 模板（由全局 LettuceConnectionFactory 支撑），
     * 不引入任何 yunxi 自管的第二份 Redis 客户端。
     *
     * @param redisTemplate 全局共享 StringRedisTemplate（必须挂载在全局 LettuceConnectionFactory 上）
     * @param keyPrefix Redis 键前缀
     */
    public RedisDistributedRuntimeConfig(StringRedisTemplate redisTemplate, String keyPrefix) {
        RedisConnectionFactory cf = redisTemplate.getConnectionFactory();
        if (!(cf instanceof LettuceConnectionFactory lcf)) {
            throw new IllegalStateException(
                    "RedisDistributedRuntimeConfig requires a global LettuceConnectionFactory, but got: "
                            + (cf == null ? "null" : cf.getClass().getName()));
        }
        AbstractRedisClient nativeClient = lcf.getNativeClient();
        RedisAgentStateStore agentStateStore;
        if (nativeClient instanceof RedisClient rc) {
            agentStateStore = RedisAgentStateStore.builder().lettuceClient(rc).keyPrefix(keyPrefix).build();
        } else if (nativeClient instanceof RedisClusterClient rcc) {
            agentStateStore =
                    RedisAgentStateStore.builder().lettuceClusterClient(rcc).keyPrefix(keyPrefix).build();
        } else {
            throw new IllegalStateException(
                    "Unsupported Lettuce native client: " + nativeClient.getClass().getName());
        }

        BaseStore baseStore = new YunxiRedisBaseStore(redisTemplate, keyPrefix);
        this.sandboxGuard = new RedisSandboxExecutionGuard(redisTemplate, keyPrefix + "sandbox:lock:");
        this.distributedStore = DistributedStore.builder()
                .agentStateStore(agentStateStore)
                .baseStore(baseStore)
                .sandboxExecutionGuard(this.sandboxGuard)
                .build();
        this.sessionTurnGate = new RedisSessionTurnGate(redisTemplate, keyPrefix);
        this.sharedChunkStore = baseStore;
    }

    /** 返回组合式分布式存储：agentStateStore=全局 Lettuce 的 RedisAgentStateStore，baseStore=yunxi 自实现。 */
    @Override
    public DistributedStore distributedStore() {
        return distributedStore;
    }

    /** 返回 Redis 跨实例轮次闸门（多副本同会话串行化），复用全局工厂。 */
    @Override
    public SessionTurnGate sessionTurnGate() {
        return sessionTurnGate;
    }

    /** 复用全局工厂承载 RAG 分块（yunxi 自实现 YunxiRedisBaseStore）。 */
    @Override
    public BaseStore sharedChunkStore() {
        return sharedChunkStore;
    }

    /** 返回跨实例沙箱执行守卫（基于全局 Lettuce 的 Redis 分布式锁），防止多副本 last-write-wins 覆盖。 */
    @Override
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return sandboxGuard;
    }
}
