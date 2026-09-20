package io.yunxi.platform.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * {@link TraceStore} 的统一自动装配。
 *
 * <p>把 Redis 实现与内存实现放在同一个配置类里，确保两者的装配顺序确定：
 * 先尝试注册 Redis 实现（跨实例可见），仅当 Redis 实现不存在时才回退到内存实现。
 * 跨类拆分两个 {@code @Configuration} 会让 {@code @ConditionalOnMissingBean} 的评估时机
 * 依赖配置类的处理顺序，而组件扫描对嵌套配置类的发现顺序并不保证，导致 Redis 实现
 * 在某些部署下永不装配。</p>
 *
 * <p><b>Redis 可用性判定时机</b>：本类是普通 {@code @Configuration}（经组件扫描加载），
 * 其处理早于 Spring Boot 的自动配置阶段，因此 {@code RedisConnectionFactory}（由
 * {@code RedisAutoConfiguration} 创建）在本类被解析时尚不存在。若用
 * {@code @ConditionalOnBean(RedisConnectionFactory.class)} 在注册期判定，会恒为 false，
 * 误回退到内存实现。故改在 <b>bean 创建期</b>通过 {@link ObjectProvider#getIfAvailable()}
 * 取 {@code RedisConnectionFactory}：此时所有 bean 定义（含自动配置）已注册，能正确取到；
 * 取不到（未配 Redis）则方法返回 {@code null}，Spring 不注册该 bean，由内存实现兜底。</p>
 *
 * <p><b>连接来源</b>：直接复用全局自动装配的 {@link RedisConnectionFactory}
 * （由 {@code config/redis.yml} 的 {@code spring.data.redis.*} 提供连接参数），
 * 本类不再自建连接工厂。自建工厂曾导致“轨迹连的库与全局不一致、密码取自读不到的前缀”
 * 这类难查问题 —— 连接参数只允许有一个来源。</p>
 *
 * <p>仅序列化器需要在本类指定：轨迹键用明文 {@link StringRedisSerializer}，
 * 以便与外部工具注入的合成轨迹格式一致；值用 {@link GenericJackson2JsonRedisSerializer}。</p>
 */
@Configuration(proxyBeanMethods = false)
public class TraceStoreAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TraceStoreAutoConfiguration.class);

    /**
     * 单一工厂方法：任何配置下都恰好产出一个 TraceStore 实例，杜绝两个竞争 @Bean 在
     * 无 Redis 环境下互相抑制的缺陷——此前 redis 的 bean 定义被注册（matchIfMissing=true）
     * 但方法返回 null，导致 @ConditionalOnMissingBean 误判、InMemory 兜底失效，容器里
     * 最终没有可用的 TraceStore，造成 AgentExecutionEngine 注入失败。
     *
     * <p>选择逻辑：显式检测到 RedisConnectionFactory 且未指定 memory 时优先用 RedisTraceStore
     * （跨实例共享）；其余情况（无 Redis 或显式 memory）回退 InMemoryTraceStore，
     * 显式要求 redis 却无 Redis 时仅告警不失败。</p>
     */
    @Bean
    @ConditionalOnMissingBean(TraceStore.class)
    public TraceStore traceStore(ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
                                 Environment environment) {
        RedisConnectionFactory connectionFactory = redisConnectionFactory.getIfAvailable();
        String type = environment.getProperty("conversation.trace-storage-type", "redis");
        if (connectionFactory != null && !"memory".equalsIgnoreCase(type)) {
            RedisTemplate<String, Object> template = new RedisTemplate<>();
            template.setConnectionFactory(connectionFactory);
            template.setKeySerializer(new StringRedisSerializer());
            template.setHashKeySerializer(new StringRedisSerializer());
            template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
            template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
            template.afterPropertiesSet();
            log.warn("TraceStore 装配: RedisTraceStore (复用全局 RedisConnectionFactory) 已创建");
            return new RedisTraceStore(template);
        }
        if (connectionFactory == null && "redis".equalsIgnoreCase(type)) {
            log.warn("TraceStore 装配: 显式要求 redis 但未检测到 RedisConnectionFactory，回退 InMemoryTraceStore");
        } else {
            log.warn("TraceStore 装配: InMemoryTraceStore (内存) 已创建");
        }
        return new InMemoryTraceStore();
    }
}
