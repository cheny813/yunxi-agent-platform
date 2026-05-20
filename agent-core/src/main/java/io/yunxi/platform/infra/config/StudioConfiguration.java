package io.yunxi.platform.infra.config;

import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.studio.StudioClient;
import io.agentscope.core.studio.StudioWebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

/**
 * AgentScope Studio 可视化调试配置类
 *
 * <p>
 * 本配置类负责初始化和管理 AgentScope Studio 集成，提供 Agent 执行过程的
 * 可视化调试能力。Studio 是 AgentScope 官方提供的可视化界面，支持：
 * </p>
 * <ul>
 * <li>实时可视化 Agent 推理和执行过程</li>
 * <li>通过 Web UI 与 Agent 交互</li>
 * <li>查看完整的消息流和追踪信息</li>
 * <li>多运行管理和对比</li>
 * </ul>
 *
 * <h3>配置示例 (application.yml)</h3>
 *
 * <pre>
 * agentscope:
 *   studio:
 *     enabled: true                          # 启用 Studio
 *     url: http://localhost:5000             # Studio 服务地址
 *     project: my-project                    # 项目名称
 *     run-name-prefix: demo_                 # 运行名称前缀
 * </pre>
 *
 * <h3>使用步骤</h3>
 * <ol>
 * <li>启动 Studio 服务：npx @agentscope/studio</li>
 * <li>配置 application.yml 启用 Studio</li>
 * <li>启动应用，访问 http://localhost:3000 查看可视化界面</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see StudioManager AgentScope Studio 管理器
 * @see StudioMessageHook Agent 消息钩子
 */
@Configuration
@ConditionalOnProperty(prefix = "agentscope.studio", name = "enabled", havingValue = "true")
public class StudioConfiguration implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StudioConfiguration.class);

    /** AgentScope 配置属性 */
    @Autowired
    private AgentscopeCoreProperties properties;

    /**
     * Studio 是否已初始化
     */
    private volatile boolean initialized = false;

    /**
     * 应用启动完成后初始化 Studio 连接
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initStudio();
    }

    /**
     * 初始化 Studio 连接
     *
     * <p>
     * 连接到 AgentScope Studio 服务，建立 WebSocket 通信通道。
     * 初始化成功后，Agent 的消息将自动推送到 Studio 进行可视化展示。
     * </p>
     */
    public synchronized void initStudio() {
        if (initialized) {
            return;
        }

        AgentscopeCoreProperties.StudioConfig config = properties.getStudio();
        if (config == null || config.getEnabled() == null || !config.getEnabled()) {
            log.debug("Studio 未启用，跳过初始化");
            return;
        }

        try {
            String runName = config.getRunNamePrefix() + System.currentTimeMillis();

            log.info("正在连接 AgentScope Studio: {}", config.getUrl());
            log.info("项目: {}, 运行: {}", config.getProject(), runName);

            StudioManager.init()
                    .studioUrl(config.getUrl())
                    .project(config.getProject())
                    .runName(runName)
                    .initialize()
                    .block();

            initialized = true;
            log.info("AgentScope Studio 连接成功！访问 {} 查看可视化界面", config.getUrl());

        } catch (Exception e) {
            log.error("连接 AgentScope Studio 失败: {}", e.getMessage());
            log.warn("Studio 功能将不可用，但应用仍可正常运行");
            log.debug("Studio 连接失败详情", e);
        }
    }

    /**
     * 获取 Studio 客户端
     *
     * <p>
     * 返回 StudioClient 实例，用于手动推送消息到 Studio。
     * </p>
     *
     * @return StudioClient 实例，如果未初始化则返回 null
     */
    @Bean
    public StudioClient studioClient() {
        if (!initialized) {
            return null;
        }
        return StudioManager.getClient();
    }

    /**
     * 获取 Studio WebSocket 客户端
     *
     * @return StudioWebSocketClient 实例，如果未初始化则返回 null
     */
    @Bean
    public StudioWebSocketClient studioWebSocketClient() {
        if (!initialized) {
            return null;
        }
        return StudioManager.getWebSocketClient();
    }

    /**
     * 创建 Studio 消息钩子
     *
     * <p>
     * 此钩子会自动将 Agent 的消息推送到 Studio 进行可视化展示。
     * 在创建 Agent 时注入此钩子即可启用 Studio 可视化。
     * </p>
     *
     * @return StudioMessageHook 实例，如果未初始化则返回 null
     */
    @Bean
    public StudioMessageHook studioMessageHook() {
        if (!initialized) {
            log.debug("Studio 未初始化，返回 null Hook");
            return null;
        }
        log.info("创建 StudioMessageHook，Agent 消息将自动推送到 Studio");
        return new StudioMessageHook(StudioManager.getClient());
    }

    /**
     * 检查 Studio 是否已初始化
     *
     * @return true 表示已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 应用关闭时清理 Studio 资源
     */
    @PreDestroy
    public void shutdown() {
        if (initialized) {
            try {
                log.info("正在关闭 AgentScope Studio 连接...");
                StudioManager.shutdown();
                log.info("AgentScope Studio 已关闭");
            } catch (Exception e) {
                log.warn("关闭 Studio 时发生异常: {}", e.getMessage());
            }
        }
    }
}
