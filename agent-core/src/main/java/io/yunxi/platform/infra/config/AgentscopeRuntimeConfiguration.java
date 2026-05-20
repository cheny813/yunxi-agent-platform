package io.yunxi.platform.infra.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope Runtime 生产级特性配置
 * <p>
 * 支持：
 * - 安全沙箱（浏览器、文件系统、GUI 自动化）
 * - 部署管理（A2A 协议）
 * - 可观测性（追踪、监控）
 * </p>
 *
 * <p>
 * 注意：AgentScope Runtime 为实验性项目，需要从源码构建或等待正式发布。
 * 依赖地址：https://github.com/agentscope-ai/agentscope-runtime-java
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 3.2.0
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AgentscopeRuntimeConfiguration.AgentscopeRuntimeConfig.class)
@ConditionalOnProperty(prefix = "agentscope.runtime", name = "enabled", havingValue = "true")
public class AgentscopeRuntimeConfiguration {

    // 注意：由于 Runtime 还未正式发布到 Maven 仓库
    // 暂时使用条件注解。当依赖可用后，取消注释即可使用
    // ==================== 沙箱配置 ====================

    /**
     * 配置沙箱管理器
     *
     * <p>
     * 沙箱类型：
     * - BASE: 基础沙箱（Python 执行器）
     * - BROWSER: 浏览器自动化
     * - FILESYSTEM: 文件系统操作
     * - GUI: GUI 自动化
     * </p>
     */
    /*
     * @Bean
     *
     * @ConditionalOnClass(name = "io.agentscope.runtime.sandbox.SandboxManager")
     *
     * @ConditionalOnProperty(prefix = "agentscope.runtime.sandbox", name =
     * "enabled", havingValue = "true")
     * public SandboxManager sandboxManager(AgentscopeRuntimeConfig config) {
     * try {
     * SandboxManager manager = SandboxManager.builder()
     * .defaultSandboxType(config.getSandbox().getDefaultType())
     * .build();
     *
     * log.info("AgentScope Runtime 沙箱管理器创建成功，默认类型: {}",
     * config.getSandbox().getDefaultType());
     *
     * return manager;
     * } catch (Exception e) {
     * log.error("创建沙箱管理器失败", e);
     * return null;
     * }
     * }
     *
     * @Bean
     *
     * @ConditionalOnClass(name = "io.agentscope.runtime.sandbox.BrowserSandbox")
     *
     * @ConditionalOnProperty(prefix = "agentscope.runtime.sandbox.browser", name =
     * "enabled", havingValue = "true")
     * public Object browserSandbox() {
     * try {
     * log.info("浏览器沙箱配置成功");
     * return new Object();
     * } catch (Exception e) {
     * log.error("创建浏览器沙箱失败", e);
     * return null;
     * }
     * }
     *
     * @Bean
     *
     * @ConditionalOnClass(name = "io.agentscope.runtime.sandbox.FilesystemSandbox")
     *
     * @ConditionalOnProperty(prefix = "agentscope.runtime.sandbox.filesystem", name
     * = "enabled", havingValue = "true")
     * public Object filesystemSandbox(AgentscopeRuntimeConfig config) {
     * try {
     * log.info("文件系统沙箱配置成功，工作目录: {}",
     * config.getSandbox().getFilesystem().getWorkspace());
     * return new Object();
     * } catch (Exception e) {
     * log.error("创建文件系统沙箱失败", e);
     * return null;
     * }
     * }
     *
     * @Bean
     *
     * @ConditionalOnClass(name = "io.agentscope.runtime.sandbox.GuiSandbox")
     *
     * @ConditionalOnProperty(prefix = "agentscope.runtime.sandbox.gui", name =
     * "enabled", havingValue = "true")
     * public Object guiSandbox() {
     * try {
     * log.info("GUI 沙箱配置成功");
     * return new Object();
     * } catch (Exception e) {
     * log.error("创建 GUI 沙箱失败", e);
     * return null;
     * }
     * }
     */

    // ==================== 配置类定义 ====================

    /**
     * AgentScope Runtime 配置类
     */
    @Data
    @ConfigurationProperties(prefix = "agentscope.runtime")
    public static class AgentscopeRuntimeConfig {
        /** 是否启用 Runtime */
        private boolean enabled = false;

        /** 沙箱配置 */
        private SandboxConfig sandbox = new SandboxConfig();

        /** 部署配置 */
        private DeployConfig deploy = new DeployConfig();

        /** 可观测性配置 */
        private ObservabilityConfig observability = new ObservabilityConfig();

        /**
         * 沙箱配置类
         */
        @Data
        public static class SandboxConfig {
            /** 是否启用沙箱 */
            private boolean enabled = false;
            /** 默认沙箱类型 */
            private String defaultType = "BASE";
            /** 浏览器沙箱配置 */
            private BrowserConfig browser = new BrowserConfig();
            /** 文件系统沙箱配置 */
            private FilesystemConfig filesystem = new FilesystemConfig();
            /** GUI 沙箱配置 */
            private GuiConfig gui = new GuiConfig();
            /** Docker 沙箱配置 */
            private DockerConfig docker = new DockerConfig();
        }

        /**
         * 浏览器沙箱配置类
         */
        @Data
        public static class BrowserConfig {
            /** 是否启用浏览器沙箱 */
            private boolean enabled = false;
            /** Headless 模式 */
            private boolean headless = true;
            /** 浏览器类型（chrome, firefox, edge） */
            private String browserType = "chrome";
        }

        /**
         * 文件系统沙箱配置类
         */
        @Data
        public static class FilesystemConfig {
            /** 是否启用文件系统沙箱 */
            private boolean enabled = false;
            /** 工作目录 */
            private String workspace = "./workspace";
        }

        /**
         * GUI 沙箱配置类
         */
        @Data
        public static class GuiConfig {
            /** 是否启用 GUI 沙箱 */
            private boolean enabled = false;
            /** 显示服务器地址（Linux） */
            private String display = ":99";
        }

        /**
         * Docker 沙箱配置类
         */
        @Data
        public static class DockerConfig {
            /** 是否使用 Docker 沙箱 */
            private boolean enabled = false;
            /** Docker 镜像 */
            private String image = "agentscope/runtime-sandbox:latest";
            /** 容器网络 */
            private String network = "bridge";
        }

        /**
         * 部署配置类
         */
        @Data
        public static class DeployConfig {
            /** 是否启用部署管理 */
            private boolean enabled = false;
            /** 部署端口 */
            private int port = 10001;
            /** 部署主机 */
            private String host = "0.0.0.0";
            /** 是否启用 A2A 协议 */
            private boolean a2aEnabled = false;
        }

        /**
         * 可观测性配置类
         */
        @Data
        public static class ObservabilityConfig {
            /** 是否启用可观测性 */
            private boolean enabled = false;
            /** 是否启用追踪 */
            private boolean tracingEnabled = true;
            /** 是否启用指标 */
            private boolean metricsEnabled = true;
            /** 是否启用日志 */
            private boolean loggingEnabled = true;
            /** OpenTelemetry 端点 */
            private String otelEndpoint = "http://localhost:4317";
            /** 采样率（0.0-1.0） */
            private double samplingRate = 1.0;
        }
    }
}
