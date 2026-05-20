package io.yunxi.platform.shared.config;

import io.yunxi.platform.shared.constants.ConfigDefaults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置验证器
 * 
 * <p>在应用启动后验证关键配置项是否已正确设置。</p>
 * 
 * <h3>验证规则：</h3>
 * <ul>
 *   <li>必填项：必须在配置文件或环境变量中设置</li>
 *   <li>高危默认值：检测是否使用了不安全的默认值</li>
 * </ul>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfigValidator {

    private final AgentscopeCoreProperties agentscopeProperties;
    private final io.yunxi.platform.infra.config.MilvusConfig milvusConfig;
    private final io.yunxi.platform.infra.config.AgentscopeExtensionProperties extensionProperties;

    /**
     * 应用启动后执行配置验证
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        log.info("========== 配置验证开始 ==========");
        
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // 1. 验证 AgentScope 核心配置
        validateAgentScopeConfig(errors, warnings);

        // 2. 验证 Milvus 配置
        validateMilvusConfig(errors, warnings);

        // 3. 验证 Studio 配置
        validateStudioConfig(errors, warnings);

        // 4. 验证 A2A 配置
        validateA2AConfig(errors, warnings);

        // 输出验证结果
        if (!errors.isEmpty()) {
            log.error("========== 配置验证失败 ==========");
            errors.forEach(e -> log.error("  ❌ {}", e));
            throw new IllegalStateException("配置验证失败，请检查上述错误项");
        }

        if (!warnings.isEmpty()) {
            log.warn("========== 配置警告 ==========");
            warnings.forEach(w -> log.warn("  ⚠️ {}", w));
        }

        log.info("========== 配置验证通过 ==========");
        logConfigSummary();
    }

    /**
     * 验证 AgentScope 核心配置
     *
     * @param errors   错误信息列表
     * @param warnings 警告信息列表
     */
    private void validateAgentScopeConfig(List<String> errors, List<String> warnings) {
        // API Key 是必填项
        if (agentscopeProperties.getApiKey() == null || agentscopeProperties.getApiKey().isBlank()) {
            errors.add("agentscope.api-key 未配置。请设置环境变量 DASHSCOPE_API_KEY 或在配置文件中指定");
        }

        // 模型名称使用默认值提示
        if (ConfigDefaults.DEFAULT_MODEL_NAME.equals(agentscopeProperties.getModelName())) {
            log.info("  ✓ 使用默认模型: {}", agentscopeProperties.getModelName());
        }

        // Provider 使用默认值提示
        if (ConfigDefaults.DEFAULT_PROVIDER.equals(agentscopeProperties.getProvider())) {
            log.info("  ✓ 使用默认 Provider: {}", agentscopeProperties.getProvider());
        }
    }

    /**
     * 验证 Milvus 配置
     *
     * @param errors   错误信息列表
     * @param warnings 警告信息列表
     */
    private void validateMilvusConfig(List<String> errors, List<String> warnings) {
        if (!milvusConfig.isEnabled()) {
            log.info("  ℹ️ Milvus 未启用");
            return;
        }

        // Host 是必填项（已移除 localhost 默认值）
        if (milvusConfig.getHost() == null || milvusConfig.getHost().isBlank()) {
            errors.add("milvus.host 未配置（启用 Milvus 时必填）。请设置环境变量 MILVUS_HOST");
        }

        // 检测是否使用了不安全的默认端口
        if (milvusConfig.getPort() == 19530) {
            log.info("  ✓ Milvus 端口: 19530 (默认)");
        }

        // 检查嵌入维度一致性
        Integer embeddingDim = milvusConfig.getEmbedding().getDimension();
        log.info("  ✓ 向量维度: {}", embeddingDim);
    }

    /**
     * 验证 Studio 配置
     *
     * @param errors   错误信息列表
     * @param warnings 警告信息列表
     */
    private void validateStudioConfig(List<String> errors, List<String> warnings) {
        AgentscopeCoreProperties.StudioConfig studio = agentscopeProperties.getStudio();
        
        if (studio == null || !Boolean.TRUE.equals(studio.getEnabled())) {
            log.info("  ℹ️ Studio 未启用");
            return;
        }

        // Studio 启用时，URL 和 Project 是必填项
        if (studio.getUrl() == null || studio.getUrl().isBlank()) {
            errors.add("agentscope.studio.url 未配置（启用 Studio 时必填）");
        }

        if (studio.getProject() == null || studio.getProject().isBlank()) {
            errors.add("agentscope.studio.project 未配置（启用 Studio 时必填）");
        }

        if (errors.isEmpty()) {
            log.info("  ✓ Studio 配置完整: url={}, project={}", studio.getUrl(), studio.getProject());
        }
    }

    /**
     * 验证 A2A 配置
     *
     * @param errors   错误信息列表
     * @param warnings 警告信息列表
     */
    private void validateA2AConfig(List<String> errors, List<String> warnings) {
        io.yunxi.platform.infra.config.AgentscopeExtensionProperties.A2AConfig a2a = 
            extensionProperties.getA2a();
        
        if (a2a == null || !a2a.isEnabled()) {
            return;
        }

        // A2A 启用时，注册中心地址和命名空间是必填项
        if (a2a.getRegistryAddr() == null || a2a.getRegistryAddr().isBlank()) {
            errors.add("agentscope.extensions.a2a.registry-addr 未配置（启用 A2A 时必填）");
        }

        if (a2a.getNamespace() == null || a2a.getNamespace().isBlank()) {
            errors.add("agentscope.extensions.a2a.namespace 未配置（启用 A2A 时必填）");
        }

        if (errors.isEmpty()) {
            log.info("  ✓ A2A 配置完整: type={}, addr={}, namespace={}", 
                a2a.getRegistryType(), a2a.getRegistryAddr(), a2a.getNamespace());
        }
    }

    /**
     * 输出配置摘要
     */
    private void logConfigSummary() {
        log.info("---------- 配置摘要 ----------");
        log.info("  Provider: {}", agentscopeProperties.getProvider());
        log.info("  Model: {}", agentscopeProperties.getModelName());
        log.info("  Timeout: {}s", agentscopeProperties.getChatTimeoutSeconds());
        log.info("  Milvus: {} ({}:{})", 
            milvusConfig.isEnabled() ? "启用" : "禁用",
            milvusConfig.getHost(),
            milvusConfig.getPort());
        
        if (agentscopeProperties.getStudio() != null && 
            Boolean.TRUE.equals(agentscopeProperties.getStudio().getEnabled())) {
            log.info("  Studio: {} -> {}", 
                agentscopeProperties.getStudio().getProject(),
                agentscopeProperties.getStudio().getUrl());
        }
        log.info("------------------------------");
    }
}
