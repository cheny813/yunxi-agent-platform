package io.yunxi.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import io.yunxi.platform.shared.codec.RedisTemplates;
import io.yunxi.platform.shared.codec.RedisValueCodec;

/**
 * 全局 RedisTemplate 装配。
 *
 * <p>值是全平台唯一编解码器 {@link RedisValueCodec}，键为明文。需要对象级 Redis 访问的
 * 组件注入本模板，不得自行新建模板。</p>
 *
 * <p>纯字符串访问请直接用 {@code StringRedisTemplate}。</p>
 *
 * @author yunxi-agent-platform
 */
@Configuration
@ConditionalOnProperty(name = "conversation.storage-type", havingValue = "redis")
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    /**
     * 创建 RedisTemplate Bean，键用明文、值用统一编解码器。
     *
     * @param connectionFactory Redis 连接工厂
     * @param valueCodec        统一值编解码器
     * @return 配置好的 RedisTemplate 实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                       RedisValueCodec valueCodec) {
        log.info("RedisConfig: 会话存储 Redis 模式已启用，redisTemplate 已创建（复用全局 RedisConnectionFactory，目标 Redis 实例={}）",
                RedisTemplates.endpointOf(connectionFactory));
        return RedisTemplates.objectTemplate(connectionFactory, valueCodec);
    }
}
