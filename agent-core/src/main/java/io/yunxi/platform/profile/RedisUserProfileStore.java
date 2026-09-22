package io.yunxi.platform.profile;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.yunxi.platform.shared.codec.RedisTemplates;
import io.yunxi.platform.shared.codec.RedisValueCodec;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * UserProfile 存储：优先 Redis（多实例共享），无 Redis 时回退进程内 Map（开发/单测）。
 *
 * <p>编解码统一交由 {@link RedisValueCodec}。存储通道为明文字符串：画像需要能被外部工具
 * 直接查看与修复，明文值是这个数据面的既定契约。</p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class RedisUserProfileStore {

    private static final String KEY_PREFIX = "yunxi:user-profile:";
    private static final Duration TTL = Duration.ofDays(180);

    private final StringRedisTemplate redisTemplate;
    private final RedisValueCodec codec;
    private final boolean redisEnabled;
    private final Map<String, UserProfile> memoryFallback = new ConcurrentHashMap<>();

    /**
     * 构造画像存储。
     *
     * <p>连接工厂由容器提供；缺失时（开发/单测）退回进程内映射。此处不新建连接工厂，
     * 连接参数只允许有一个来源。</p>
     *
     * @param factoryProvider 连接工厂提供者
     * @param codec           统一编解码器
     */
    public RedisUserProfileStore(ObjectProvider<RedisConnectionFactory> factoryProvider,
                                 RedisValueCodec codec) {
        this.codec = codec;
        this.redisTemplate = RedisTemplates.stringTemplate(factoryProvider.getIfAvailable());
        this.redisEnabled = this.redisTemplate != null;
    }

    /**
     * 读取用户画像。
     *
     * @param userId 用户标识
     * @return 画像；不存在或解码失败返回 null
     */
    public UserProfile getProfile(String userId) {
        if (userId == null) {
            return null;
        }
        if (redisEnabled) {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            return codec.read(json, UserProfile.class, RedisValueCodec.SCENE_PROFILE);
        }
        return memoryFallback.get(userId);
    }

    /**
     * 保存用户画像。
     *
     * @param userId  用户标识
     * @param profile 画像
     */
    public void saveProfile(String userId, UserProfile profile) {
        if (userId == null || profile == null) {
            return;
        }
        if (redisEnabled) {
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX + userId,
                        codec.write(profile, RedisValueCodec.SCENE_PROFILE), TTL);
            } catch (Exception ignored) {
                // 存储故障不阻断对话执行
            }
        } else {
            memoryFallback.put(userId, profile);
        }
    }

    /**
     * 是否使用 Redis 存储。
     *
     * @return 使用 Redis 返回 true
     */
    public boolean isRedisEnabled() {
        return redisEnabled;
    }
}
