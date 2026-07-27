package io.yunxi.platform.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import io.yunxi.platform.config.AgentscopeExtensionProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理控制器
 * <p>
 * 提供 API 端点来查询 AgentScope 配置信息，包括知识库、记忆存储、技能系统等
 * </p>
 *
 * <p>
 * <b>自动配置说明</b>
 * </p>
 * <p>
 * 当 {@code agentscope.extensions.autoConfigEnabled=true} 时，框架会自动根据 YAML 配置
 * 创建知识库（Knowledge）等组件并注册为 Spring Bean。配置即使用无需手动编写 {@code @Bean} 方法
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

    /** AgentScope 原生技能仓库配置（agentscope.core.skill），技能状态以此为准 */
    private final AgentscopeCoreProperties coreProperties;

    /**
     * 获取所有配置项概览
     * <p>
     * 返回知识库数量、记忆存储数量、技能启用状态等概要信息
     * </p>
     *
     * @return 包含配置概要信息的 Map，包括
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
        if (extensionProperties.getMemoryStores() != null) {
            overview.put("memoryStores", extensionProperties.getMemoryStores().size());
        }
        if (coreProperties.getSkill() != null) {
            overview.put("skillsEnabled", coreProperties.getSkill().isEnabled());
        }
        overview.put("status", "active");
        overview.put("note", "autoConfigEnabled=true 时，YAML 配置的知识库、记忆等组件将自动注册为 Spring Bean");
        return overview;
    }

    /**
     * 获取详细配置信息
     * <p>
     * 返回完整的 AgentScope 自动配置对象，包括所有配置节点
     * </p>
     *
     * @return 完整的 AgentscopeExtensionProperties 对象
     */
    @GetMapping("/details")
    public AgentscopeExtensionProperties getConfigDetails() {
        return extensionProperties;
    }

    /**
     * 验证配置服务状态
     * <p>
     * 检查配置服务的运行状态，返回时间戳和服务状态等信息
     * </p>
     *
     * @return 包含健康检查信息的 Map，包括
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
        health.put("note", "autoConfigEnabled=true 时配置即使用无需手动 @Bean");
        return health;
    }

    /**
     * 获取记忆存储配置信息
     * <p>
     * 返回已配置的记忆存储列表及其数量。记忆存储用于 Agent 的长期记忆功能
     * </p>
     *
     * @return 包含记忆存储配置信息的 Map，包括
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
        result.put("note", "配置仅供参考，请通过 @Bean 创建 LongTermMemory 实例");
        return result;
    }

    /**
     * 获取技能系统配置信息
     * <p>
     * 返回技能系统的配置信息，包括启用状态和技能路径。技能系统用于扩展 Agent 的能力
     * </p>
     *
     * @return 包含技能系统配置信息的 Map，包括
     *         <ul>
     *         <li>skills - 技能配置对象</li>
     *         <li>enabled - 技能系统是否启用</li>
     *         <li>note - 使用说明</li>
     *         </ul>
     */
    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        Map<String, Object> result = new HashMap<>();
        result.put("skills", coreProperties.getSkill());
        result.put("enabled", coreProperties.getSkill() != null && coreProperties.getSkill().isEnabled());
        result.put("note", "技能由 AgentScope AgentSkillRepository 体系承载，配置见 agentscope.core.skill");
        return result;
    }
}
