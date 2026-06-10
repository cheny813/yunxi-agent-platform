package io.yunxi.platform.agent.config;

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
 * Redis 分布式 Agent Session 配置。
 *
 * <p>
 * 自动激活条件（同时满足）：
 * <ul>
 * <li>{@code agentscope-extensions-session-redis} 在 classpath 中</li>
 * <li>{@code agentscope.core.session.type=redis} 配置项</li>
 * </ul>
 * </p>
 *
 * <p>
 * 当条件满足时，创建 RedisSession Bean 替代默认的本地 Workspace 会话，
 * 支持多实例部署场景下的会话共享。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.agentscope.core.session.redis.RedisClientAdapter")
@ConditionalOnProperty(name = "agentscope.core.session.type", havingValue = "redis")
public class RedisSessionConfig {

    /**
     * 创建 Redis 分布式 Session Bean。
     *
     * <p>
     * 使用 Spring RedisTemplate 适配为 agentscope 的 RedisClientAdapter，
     * 构建 RedisSession 实例。RedisSession 支持跨 JVM 的会话共享，
     * 适用于多实例部署场景。
     * </p>
     *
     * @param redisTemplate Spring Redis 模板
     * @return RedisSession 实例
     */
    @Bean
    public Session redisSession(RedisTemplate<String, String> redisTemplate) {
        RedisClientAdapter adapter = new RedisTemplateAdapter(redisTemplate);
        return RedisSession.builder()
                .clientAdapter(adapter)
                .build();
    }

    /**
     * 将 Spring RedisTemplate 适配为 agentscope 的 RedisClientAdapter。
     *
     * <p>
     * 代理所有 Redis 操作到 Spring RedisTemplate，实现 agentscope 框架
     * 与 Spring Data Redis 的无缝集成。支持 String、List、Set 三种数据结构操作。
     * </p>
     */
    static class RedisTemplateAdapter implements RedisClientAdapter {

        /** Spring Redis 模板实例 */
        private final RedisTemplate<String, String> rt;

        /**
         * 构造适配器。
         *
         * @param rt Spring RedisTemplate 实例
         */
        RedisTemplateAdapter(RedisTemplate<String, String> rt) {
            this.rt = rt;
        }

        /**
         * 设置 String 类型键值对。
         *
         * @param key   键
         * @param value 值
         */
        public void set(String key, String value) {
            rt.opsForValue().set(key, value);
        }

        /**
         * 获取 String 类型键值。
         *
         * @param key 键
         * @return 值，不存在时返回 null
         */
        public String get(String key) {
            return rt.opsForValue().get(key);
        }

        /**
         * 向 List 右侧追加元素。
         *
         * @param key   List 键
         * @param value 追加的值
         */
        public void rightPushList(String key, String value) {
            rt.opsForList().rightPush(key, value);
        }

        /**
         * 获取 List 指定范围的元素。
         *
         * @param key   List 键
         * @param start 起始索引（0 开始）
         * @param end   结束索引（-1 表示到末尾）
         * @return 元素列表
         */
        public List<String> rangeList(String key, long start, long end) {
            return rt.opsForList().range(key, start, end);
        }

        /**
         * 获取 List 长度。
         *
         * @param key List 键
         * @return List 长度
         */
        public long getListLength(String key) {
            return rt.opsForList().size(key);
        }

        /**
         * 删除指定的键。
         *
         * @param keys 要删除的键列表
         */
        public void deleteKeys(String... keys) {
            rt.delete(List.of(keys));
        }

        /**
         * 向 Set 添加元素。
         *
         * @param key   Set 键
         * @param value 添加的值
         */
        public void addToSet(String key, String value) {
            rt.opsForSet().add(key, value);
        }

        /**
         * 获取 Set 的所有成员。
         *
         * @param key Set 键
         * @return 成员集合
         */
        public Set<String> getSetMembers(String key) {
            return rt.opsForSet().members(key);
        }

        /**
         * 获取 Set 的大小。
         *
         * @param key Set 键
         * @return Set 大小
         */
        public long getSetSize(String key) {
            return rt.opsForSet().size(key);
        }

        /**
         * 检查键是否存在。
         *
         * @param key 键
         * @return true 表示键存在
         */
        public boolean keyExists(String key) {
            return rt.hasKey(key);
        }

        /**
         * 按模式查找匹配的键。
         *
         * @param pattern 匹配模式（如 "session:*"）
         * @return 匹配的键集合
         */
        public Set<String> findKeysByPattern(String pattern) {
            return rt.keys(pattern);
        }

        /**
         * 关闭适配器（无需操作，Spring 管理 RedisTemplate 生命周期）。
         */
        public void close() {
        }
    }
}
