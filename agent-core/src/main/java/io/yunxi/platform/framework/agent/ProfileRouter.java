package io.yunxi.platform.framework.agent;

import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    private static final Logger log = LoggerFactory.getLogger(ProfileRouter.class);

    private final AgentDomainService agentDomainService;
    private final AgentDefinitionLoader definitionLoader;

    public ProfileRouter(AgentDomainService agentDomainService, AgentDefinitionLoader definitionLoader) {
        this.agentDomainService = agentDomainService;
        this.definitionLoader = definitionLoader;
    }

    /**
     * 构建复合键：agentName + "#" + profileName
     */
    public String buildCompositeKey(String agentName, String profile) {
        return agentName + "#" + profile;
    }

    /**
     * 解析 Agent 实例
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称（null 或空 = 使用默认 Agent）
     * @return ReActAgent 实例
     */
    public Object resolve(String agentName, String profile) {
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
     *
     * @param agentName Agent 名称
     * @return Profile 信息列表
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