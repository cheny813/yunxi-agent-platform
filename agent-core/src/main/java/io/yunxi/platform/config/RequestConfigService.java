package io.yunxi.platform.config;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求级别配置服务
 * <p>负责处理每次请求中的高级功能配置解析、验证和应用。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class RequestConfigService {

    private static final Logger log = LoggerFactory.getLogger(RequestConfigService.class);

    /** 配置结果 — 封装高级功能检测结果和配置映射 */
    public static class ConfigResult {
        /** 是否包含 RAG、记忆、技能等高级功能 */
        public final boolean hasAdvancedFeatures;
        /** 配置键值对映射（ragMode、memoryMode 等） */
        public final Map<String, Object> configMap;

        public ConfigResult(boolean hasAdvancedFeatures, Map<String, Object> configMap) {
            this.hasAdvancedFeatures = hasAdvancedFeatures;
            this.configMap = configMap;
        }
    }

    /** 请求配置 — 解析后的请求级别高级功能配置 */
    public static class RequestConfig {
        public String ragMode;
        public java.util.List<String> knowledgeBases;
        public String memoryMode;
        public java.util.List<String> enabledSkills;
        public java.util.List<String> enabledTools;
        /** 最大 ReAct 循环迭代次数 */
        public Integer maxIters;
        public Double temperature;
        public Integer maxTokens;

        public boolean hasAdvancedFeatures() {
            return ragMode != null || (knowledgeBases != null && !knowledgeBases.isEmpty()) ||
                    memoryMode != null || (enabledSkills != null && !enabledSkills.isEmpty()) ||
                    (enabledTools != null && !enabledTools.isEmpty()) ||
                    maxIters != null || temperature != null || maxTokens != null;
        }
    }

    /** 验证结果 */
    public static class ValidationResult {
        public final boolean valid;
        public final String errorMessage;

        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
    }

    /** 解析请求配置 */
    public RequestConfig parseRequestConfig(UnifiedChatRequest request) {
        RequestConfig config = new RequestConfig();
        if (request == null) return config;
        config.ragMode = request.getRagMode();
        config.knowledgeBases = request.getKnowledgeBases();
        config.memoryMode = request.getMemoryMode();
        config.enabledSkills = request.getEnabledSkills();
        config.enabledTools = request.getEnabledTools();
        config.maxIters = request.getMaxIters();
        config.temperature = request.getTemperature();
        config.maxTokens = request.getMaxTokens();
        return config;
    }

    /** 验证请求配置的合法性 */
    public ValidationResult validateRequestConfig(RequestConfig config) {
        if (config == null) return new ValidationResult(true, null);
        if (config.ragMode != null) {
            for (String mode : new String[]{"NONE", "HYBRID", "KNOWLEDGE_ONLY", "LLM_ONLY"}) {
                if (mode.equalsIgnoreCase(config.ragMode)) return new ValidationResult(true, null);
            }
            return new ValidationResult(false, "无效的RAG模式: " + config.ragMode);
        }
        if (config.maxIters != null && config.maxIters < 1) return new ValidationResult(false, "maxIters必须大于0");
        if (config.temperature != null && (config.temperature < 0 || config.temperature > 2)) return new ValidationResult(false, "temperature必须在0-2之间");
        if (config.maxTokens != null && config.maxTokens < 1) return new ValidationResult(false, "maxTokens必须大于0");
        return new ValidationResult(true, null);
    }

    public ConfigResult buildConfigResult(RequestConfig config) {
        Map<String, Object> configMap = new HashMap<>();
        if (config == null || !config.hasAdvancedFeatures()) return new ConfigResult(false, configMap);
        if (config.ragMode != null) configMap.put("ragMode", config.ragMode);
        if (config.knowledgeBases != null) configMap.put("knowledgeBases", config.knowledgeBases);
        if (config.memoryMode != null) configMap.put("memoryMode", config.memoryMode);
        if (config.enabledSkills != null) configMap.put("enabledSkills", config.enabledSkills);
        if (config.enabledTools != null) configMap.put("enabledTools", config.enabledTools);
        if (config.maxIters != null) configMap.put("maxIters", config.maxIters);
        if (config.temperature != null) configMap.put("temperature", config.temperature);
        if (config.maxTokens != null) configMap.put("maxTokens", config.maxTokens);
        return new ConfigResult(true, configMap);
    }

    public void applyConfigToAgent(Agent agent, RequestConfig config) {
        if (agent == null || config == null) return;
        if (config.hasAdvancedFeatures()) {
            log.info("应用高级配置到Agent: ragMode={}, memoryMode={}", config.ragMode, config.memoryMode);
        }
    }
}
