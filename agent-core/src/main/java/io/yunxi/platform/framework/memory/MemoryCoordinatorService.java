package io.yunxi.platform.framework.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.yunxi.platform.infra.cache.CacheNamespaces;
import io.yunxi.platform.spi.cache.CacheProvider;
import io.yunxi.platform.shared.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆协调服务
 *
 * <p>
 * 管理短期记忆（Redis 集群共享滑动窗口），负责当前会话的上下文连续性。
 * 长期记忆由框架的 {@code StaticLongTermMemoryHook} 自动管理（通过 ReMe → Milvus）。
 * </p>
 *
 * <p>
 * 写入路径：addMessages() 写入短期缓冲（本地 + Redis）。
 * </p>
 * <p>
 * 读取路径：getContextMessages() 从短期缓冲返回。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 4.0.0
 */
@Slf4j
@Service
public class MemoryCoordinatorService {

    /** 缓存提供者 */
    private final CacheProvider cacheProvider;

    /** 本地缓存：会话记忆（用于快速访问） */
    private final Map<String, List<Msg>> localMemoryCache = new ConcurrentHashMap<>();

    /** 本地缓存：会话配置 */
    private final Map<String, MemoryConfig> localConfigCache = new ConcurrentHashMap<>();

    /**
     * 构造函数
     *
     * @param cacheProvider 缓存提供者
     */
    public MemoryCoordinatorService(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    /**
     * 添加消息到短期记忆
     *
     * <p>
     * 写入短期缓冲（本地 + Redis）。长期记忆由框架的 StaticLongTermMemoryHook 自动管理。
     * </p>
     *
     * @param conversationId 会话ID
     * @param config         记忆配置
     * @param model          模型
     * @param messages       消息列表
     */
    public void addMessages(String conversationId, MemoryConfig config, Model model, List<Msg> messages) {
        if (conversationId == null || messages == null || messages.isEmpty()) {
            return;
        }
        addToShortTermBuffer(conversationId, config, messages);
    }

    /**
     * 获取上下文消息
     *
     * <p>
     * 从短期缓冲获取近期对话滑动窗口。长期记忆由框架的 StaticLongTermMemoryHook
     * 在 Agent 推理前自动注入。
     * </p>
     *
     * @param conversationId  会话ID
     * @param config          记忆配置
     * @param model           模型
     * @param currentMessages 当前消息列表
     * @return 短期上下文消息列表
     */
    public List<Msg> getContextMessages(String conversationId, MemoryConfig config, Model model,
            List<Msg> currentMessages) {
        if (conversationId == null) {
            return currentMessages != null ? currentMessages : new ArrayList<>();
        }
        return getShortTermContext(conversationId, config, currentMessages);
    }

    // ==================== 短期记忆（本地 + Redis） ====================

    private void addToShortTermBuffer(String conversationId, MemoryConfig config, List<Msg> messages) {
        // 更新本地缓存
        List<Msg> memory = localMemoryCache.computeIfAbsent(conversationId, k -> new ArrayList<>());
        memory.addAll(messages);

        // 更新配置缓存
        localConfigCache.put(conversationId, config);

        // 同步到 Redis（集群共享）
        try {
            Optional<List<Msg>> existingMemory = cacheProvider.get(
                    CacheNamespaces.MEMORY, conversationId,
                    new TypeReference<List<Msg>>() {
                    });
            List<Msg> allMessages = existingMemory.orElse(new ArrayList<>());
            allMessages.addAll(messages);
            cacheProvider.put(CacheNamespaces.MEMORY, conversationId, allMessages,
                    Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
            cacheProvider.put(CacheNamespaces.MEMORY_CONFIG, conversationId, config,
                    Duration.ofHours(CacheNamespaces.MEMORY_TTL_HOURS));
        } catch (Exception e) {
            log.warn("同步记忆到 Redis 失败: conversationId={}, error={}", conversationId, e.getMessage());
        }

        log.debug("短期记忆写入: conversationId={}, messageCount={}, totalMessages={}",
                conversationId, messages.size(), memory.size());
    }

    private List<Msg> getShortTermContext(String conversationId, MemoryConfig config, List<Msg> currentMessages) {
        // 先从本地缓存获取
        List<Msg> memory = localMemoryCache.get(conversationId);

        // 本地缓存未命中，尝试从 Redis 获取
        if (memory == null || memory.isEmpty()) {
            try {
                Optional<List<Msg>> fromRedis = cacheProvider.get(
                        CacheNamespaces.MEMORY, conversationId,
                        new TypeReference<List<Msg>>() {
                        });
                if (fromRedis.isPresent()) {
                    memory = fromRedis.get();
                    localMemoryCache.put(conversationId, memory);
                    log.debug("从 Redis 加载记忆: conversationId={}, count={}", conversationId, memory.size());
                }
            } catch (Exception e) {
                log.warn("从 Redis 加载记忆失败: conversationId={}, error={}", conversationId, e.getMessage());
            }
        }

        if (memory == null || memory.isEmpty()) {
            return currentMessages != null ? currentMessages : new ArrayList<>();
        }

        // 根据配置决定返回多少历史消息
        int maxContextSize = config.getMaxContextSize() > 0 ? config.getMaxContextSize() : 20;

        List<Msg> result = new ArrayList<>();

        // 如果历史消息超过限制，取最近的N条
        if (memory.size() > maxContextSize) {
            result.addAll(memory.subList(memory.size() - maxContextSize, memory.size()));
        } else {
            result.addAll(memory);
        }

        // 添加当前消息
        if (currentMessages != null) {
            result.addAll(currentMessages);
        }

        log.debug("短期上下文: conversationId={}, historySize={}, contextSize={}",
                conversationId, memory.size(), result.size());

        return result;
    }
}
