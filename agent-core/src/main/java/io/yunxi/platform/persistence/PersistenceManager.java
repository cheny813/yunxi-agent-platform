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
 * @version 1.0.0
 */
@Slf4j
@Service
public class PersistenceManager {

    private final HybridPersistenceStrategy hybridStrategy;
    private final PersistenceConfig config;

    public PersistenceManager(HybridPersistenceStrategy hybridStrategy, PersistenceConfig config) {
        this.hybridStrategy = hybridStrategy;
        this.config = config;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (config.getEnabledStrategies() != null && !config.getEnabledStrategies().isEmpty()) {
            // V2.0: setEnabledStrategies removed - strategies managed via Spring DI
            log.info("持久化策略配置完成: {}", config.getEnabledStrategies());
        }
    }

    public boolean saveConversation(ConversationEntity conversation) {
        return hybridStrategy.saveConversation(conversation);
    }

    public boolean deleteConversation(String conversationId) {
        return hybridStrategy.deleteConversation(conversationId);
    }

    public boolean saveMemory(String conversationId, List<Msg> messages, MemoryConfig config) {
        return hybridStrategy.saveMemory(conversationId, messages, config);
    }

    public List<Msg> getMemory(String conversationId, MemoryConfig config) {
        return hybridStrategy.getMemory(conversationId, config);
    }

    public boolean deleteMemory(String conversationId) {
        return hybridStrategy.deleteMemory(conversationId);
    }

    public boolean isCacheEnabled() {
        return config.getEnabledStrategies().stream()
                .anyMatch(s -> s.equalsIgnoreCase("redis") || s.equalsIgnoreCase("cache"));
    }

    public boolean isDatabaseEnabled() {
        return config.getDatabase().isEnabled();
    }

    public String getCurrentStrategy() {
        return hybridStrategy.getStrategyName();
    }
}
