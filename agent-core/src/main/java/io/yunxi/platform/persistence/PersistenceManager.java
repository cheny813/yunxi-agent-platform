package io.yunxi.platform.persistence;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.config.PersistenceConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 持久化管理器（统一封装）
 * <p>
 * 统一封装所有数据持久化操作，简化业务代码。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
public class PersistenceManager {

    private final HybridPersistenceStrategy hybridStrategy;
    private final PersistenceConfig config;

    /**
     * 构造持久化管理器。
     *
     * @param hybridStrategy 混合持久化策略（由 Spring 注入）
     * @param config         持久化配置（由 Spring 注入）
     */
    public PersistenceManager(HybridPersistenceStrategy hybridStrategy, PersistenceConfig config) {
        this.hybridStrategy = hybridStrategy;
        this.config = config;
    }

    /**
     * 应用启动完成后记录已启用的持久化策略。
     *
     * <p>策略实例由 Spring DI 注入，此处仅做日志确认，便于排查配置。</p>
     *
     * @param event 应用就绪事件
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (config.getEnabledStrategies() != null && !config.getEnabledStrategies().isEmpty()) {
            // 策略由 Spring DI 注入，此处仅记录已启用的持久化策略
            log.info("持久化策略配置完成: {}", config.getEnabledStrategies());
        }
    }

    /**
     * 保存会话到混合持久化策略。
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true，否则返回 false
     */
    public boolean saveConversation(ConversationEntity conversation) {
        return hybridStrategy.saveConversation(conversation);
    }

    /**
     * 按会话 ID 删除会话。
     *
     * @param conversationId 会话标识
     * @return 删除成功返回 true，否则返回 false
     */
    public boolean deleteConversation(String conversationId) {
        return hybridStrategy.deleteConversation(conversationId);
    }

    /**
     * 保存会话记忆（消息列表）。
     *
     * @param conversationId 会话标识
     * @param messages 消息列表
     * @param config 记忆配置（最大上下文等约束）
     * @return 保存成功返回 true，否则返回 false
     */
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        return hybridStrategy.saveMemory(conversationId, messages, config);
    }

    /**
     * 按会话 ID 获取记忆（消息列表）。
     *
     * @param conversationId 会话标识
     * @param config 记忆配置（最大上下文等约束）
     * @return 消息列表，未命中时返回空列表
     */
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        return hybridStrategy.getMemory(conversationId, config);
    }

    /**
     * 删除指定会话的记忆。
     *
     * @param conversationId 会话标识
     * @return 删除成功返回 true，否则返回 false
     */
    public boolean deleteMemory(String conversationId) {
        return hybridStrategy.deleteMemory(conversationId);
    }

    /**
     * 判断是否启用缓存（redis/cache）策略。
     *
     * @return 启用缓存策略时返回 true
     */
    public boolean isCacheEnabled() {
        return config.getEnabledStrategies().stream()
                .anyMatch(s -> s.equalsIgnoreCase("redis") || s.equalsIgnoreCase("cache"));
    }

    /**
     * 判断数据库持久化是否启用。
     *
     * @return 数据库启用时返回 true
     */
    public boolean isDatabaseEnabled() {
        return config.getDatabase().isEnabled();
    }

    /**
     * 返回当前生效的持久化策略名称。
     *
     * @return 策略名称字符串
     */
    public String getCurrentStrategy() {
        return hybridStrategy.getStrategyName();
    }
}
