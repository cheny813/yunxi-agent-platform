package io.yunxi.platform.framework.agent;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.ProfileDefinition;
import io.yunxi.platform.shared.dto.ProfileInfo;

/**
 * Profile 路由器 — 框架核心服务
 * <p>
 * 职责：
 * <ul>
 * <li>根据 agentName + profileName 解析对应的 Agent 实例</li>
 * <li>无 profile 时回退到默认 Agent（向后兼容）</li>
 * <li>管理 Profile Agent 实例的缓存键</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ProfileRouter {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ProfileRouter.class);

    /** Agent 领域服务 — 获取 Agent 实例 */
    private final AgentDomainService agentDomainService;

    /** Agent 定义加载器 — 读取 Profile 配置（YAML 中的 profiles 字段） */
    private final AgentDefinitionLoader definitionLoader;

    /**
     * 构造 Profile 路由器
     *
     * @param agentDomainService Agent 领域服务
     * @param definitionLoader   Agent 定义加载器
     */
    public ProfileRouter(AgentDomainService agentDomainService, AgentDefinitionLoader definitionLoader) {
        this.agentDomainService = agentDomainService;
        this.definitionLoader = definitionLoader;
    }

    /**
     * 构建复合缓存键：agentName + "#" + profileName
     * <p>
     * AgentDomainService 中同个 Agent 的不同 Profile 实例通过此复合键区分存储。
     * 例如 "nutrition-assistant#dietitian" 是校园餐场景，使用 dietitian 配置的实例。
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称
     * @return 复合键字符串
     */
    public String buildCompositeKey(String agentName, String profile) {
        return agentName + "#" + profile;
    }

    /**
     * 解析 Agent 实例（支持 Profile 路由）
     * <p>
     * 路由规则：
     * <ol>
     * <li>profile 为 null 或空 → 直接返回默认 Agent 实例</li>
     * <li>profile 存在 → 构造复合键查询 Profile Agent 实例</li>
     * <li>Profile 未找到 → 记录警告日志，回退到默认 Agent 实例</li>
     * </ol>
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称（null 或空 = 使用默认 Agent）
     * @return Agent 实例（Profile 不存在时回退到默认）
     */
    public Agent resolve(String agentName, String profile) {
        if (profile == null || profile.isBlank()) {
            return agentDomainService.getAgentInstance(agentName);
        }

        String compositeKey = buildCompositeKey(agentName, profile);
        try {
            return agentDomainService.getAgentInstance(compositeKey);
        } catch (Exception e) {
            log.warn("Profile '{}' 不存在于 Agent '{}'，回退到默认 Agent", profile, agentName);
            return agentDomainService.getAgentInstance(agentName);
        }
    }

    /**
     * 获取 Agent 的可用 Profile 列表
     * <p>
     * 从 AgentDefinition YAML 配置的 {@code profiles} 字段读取，
     * 返回包含名称、标签、描述的 Profile 信息列表。
     * 无 Profile 配置时返回空列表。
     * </p>
     *
     * @param agentName Agent 名称
     * @return Profile 信息列表（可能为空）
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
