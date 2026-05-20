package io.yunxi.platform.infra.persistence;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.infra.config.PersistenceConfig;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.config.MemoryConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 持久化管理器（统一封装）
 * 
 * <p>
 * 统一封装所有数据持久化操作，简化业务代码
 * </p>
 * 
 * <p>
 * <b>使用示例</b>：
 * 
 * <pre>
 * // 保存会话
 * persistenceManager.saveConversation(conversation);
 * 
 * // 获取记忆
 * List<Msg> memory = persistenceManager.getMemory(conversationId, config);
 * </pre>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class PersistenceManager {

    /** 混合持久化策略 */
    private final HybridPersistenceStrategy hybridStrategy;
    /** 持久化配置 */
    private final PersistenceConfig config;

    /**
     * 构造持久化管理器
     *
     * @param hybridStrategy 混合持久化策略
     * @param config         持久化配置
     */
    public PersistenceManager(
            HybridPersistenceStrategy hybridStrategy,
            PersistenceConfig config) {
        this.hybridStrategy = hybridStrategy;
        this.config = config;
    }

    /**
     * 应用启动后配置策略
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (config.getEnabledStrategies() != null && !config.getEnabledStrategies().isEmpty()) {
            hybridStrategy.setEnabledStrategies(config.getEnabledStrategies());
            log.info("持久化策略配置完成: {}", config.getEnabledStrategies());
        }
    }

    // ==================== 会话操作 ====================

    /**
     * 保存会话
     * 
     * <p>
     * 根据配置自动选择持久化策略，可能同时保存到多个存储
     * </p>
     *
     * @param conversation 会话实体
     * @return 保存成功返回 true
     */
    public boolean saveConversation(ConversationEntity conversation) {
        log.debug("保存会话: id={}, strategy={}",
                conversation.getId(), hybridStrategy.getStrategyName());
        return hybridStrategy.saveConversation(conversation);
    }

    /**
     * 删除会话
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    public boolean deleteConversation(String conversationId) {
        log.debug("删除会话: id={}", conversationId);
        return hybridStrategy.deleteConversation(conversationId);
    }

    // ==================== 记忆操作 ====================

    /**
     * 保存会话记忆
     * 
     * <p>
     * 根据策略类型：
     * <ul>
     * <li>CACHE: 只保存到Redis，带TTL过期</li>
     * <li>PRIMARY: 通过会话对象保存到数据库</li>
     * <li>HYBRID: 同时保存到多个存储</li>
     * </ul>
     * </p>
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
     * @param config         记忆配置
     * @return 保存成功返回 true
     */
    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        log.debug("保存记忆: conversationId={}, count={}, strategy={}",
                conversationId, messages.size(), hybridStrategy.getStrategyName());
        return hybridStrategy.saveMemory(conversationId, messages, config);
    }

    /**
     * 获取会话记忆
     * 
     * <p>
     * 查找顺序：CACHE -> PRIMARY -> ARCHIVE
     * </p>
     *
     * @param conversationId 会话 ID
     * @param config         记忆配置
     * @return 消息列表
     */
    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        List<Msg> memory = hybridStrategy.getMemory(conversationId, config);
        log.debug("获取记忆: conversationId={}, count={}, source={}",
                conversationId, memory.size(), determineSource());
        return memory;
    }

    /**
     * 删除会话记忆
     *
     * @param conversationId 会话 ID
     * @return 删除成功返回 true
     */
    public boolean deleteMemory(String conversationId) {
        log.debug("删除记忆: conversationId={}", conversationId);
        return hybridStrategy.deleteMemory(conversationId);
    }

    // ==================== 工具方法 ====================

    /**
     * 判断是否使用缓存
     *
     * @return 使用缓存返回 true
     */
    public boolean isCacheEnabled() {
        return config.getEnabledStrategies().stream()
                .anyMatch(s -> s.equalsIgnoreCase("redis") || s.equalsIgnoreCase("cache"));
    }

    /**
     * 判断是否使用数据库
     *
     * @return 使用数据库返回 true
     */
    public boolean isDatabaseEnabled() {
        return config.getDatabase().isEnabled();
    }

    /**
     * 获取当前策略名称
     *
     * @return 当前策略名称
     */
    public String getCurrentStrategy() {
        return hybridStrategy.getStrategyName();
    }

    /**
     * 判断数据来源
     *
     * @return 数据来源标识
     */
    private String determineSource() {
        if (isCacheEnabled()) {
            return "cache";
        }
        if (isDatabaseEnabled()) {
            return "database";
        }
        return "unknown";
    }
}

