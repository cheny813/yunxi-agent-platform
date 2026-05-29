package io.yunxi.platform.framework.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.exception.BadRequestException;

/**
 * 用户工作区懒加载服务
 *
 * <p>
 * 为通过 yunxiClaw 连接的个人用户按需创建隔离的 Agent 实例。
 * 每个用户的 Agent 有独立的工作区（{@code workspaceBasePath}/users/{userId}/{agentName}/），
 * 实现知识/技能/子Agent 的完全隔离。
 * </p>
 *
 * <p>
 * <b>路由规则</b>：
 * <ul>
 * <li>compositeKey = {@code agentName + "#" + userId}（与 ProfileRouter 的
 * {@code #} 模式一致）</li>
 * <li>缓存 key = {@code food-chat#zhangsan}，与全局 Agent 的 {@code food-chat}
 * 不冲突</li>
 * <li>workspace 路径 =
 * {@code .agentscope/workspace/users/zhangsan/food-chat/}</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class UserWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceService.class);

    private final AgentDomainService agentDomainService;
    private final AgentscopeCoreProperties coreProperties;
    private final AgentWorkspaceInitializer workspaceInitializer;

    public UserWorkspaceService(
            AgentDomainService agentDomainService,
            AgentscopeCoreProperties coreProperties,
            AgentWorkspaceInitializer workspaceInitializer) {
        this.agentDomainService = agentDomainService;
        this.coreProperties = coreProperties;
        this.workspaceInitializer = workspaceInitializer;
    }

    /**
     * 获取或创建用户级别的 Agent 实例。
     * <p>
     * 先查缓存，缓存不存在则懒加载创建。
     * 使用 {@code synchronized(compositeKey.intern())} 防止并发重复创建。
     * </p>
     *
     * @param agentName Agent 名称（如 "food-chat"）
     * @param userId    用户 ID（如 "zhangsan"）
     * @return Agent 实例（workspace 指向 users/{userId}/{agentName}/）
     */
    public Agent getOrCreateUserAgent(String agentName, String userId) {
        if (agentName == null || agentName.isBlank()) {
            throw new BadRequestException("Agent name 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId 不能为空");
        }

        String compositeKey = agentName + "#" + userId;

        // 先查缓存
        Agent existing = agentDomainService.findAgent(compositeKey);
        if (existing != null) {
            return existing;
        }

        // 懒加载创建（防并发）
        synchronized (compositeKey.intern()) {
            existing = agentDomainService.findAgent(compositeKey);
            if (existing != null) {
                return existing;
            }

            String workspacePath = coreProperties.getWorkspaceBasePath()
                    + "/users/" + userId + "/" + agentName;

            // 初始化工作区目录
            workspaceInitializer.initialize(agentName, null, null, workspacePath);
            log.info("初始化用户工作区: agent={}, userId={}, path={}", agentName, userId, workspacePath);

            // 使用默认配置创建 Agent
            AgentConfigDto config = new AgentConfigDto();
            config.setApiKey(coreProperties.getApiKey());
            config.setModelName(coreProperties.getModelName());
            config.setProvider(coreProperties.getProvider());
            config.setPrompt(coreProperties.getDefaultPrompt());
            config.setTemperature(0.7);
            config.setMaxTokens(4096);

            // 创建用户 Agent（workspace 指向 users/{userId}/{agentName}/）
            agentDomainService.createUserAgent(compositeKey, config, workspacePath);

            log.info("用户 Agent 创建成功: {}", compositeKey);

            return agentDomainService.findAgent(compositeKey);
        }
    }
}
