package io.yunxi.platform.framework.agent;

import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;

import io.agentscope.core.session.Session;
import io.agentscope.core.session.redis.RedisClientAdapter;
import io.agentscope.core.session.redis.RedisSession;

/**
 * Redis 模式的 Agent Session 持久化配置。
 *
 * <p>
 * 仅在同时满足以下条件时生效：
 * <ul>
 * <li>{@code agentscope-extensions-session-redis} 在 classpath 上
 * <li>{@code agentscope.core.session.type=redis} 已配置
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.agentscope.core.session.redis.RedisClientAdapter")
@ConditionalOnProperty(name = "agentscope.core.session.type", havingValue = "redis")
public class RedisSessionConfig {

    @Bean
    public Session redisSession(RedisTemplate<String, String> redisTemplate) {
        RedisClientAdapter adapter = new RedisTemplateAdapter(redisTemplate);
        return RedisSession.builder()
                .clientAdapter(adapter)
                .build();
    }

    /** 将 Spring RedisTemplate 适配为 agentscope 的 RedisClientAdapter */
    static class RedisTemplateAdapter implements RedisClientAdapter {
        private final RedisTemplate<String, String> rt;

        RedisTemplateAdapter(RedisTemplate<String, String> rt) {
            this.rt = rt;
        }

        public void set(String key, String value) {
            rt.opsForValue().set(key, value);
        }

        public String get(String key) {
            return rt.opsForValue().get(key);
        }

        public void rightPushList(String key, String value) {
            rt.opsForList().rightPush(key, value);
        }

        public List<String> rangeList(String key, long start, long end) {
            return rt.opsForList().range(key, start, end);
        }

        public long getListLength(String key) {
            return rt.opsForList().size(key);
        }

        public void deleteKeys(String... keys) {
            rt.delete(List.of(keys));
        }

        public void addToSet(String key, String value) {
            rt.opsForSet().add(key, value);
        }

        public Set<String> getSetMembers(String key) {
            return rt.opsForSet().members(key);
        }

        public long getSetSize(String key) {
            return rt.opsForSet().size(key);
        }

        public boolean keyExists(String key) {
            return rt.hasKey(key);
        }

        public Set<String> findKeysByPattern(String pattern) {
            return rt.keys(pattern);
        }

        public void close() {
        }
    }
}