package io.yunxi.platform.infra.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import io.yunxi.platform.infra.repository.CompositeConversationRepository;
import io.yunxi.platform.infra.repository.ConversationRepository;
import io.yunxi.platform.infra.repository.DatabaseConversationRepository;
import io.yunxi.platform.infra.repository.InMemoryConversationRepository;

/**
 * 会话存储配置
 * 
 * <p>
 * 支持通过配置切换存储实现：
 * <ul>
 *   <li>memory - 纯内存存储（适合开发测试）</li>
 *   <li>database - 纯数据库存储（适合生产环境）</li>
 *   <li>composite - 组合存储，缓存+数据库（推荐，默认）</li>
 * </ul>
 * </p>
 * 
 * <p>
 * 配置示例：
 * <pre>
 * conversation:
 *   storage:
 *     type: composite  # memory | database | composite
 * </pre>
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "conversation.storage")
public class ConversationStorageConfig {

    /**
     * 存储类型：memory, database, composite
     */
    private String type = "composite";

    /**
     * 是否启用缓存预热（从数据库加载到缓存）
     */
    private boolean warmupCache = false;

    /**
     * 创建会话存储仓库 Bean
     *
     * @param memoryRepository     内存存储仓库
     * @param databaseRepository   数据库存储仓库
     * @param compositeRepository  组合存储仓库
     * @return 选定的会话存储仓库
     */
    @Bean
    @Primary
    public ConversationRepository conversationRepository(
            InMemoryConversationRepository memoryRepository,
            DatabaseConversationRepository databaseRepository,
            CompositeConversationRepository compositeRepository) {

        ConversationRepository selectedRepository = switch (type.toLowerCase()) {
            case "memory" -> {
                log.info("使用内存存储: type={}", type);
                yield memoryRepository;
            }
            case "database" -> {
                log.info("使用数据库存储: type={}", type);
                yield databaseRepository;
            }
            case "composite" -> {
                log.info("使用组合存储（缓存+数据库）: type={}", type);
                yield compositeRepository;
            }
            default -> {
                log.warn("未知的存储类型: {}, 使用默认组合存储", type);
                yield compositeRepository;
            }
        };

        log.info("会话存储仓库初始化完成: storageType={}", selectedRepository.getStorageType());
        return selectedRepository;
    }
}
