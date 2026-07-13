package io.yunxi.platform.sync.config;

import io.yunxi.platform.sync.SyncEngine;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步管道配置加载器
 *
 * <p>
 * 将 sync-pipelines.yml 中的管道配置绑定为 {@link List}&lt;{@link SyncPipelineConfig}&gt;
 * Bean，并通过 {@link InitializingBean} 注入到 {@link SyncEngine} 中。
 * </p>
 *
 * <p>
 * {@code @Autowired List<SyncPipelineConfig>} 只收集上下文中的独立 Bean，
 * 不会从 {@code List<SyncPipelineConfig>} Bean 中取元素，
 * 因此需要通过此方式手动注入。
 * </p>
 *
 * <p>
 * 受 {@code static-sync.enabled} 控制：设为 false 时整个同步管道体系不加载。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Configuration
@ConditionalOnProperty(name = "static-sync.enabled", havingValue = "true", matchIfMissing = false)
public class SyncPipelineConfigLoader {

    /**
     * 绑定 sync-pipelines.yml 中定义的管道列表
     *
     * <p>
     * YAML 格式示例：
     * </p>
     *
     * <pre>
     * sync-pipelines:
     *   - name: dish-classes-sync
     *     source:
     *       datasource: school_db
     *       sql: "SELECT ..."
     *     target:
     *       collection: dish_classes
     *       ...
     * </pre>
     */
    @Bean
    @ConfigurationProperties(prefix = "sync-pipelines")
    public List<SyncPipelineConfig> syncPipelineConfigs() {
        return new ArrayList<>();
    }

    /**
     * 将管道配置注入到 SyncEngine 中
     *
     * <p>
     * 使用 InitializingBean 确保在 SyncEngine 初始化后执行注入。
     * </p>
     */
    @Bean
    public InitializingBean syncPipelineInitializer(
            @Qualifier("syncPipelineConfigs") List<SyncPipelineConfig> configs,
            SyncEngine syncEngine) {
        return () -> {
            if (!configs.isEmpty()) {
                syncEngine.setPipelineConfigs(configs);
                // 触发同步：SyncEngine.afterPropertiesSet() 在管道注入前已执行，
                // 因此需要在此处触发 syncAll() 启动实际数据同步
                syncEngine.syncAll();
            }
        };
    }
}
