package io.yunxi.platform.framework.memory.reme;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.memory.reme.ReMeClient;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;
import io.yunxi.platform.infra.config.AgentscopeExtensionProperties.MemoryStoreConfig;
import lombok.extern.slf4j.Slf4j;

/**
 * ReMe 记忆服务自动配置
 *
 * <p>
 * ReMe 使用 Milvus 作为向量化存储后端，因此需要 milvus.enabled=true。
 * </p>
 *
 * <p>
 * 条件：
 * <ul>
 * <li>{@code milvus.enabled=true} — Milvus 是 ReMe 的存储后端，必须启用</li>
 * <li>{@code agentscope.extensions.memory-stores.reme.enabled=true} — ReMe
 * 自身开关</li>
 * </ul>
 * </p>
 *
 * <p>
 * 优先级：ReMe > 无长期记忆
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class ReMeAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.extensions.memory-stores.reme", name = "enabled", havingValue = "true")
    public ReMeClient reMeClient(MemoryStoreConfig reMeConfig) {
        String apiBaseUrl = reMeConfig.getApiBaseUrl();
        Duration timeout = Duration.ofMillis(
                reMeConfig.getTimeoutMs() > 0 ? reMeConfig.getTimeoutMs() : 30000);
        log.info("创建 ReMeClient: apiBaseUrl={}, timeoutMs={}",
                apiBaseUrl, timeout.toMillis());
        return new ReMeClient(apiBaseUrl, timeout);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agentscope.extensions.memory-stores.reme", name = "enabled", havingValue = "true")
    public LongTermMemory reMeLongTermMemory(MemoryStoreConfig reMeConfig) {
        String apiBaseUrl = reMeConfig.getApiBaseUrl();
        Duration timeout = Duration.ofMillis(
                reMeConfig.getTimeoutMs() > 0 ? reMeConfig.getTimeoutMs() : 30000);
        log.info("创建 ReMeLongTermMemory: apiBaseUrl={}, timeoutMs={}",
                apiBaseUrl, timeout.toMillis());
        return ReMeLongTermMemory.builder()
                .userId("default")
                .apiBaseUrl(apiBaseUrl)
                .timeout(timeout)
                .build();
    }

    /**
     * 从 AgentscopeExtensionProperties.memoryStores 中提取 "reme" 配置
     */
    @Bean("reMeConfig")
    @ConditionalOnProperty(prefix = "agentscope.extensions.memory-stores.reme", name = "enabled", havingValue = "true")
    public MemoryStoreConfig reMeConfig(io.yunxi.platform.infra.config.AgentscopeExtensionProperties extensions) {
        Map<String, MemoryStoreConfig> stores = extensions.getMemoryStores();
        if (stores != null && stores.containsKey("reme")) {
            return stores.get("reme");
        }
        // fallback: 返回默认配置
        MemoryStoreConfig config = new MemoryStoreConfig();
        config.setEnabled(true);
        config.setType("reme");
        config.setApiBaseUrl("http://localhost:8002");
        return config;
    }
}
