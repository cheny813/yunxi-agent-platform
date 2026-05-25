package io.yunxi.platform.framework.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.infra.config.AgentscopeExtensionProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理控制器
 * <p>
 * 提供 API 端点来查看 AgentScope 配置信息，包括知识库、记忆存储、技能系统等。
 * </p>
 *
 * <p>
 * <b>自动配置说明</b>
 * </p>
 * <p>
 * 当 {@code agentscope.extensions.autoConfigEnabled=true} 时，框架会自动根据 YAML 配置
 * 创建知识库（Knowledge）等扩展组件并注册为 Spring Bean。配置即用，无需手动编写 {@code @Bean} 方法。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigManagementController {

    private final AgentscopeExtensionProperties extensionProperties;

    /**
     * 获取所有配置概览
     * <p>
     * 返回知识库数量、记忆存储数量、技能启用状态等概览信息。
     * </p>
     *
     * @return 包含配置概览信息的 Map，包括：
     *         <ul>
     *         <li>knowledgeBases - 知识库数量</li>
     *         <li>memoryStores - 记忆存储数量</li>
     *         <li>skillsEnabled - 技能系统是否启用</li>
     *         <li>status - 服务状态</li>
     *         <li>note - 使用说明</li>
     *         </ul>
     */
    @GetMapping("/overview")
    public Map<String, Object> getConfigOverview() {
        Map<String, Object> overview = new HashMap<>();
        if (extensionProperties.getKnowledgeBases() != null) {
            overview.put("knowledgeBases", extensionProperties.getKnowledgeBases().size());
        }
        if (extensionProperties.getMemoryStores() != null) {
            overview.put("memoryStores", extensionProperties.getMemoryStores().size());
        }
        if (extensionProperties.getSkills() != null) {
            overview.put("skillsEnabled", extensionProperties.getSkills().isEnabled());
        }
        overview.put("status", "active");
        overview.put("note", "autoConfigEnabled=true 时，YAML 配置的知识库、记忆等组件将自动注册为 Spring Bean");
        return overview;
    }

    /**
     * 获取详细配置信息
     * <p>
     * 返回完整的 AgentScope 自动配置对象，包含所有配置细节。
     * </p>
     *
     * @return 完整的 AgentscopeExtensionProperties 对象
     */
    @GetMapping("/details")
    public AgentscopeExtensionProperties getConfigDetails() {
        return extensionProperties;
    }

    /**
     * 验证配置健康状态
     * <p>
     * 检查配置服务的健康状态，返回时间戳和服务状态。
     * </p>
     *
     * @return 包含健康检查信息的 Map，包括：
     *         <ul>
     *         <li>timestamp - 当前时间戳（毫秒）</li>
     *         <li>service - 服务名称</li>
     *         <li>status - 服务状态（UP/DOWN）</li>
     *         <li>note - 使用说明</li>
     *         </ul>
     */
    @GetMapping("/health")
    public Map<String, Object> checkConfigHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("timestamp", System.currentTimeMillis());
        health.put("service", "agentscope-config");
        health.put("status", "UP");
        health.put("note", "autoConfigEnabled=true 时配置即用，无需手动 @Bean");
        return health;
    }

    /**
     * 获取知识库配置信息
     * <p>
     * 返回已配置的知识库列表及其数量。
     * 当 {@code autoConfigEnabled=true} 时，这些配置将自动创建为 Knowledge Bean 并注册到 Spring 容器。
     * </p>
     *
     * @return 包含知识库配置信息的 Map，包括：
     *         <ul>
     *         <li>knowledgeBases - 知识库配置列表</li>
     *         <li>count - 知识库数量</li>
     *         <li>note - 自动配置说明</li>
     *         </ul>
     */
    @GetMapping("/knowledge-bases")
    public Map<String, Object> getKnowledgeBases() {
        Map<String, Object> result = new HashMap<>();
        result.put("knowledgeBases", extensionProperties.getKnowledgeBases());
        result.put("count",
                extensionProperties.getKnowledgeBases() != null ? extensionProperties.getKnowledgeBases().size() : 0);
        result.put("note", "autoConfigEnabled=true 时，配置将自动创建对应的 Knowledge Bean，API 请求中按 Bean 名称引用即可");
        return result;
    }

    /**
     * 获取记忆存储配置信息
     * <p>
     * 返回已配置的记忆存储列表及其数量。记忆存储用于 Agent 的长期记忆功能。
     * </p>
     *
     * @return 包含记忆存储配置信息的 Map，包括：
     *         <ul>
     *         <li>memoryStores - 记忆存储配置列表</li>
     *         <li>count - 记忆存储数量</li>
     *         <li>note - 使用说明</li>
     *         </ul>
     */
    @GetMapping("/memory-stores")
    public Map<String, Object> getMemoryStores() {
        Map<String, Object> result = new HashMap<>();
        result.put("memoryStores", extensionProperties.getMemoryStores());
        result.put("count",
                extensionProperties.getMemoryStores() != null ? extensionProperties.getMemoryStores().size() : 0);
        result.put("note", "配置仅供参考，请通过 @Bean 创建 Mem0Memory/ReMeMemory 等实例");
        return result;
    }

    /**
     * 获取技能系统配置信息
     * <p>
     * 返回技能系统的配置信息，包括启用状态和技能路径。技能系统用于扩展 Agent 的能力。
     * </p>
     *
     * @return 包含技能系统配置信息的 Map，包括：
     *         <ul>
     *         <li>skills - 技能配置对象</li>
     *         <li>enabled - 技能系统是否启用</li>
     *         <li>note - 使用说明</li>
     *         </ul>
     */
    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        Map<String, Object> result = new HashMap<>();
        result.put("skills", extensionProperties.getSkills());
        result.put("enabled", extensionProperties.getSkills() != null && extensionProperties.getSkills().isEnabled());
        result.put("note", "配置仅供参考，请通过 @Bean 创建 SkillBox 实例");
        return result;
    }
}
