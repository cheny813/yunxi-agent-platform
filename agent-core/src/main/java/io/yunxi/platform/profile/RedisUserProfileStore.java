package io.yunxi.platform.profile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Service;

import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;

/**
 * UserProfile 存储：优先 Redis（多实例共享），无 Redis 时回退进程内 Map（开发/单测）。
 *
 * <p>SPI 结构类 {@link UserProfile} 是无默认构造器的嵌套类，直接交给 GenericJackson 反序列化会失败，
 * 故这里手写 JSON 编解码，只搬运其字段，避免改动 agent-spi 既定义约。</p>
 */
@Service
public class RedisUserProfileStore {

    private static final String KEY_PREFIX = "yunxi:user-profile:";
    private static final Duration TTL = Duration.ofDays(180);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;
    private final boolean redisEnabled;
    private final Map<String, UserProfile> memoryFallback = new ConcurrentHashMap<>();

    public RedisUserProfileStore(ObjectProvider<RedisConnectionFactory> factoryProvider) {
        RedisConnectionFactory factory = factoryProvider.getIfAvailable();
        if (factory != null) {
            RedisTemplate<String, String> tpl = new RedisTemplate<>();
            tpl.setConnectionFactory(factory);
            tpl.setKeySerializer(StringRedisSerializer.UTF_8);
            tpl.setValueSerializer(StringRedisSerializer.UTF_8);
            tpl.afterPropertiesSet();
            this.redisTemplate = tpl;
            this.redisEnabled = true;
        } else {
            this.redisTemplate = null;
            this.redisEnabled = false;
        }
    }

    public UserProfile getProfile(String userId) {
        if (userId == null) {
            return null;
        }
        if (redisEnabled) {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + userId);
            if (json == null) {
                return null;
            }
            try {
                return fromJson(json);
            } catch (Exception ignored) {
                return null;
            }
        }
        return memoryFallback.get(userId);
    }

    public void saveProfile(String userId, UserProfile profile) {
        if (userId == null || profile == null) {
            return;
        }
        if (redisEnabled) {
            try {
                redisTemplate.opsForValue().set(KEY_PREFIX + userId, toJson(profile), TTL);
            } catch (Exception ignored) {
                // 存储故障不阻断对话执行
            }
        } else {
            memoryFallback.put(userId, profile);
        }
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    private String toJson(UserProfile p) throws Exception {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("userId", p.getUserId());
        root.put("updatedAt", p.getUpdatedAt());
        root.put("personalContext", p.getPersonalContext());
        root.put("socialRelations", p.getSocialRelations());
        List<Map<String, Object>> ids = new ArrayList<>();
        if (p.getIdentities() != null) {
            for (Identity id : p.getIdentities()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", id.getName());
                m.put("displayName", id.getDisplayName());
                m.put("category", id.getCategory());
                m.put("confidence", id.getConfidence());
                m.put("keywords", id.getKeywords());
                m.put("attributes", id.getAttributes());
                ids.add(m);
            }
        }
        root.put("identities", ids);
        return MAPPER.writeValueAsString(root);
    }

    @SuppressWarnings("unchecked")
    private UserProfile fromJson(String json) throws Exception {
        Map<String, Object> root = MAPPER.readValue(json, Map.class);
        List<Identity> ids = new ArrayList<>();
        Object idObj = root.get("identities");
        if (idObj instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) raw;
                    Map<String, String> attrs = parseAttributes(m.get("attributes"));
                    ids.add(new Identity(
                            str(m.get("name")), str(m.get("displayName")),
                            str(m.get("category")), dbl(m.get("confidence")),
                            (List<String>) m.getOrDefault("keywords", List.of()),
                            attrs));
                }
            }
        }
        Map<String, String> pc = (Map<String, String>) root.getOrDefault("personalContext", Map.of());
        Map<String, String> sr = (Map<String, String>) root.getOrDefault("socialRelations", Map.of());
        long updatedAt = root.get("updatedAt") instanceof Number n ? n.longValue() : System.currentTimeMillis();
        return new UserProfile(str(root.get("userId")), ids, pc, sr, updatedAt);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseAttributes(Object o) {
        if (!(o instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> m = (Map<String, Object>) raw;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            result.put(e.getKey(), e.getValue() == null ? null : e.getValue().toString());
        }
        return result;
    }
}
