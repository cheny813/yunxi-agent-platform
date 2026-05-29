package io.yunxi.platform.framework.config;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求级别配置服务
 *
 * <p>
 * 负责处理每次请求中的高级功能配置
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class RequestConfigService {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(RequestConfigService.class);

    /**
     * 配置结果 — 封装高级功能检测结果和配置映射
     */
    public static class ConfigResult {
        /** 是否包含 RAG、记忆、技能等高级功能 */
        public final boolean hasAdvancedFeatures;
        /** 配置键值对映射（ragMode、memoryMode 等） */
        public final Map<String, Object> configMap;

        /**
         * 构造配置结果
         *
         * @param hasAdvancedFeatures 是否具有高级功能
         * @param configMap           配置映射
         */
        public ConfigResult(boolean hasAdvancedFeatures, Map<String, Object> configMap) {
            this.hasAdvancedFeatures = hasAdvancedFeatures;
            this.configMap = configMap;
        }
    }

    /**
     * 请求配置 — 解析后的请求级别高级功能配置
     * <p>
     * 包含 RAG 模式、知识库、记忆模式、技能、工具、执行参数等配置项。
     * 每个字段对应 UnifiedChatRequest 中的一个可选参数。
     * </p>
     */
    public static class RequestConfig {
        /** RAG 检索模式（NONE / GENERIC / AGENTIC） */
        public String ragMode;
        /** 知识库名称列表 */
        public java.util.List<String> knowledgeBases;
        /** 记忆模式（NONE / IN_MEMORY / PERSISTENT） */
        public String memoryMode;
        /** 启用的技能列表 */
        public java.util.List<String> enabledSkills;
        /** 启用的工具类名列表 */
        public java.util.List<String> enabledTools;
        /** 最大 ReAct 循环迭代次数 */
        public Integer maxIters;
        /** 模型温度参数（0.0～2.0） */
        public Double temperature;
        /** 最大输出 Token 数 */
        public Integer maxTokens;

        /**
         * 检查是否包含高级功能
         *
         * @return true 如果任一高级功能已配置
         */
        public boolean hasAdvancedFeatures() {
            return ragMode != null ||
                    (knowledgeBases != null && !knowledgeBases.isEmpty()) ||
                    memoryMode != null ||
                    (enabledSkills != null && !enabledSkills.isEmpty()) ||
                    (enabledTools != null && !enabledTools.isEmpty()) ||
                    maxIters != null ||
                    temperature != null ||
                    maxTokens != null;
        }
    }

    /**
     * 验证结果
     */
    public static class ValidationResult {
        /** 是否有效 */
        public final boolean valid;
        /** 错误信息 */
        public final String errorMessage;

        /**
         * 构造验证结果
         *
         * @param valid        是否有效
         * @param errorMessage 错误信息
         */
        public ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }
    }

    /**
     * 解析请求配置
     *
     * @param request 统一聊天请求
     * @return 请求配置
     */
    public RequestConfig parseRequestConfig(UnifiedChatRequest request) {
        RequestConfig config = new RequestConfig();

        if (request == null) {
            return config;
        }

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

    /**
     * 验证请求配置
     *
     * @param config 请求配置
     * @return 验证结果
     */
    public ValidationResult validateRequestConfig(RequestConfig config) {
        if (config == null) {
            return new ValidationResult(true, null);
        }

        // 验证RAG模式
        if (config.ragMode != null) {
            String[] validModes = { "NONE", "HYBRID", "KNOWLEDGE_ONLY", "LLM_ONLY" };
            boolean valid = false;
            for (String mode : validModes) {
                if (mode.equalsIgnoreCase(config.ragMode)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                return new ValidationResult(false, "无效的RAG模式: " + config.ragMode);
            }
        }

        // 验证记忆模式
        if (config.memoryMode != null) {
            String[] validModes = { "NONE", "IN_MEMORY", "PERSISTENT" };
            boolean valid = false;
            for (String mode : validModes) {
                if (mode.equalsIgnoreCase(config.memoryMode)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                return new ValidationResult(false, "无效的记忆模式: " + config.memoryMode);
            }
        }

        // 验证maxIters
        if (config.maxIters != null && config.maxIters < 1) {
            return new ValidationResult(false, "maxIters必须大于0");
        }

        // 验证temperature
        if (config.temperature != null && (config.temperature < 0 || config.temperature > 2)) {
            return new ValidationResult(false, "temperature必须在0-2之间");
        }

        // 验证maxTokens
        if (config.maxTokens != null && config.maxTokens < 1) {
            return new ValidationResult(false, "maxTokens必须大于0");
        }

        return new ValidationResult(true, null);
    }

    /**
     * 构建配置结果
     *
     * @param config 请求配置
     * @return 配置结果
     */
    public ConfigResult buildConfigResult(RequestConfig config) {
        Map<String, Object> configMap = new HashMap<>();

        if (config == null || !config.hasAdvancedFeatures()) {
            return new ConfigResult(false, configMap);
        }

        if (config.ragMode != null) {
            configMap.put("ragMode", config.ragMode);
        }
        if (config.knowledgeBases != null && !config.knowledgeBases.isEmpty()) {
            configMap.put("knowledgeBases", config.knowledgeBases);
        }
        if (config.memoryMode != null) {
            configMap.put("memoryMode", config.memoryMode);
        }
        if (config.enabledSkills != null && !config.enabledSkills.isEmpty()) {
            configMap.put("enabledSkills", config.enabledSkills);
        }
        if (config.enabledTools != null && !config.enabledTools.isEmpty()) {
            configMap.put("enabledTools", config.enabledTools);
        }
        if (config.maxIters != null) {
            configMap.put("maxIters", config.maxIters);
        }
        if (config.temperature != null) {
            configMap.put("temperature", config.temperature);
        }
        if (config.maxTokens != null) {
            configMap.put("maxTokens", config.maxTokens);
        }

        return new ConfigResult(true, configMap);
    }

    /**
     * 应用配置到Agent
     *
     * @param agent  Agent 实例
     * @param config 请求配置
     */
    public void applyConfigToAgent(Agent agent, RequestConfig config) {
        if (agent == null || config == null) {
            return;
        }

        // 记录配置应用日志
        if (config.hasAdvancedFeatures()) {
            log.info("应用高级配置到Agent: ragMode={}, memoryMode={}",
                    config.ragMode, config.memoryMode);
        }
    }
}
