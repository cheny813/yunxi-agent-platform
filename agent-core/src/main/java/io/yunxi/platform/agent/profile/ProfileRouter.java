package io.yunxi.platform.agent.profile;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.ProfileDefinition;
import io.yunxi.platform.shared.dto.ProfileInfo;

/**
 * Profile 路由器，根据配置选择 Agent 实例。
 *
 * <p>
 * 核心能力：
 * <ul>
 * <li>根据 agentName + profileName 组合键查找 Agent 实例</li>
 * <li>按 profile 配置动态创建新的 Agent（共享模型配置）</li>
 * <li>管理 Profile Agent 实例的生命周期</li>
 * </ul>
 * </p>
 *
 * <p>
 * 组合键格式：agentName#profileName，例如 "business-assistant#default"。
 * AgentService 使用组合键注册和查找 Profile Agent 实例。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ProfileRouter {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ProfileRouter.class);

    /** Agent 服务 — 查找和创建 Agent 实例 */
    private final AgentService agentService;

    /** Agent 定义加载器 — 读取 Profile 配置（来自 YAML 的 profiles 节点） */
    private final AgentDefinitionLoader definitionLoader;

    /**
     * 创建 Profile 路由器。
     *
     * @param agentService     Agent 服务
     * @param definitionLoader Agent 定义加载器
     */
    public ProfileRouter(AgentService agentService, AgentDefinitionLoader definitionLoader) {
        this.agentService = agentService;
        this.definitionLoader = definitionLoader;
    }

    /**
     * 构建组合键 agentName + "#" + profileName。
     *
     * <p>
     * AgentService 使用组合键注册和查找 Profile Agent 实例。
     * 例如 "business-assistant#default" 表示 business-assistant 的
     * default Profile 版本。
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称
     * @return 组合键字符串
     */
    public String buildCompositeKey(String agentName, String profile) {
        return agentName + "#" + profile;
    }

    /**
     * 根据 Profile 路由到合适的 Agent 实例。
     *
     * <p>
     * 路由逻辑：
     * <ol>
     * <li>profile 为 null 或空 → 返回原始 Agent 实例</li>
     * <li>profile 非空 → 查找或创建对应的 Profile Agent 实例</li>
     * <li>Profile 不存在 → 降级返回原始 Agent 实例</li>
     * </ol>
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称（null 或空 = 默认 Agent）
     * @return Agent 实例，根据 profile 可能是原始实例或 Profile 版本
     */
    public Agent resolve(String agentName, String profile) {
        // profile 为空时直接返回原始 Agent
        if (profile == null || profile.isBlank()) {
            return agentService.getAgentInstance(agentName);
        }

        // 尝试查找 Profile Agent 实例
        String compositeKey = buildCompositeKey(agentName, profile);
        try {
            return agentService.getAgentInstance(compositeKey);
        } catch (Exception e) {
            // Profile Agent 不存在，降级返回原始 Agent
            log.warn("Profile '{}' 未找到对应 Agent '{}'，降级返回原始 Agent", profile, agentName);
            return agentService.getAgentInstance(agentName);
        }
    }

    /**
     * 获取 Agent 的可用 Profile 列表。
     *
     * <p>
     * 从 AgentDefinition YAML 中的 {@code profiles} 节点读取。
     * 返回的列表包含每个 Profile 的名称、标签和描述。
     * 如果 Profile 配置中缺少标签或描述，使用名称作为默认值。
     * </p>
     *
     * @param agentName Agent 名称
     * @return Profile 信息列表，可能为空
     */
    public List<ProfileInfo> getAvailableProfiles(String agentName) {
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def == null || def.getProfiles() == null || def.getProfiles().isEmpty()) {
            return Collections.emptyList();
        }

        return def.getProfiles().entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    ProfileDefinition profile = entry.getValue();
                    return new ProfileInfo(
                            name,
                            profile.getLabel(),
                            profile.getDescription(),
                            null);
                })
                .toList();
    }
}
