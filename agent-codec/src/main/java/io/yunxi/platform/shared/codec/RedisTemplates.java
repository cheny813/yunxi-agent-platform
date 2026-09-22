package io.yunxi.platform.shared.codec;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 模板的唯一构造入口。
 *
 * <p>平台只允许两种模板形态：对象级（值经 {@link RedisValueCodec} 编解码）与字符串级
 * （明文字符串直存）。需要 Redis 访问的组件一律经本类构造，不得自行 {@code new} 模板 ——
 * 复制这段构造代码是引入第二套序列化配置的入口。</p>
 *
 * <p>两个方法都在无连接工厂时返回 {@code null}，由调用方据此回退到进程内实现。这是开发与
 * 单测（不起 Redis）下的既定降级路径。</p>
 *
 * @author yunxi-agent-platform
 */
public final class RedisTemplates {

    private RedisTemplates() {
    }

    /**
     * 构造对象级模板：键明文、值经统一编解码器。
     *
     * @param connectionFactory 连接工厂，可为 null
     * @param codec             统一值编解码器
     * @return 配置好的模板；无连接工厂时返回 null
     */
    public static RedisTemplate<String, Object> objectTemplate(RedisConnectionFactory connectionFactory,
                                                               RedisValueCodec codec) {
        if (connectionFactory == null) {
            return null;
        }
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(codec);
        template.setHashValueSerializer(codec);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 构造字符串级模板：键值均为明文。
     *
     * <p>适用于纯文本数据（画像 JSON、节流计数）。若数据是对象，应改用
     * {@link #objectTemplate} 并让值经编解码器处理，不要在这里自行拼接 JSON。</p>
     *
     * @param connectionFactory 连接工厂，可为 null
     * @return 配置好的模板；无连接工厂时返回 null
     */
    public static StringRedisTemplate stringTemplate(RedisConnectionFactory connectionFactory) {
        if (connectionFactory == null) {
            return null;
        }
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 描述连接工厂指向的 Redis 实例，便于装配日志与排障时确认「是否命中了同一个实例」。
     *
     * <p>平台所有 Redis 使用方（会话存储、轨迹存储、画像、节流）共用同一全局
     * {@link RedisConnectionFactory}；打印实际 host/port 可防止运维拿错实例去查
     * （例如把应用连的 Redis 与 docker 容器 yunxi-redis 当成两个独立库）。</p>
     *
     * @param connectionFactory 连接工厂，可为 null
     * @return 形如 {@code 127.0.0.1:6379} 的端点描述；无法识别时退回类型名
     */
    public static String endpointOf(RedisConnectionFactory connectionFactory) {
        if (connectionFactory == null) {
            return "(无连接工厂)";
        }
        if (connectionFactory instanceof LettuceConnectionFactory lcf) {
            String host = lcf.getHostName();
            int port = lcf.getPort();
            if (host != null) {
                return host + ":" + port;
            }
        }
        return "(连接工厂类型=" + connectionFactory.getClass().getSimpleName() + ")";
    }
}
