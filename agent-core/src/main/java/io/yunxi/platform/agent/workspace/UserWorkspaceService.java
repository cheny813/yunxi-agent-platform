package io.yunxi.platform.agent.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.exception.BadRequestException;

/**
 * 用户工作空间服务。
 *
 * <p>
 * 支持多用户场景下（如 yunxiClaw 前端调用），每个用户拥有独立的 Agent 实例，
 * 且 Agent 的工作空间相互隔离。路径格式遵循底层 agentscope-java-v2.0 框架设计：
 * agent 优先，users 嵌套在 agent 下，即
 * {@code workspaceBasePath}/{agentName}/users/{userId}/}。
 * </p>
 *
 * <p>
 * <b>路由逻辑</b>：
 * <ul>
 * <li>compositeKey = {@code agentName + "#" + userId}，与 ProfileRouter 的
 * {@code #} 分隔符一致</li>
 * <li>例如 key = {@code food-chat#zhangsan}，代表 Agent {@code food-chat}
 * 的用户版</li>
 * <li>workspace 路径 =
 * {@code .agentscope/workspace/food-chat/users/zhangsan/}</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Service
public class UserWorkspaceService {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceService.class);

    /** Agent 服务，用于查找和创建 Agent 实例 */
    private final AgentService agentService;

    /** 核心配置属性，提供工作空间根路径、默认模型等配置 */
    private final AgentscopeCoreProperties coreProperties;

    /** 工作空间初始化器，用于创建用户工作空间目录结构 */
    private final AgentWorkspaceInitializer workspaceInitializer;

    /**
     * 构造用户工作空间服务。
     *
     * @param agentService         Agent 服务
     * @param coreProperties       核心配置属性
     * @param workspaceInitializer 工作空间初始化器
     */
    public UserWorkspaceService(
            AgentService agentService,
            AgentscopeCoreProperties coreProperties,
            AgentWorkspaceInitializer workspaceInitializer) {
        this.agentService = agentService;
        this.coreProperties = coreProperties;
        this.workspaceInitializer = workspaceInitializer;
    }

    /**
     * 获取或创建用户专属的 Agent 实例。
     *
     * <p>
     * 使用双重检查锁定确保并发安全：
     * 1. 外层检查：先尝试查找已存在的 Agent，避免不必要的同步开销
     * 2. 内层同步块：使用 {@code synchronized(compositeKey.intern())} 实现细粒度锁，
     * 确保同一用户的同一 Agent 只创建一次
     * </p>
     *
     * <p>
     * 创建流程：
     * 1. 验证 agentName 和 userId 参数
     * 2. 构建组合键 agentName#userId
     * 3. 双重检查是否已存在
     * 4. 初始化用户工作空间目录
     * 5. 使用全局默认配置创建 Agent
     * 6. 返回新创建的 Agent 实例
     * </p>
     *
     * @param agentName Agent 名称（如 "food-chat"）
     * @param userId    用户 ID（如 "zhangsan"）
     * @return Agent 实例，workspace 路径为 {agentName}/users/{userId}/
     * @throws BadRequestException agentName 或 userId 为空时抛出
     */
    public Agent getOrCreateUserAgent(String agentName, String userId) {
        if (agentName == null || agentName.isBlank()) {
            throw new BadRequestException("Agent name 不能为空");
        }
        if (userId == null || userId.isBlank()) {
            throw new BadRequestException("userId 不能为空");
        }

        String compositeKey = agentName + "#" + userId;

        // 外层检查：快速路径，避免不必要的同步开销
        Agent existing = agentService.findAgent(compositeKey);
        if (existing != null) {
            return existing;
        }

        // 内层同步块：使用组合键的 intern() 作为锁对象，实现细粒度锁
        synchronized (compositeKey.intern()) {
            // 再次检查，防止其他线程已创建
            existing = agentService.findAgent(compositeKey);
            if (existing != null) {
                return existing;
            }

            // 构建用户专属工作空间路径：框架设计为 agent 优先 → {agentName}/users/{userId}/
            String workspacePath = coreProperties.getWorkspaceBasePath()
                    + "/" + agentName + "/users/" + userId;

            // 初始化工作空间目录结构
            workspaceInitializer.initialize(agentName, null, null, workspacePath);
            log.info("用户工作空间初始化完成: agent={}, userId={}, path={}", agentName, userId, workspacePath);

            // 构建默认配置创建 Agent
            AgentConfigDto config = new AgentConfigDto();
            config.setApiKey(coreProperties.getApiKey());
            config.setModelName(coreProperties.getModelName());
            config.setProvider(coreProperties.getProvider());
            config.setPrompt(coreProperties.getDefaultPrompt());
            config.setTemperature(0.7);
            config.setMaxTokens(4096);

            // 创建 Agent，workspace 路径为 {agentName}/users/{userId}/
            agentService.createUserAgent(compositeKey, config, workspacePath);

            log.info("用户 Agent 创建完成: {}", compositeKey);

            return agentService.findAgent(compositeKey);
        }
    }
}
