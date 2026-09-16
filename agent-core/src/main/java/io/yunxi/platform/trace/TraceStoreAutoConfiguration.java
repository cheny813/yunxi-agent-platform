package io.yunxi.platform.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
    @ConditionalOnProperty(
            name = "conversation.trace-storage-type", havingValue = "redis", matchIfMissing = true)
    @ConditionalOnBean(RedisConnectionFactory.class)
    @ConditionalOnMissingBean(TraceStore.class)
    public TraceStore redisTraceStore(RedisConnectionFactory connectionFactory) {
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

    @Bean
    @ConditionalOnMissingBean(TraceStore.class)
    public TraceStore inMemoryTraceStore() {
        log.warn("TraceStore 装配: InMemoryTraceStore (内存) 已创建");
        return new InMemoryTraceStore();
    }
}
