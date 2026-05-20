package io.yunxi.platform.framework.controller;

import io.yunxi.platform.framework.agent.AgentConfigDtoCache;
import io.yunxi.platform.framework.agent.AgentDomainService;
import io.yunxi.platform.framework.agent.ProfileRouter;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.dto.ProfileInfo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 管理控制器
 * <p>
 * 专注于Agent本身的管理，包括创建、查询、监控和缓存管理
 * <b>所有对话功能请参考 ConversationController</b>
 * </p>
 * <p>
 * <b>职责范围</b>：
 * <ul>
 * <li>Agent CRUD：创建、删除、查询、列表</li>
 * <li>Agent状态监控：运行时信息和能力清单</li>
 * <li>缓存管理：Agent配置缓存清理和统计</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    /**
     * Agent 领域服务
     */
private final AgentDomainService agentDomainService;
    private final AgentConfigDtoCache agentConfigDtoCache;
    private final ProfileRouter profileRouter;

    public AgentController(AgentDomainService agentDomainService,
            AgentConfigDtoCache agentConfigDtoCache,
            ProfileRouter profileRouter) {
        this.agentDomainService = agentDomainService;
        this.agentConfigDtoCache = agentConfigDtoCache;
        this.profileRouter = profileRouter;
    }

    /**
     * 健康检查与系统统计
     * <p>
     * 返回服务状态、基础统计信息和Agent缓存状态，用于监控和调试
     * 支持health和Stats功能，提供统一的监控端点
     * </p>
     *
     * <p>
     * <b>返回信息包括</b>：
     * <ul>
     * <li>服务状态和时间戳</li>
     * <li>Agent数量和缓存统计</li>
     * <li>缓存详情（大小、过期统计、配置等）</li>
     * </ul>
     * </p>
     *
     * @param detailed 是否包含详细的缓存统计信息（可选，默认为false）
     * @return 完整的系统状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health(
            @RequestParam(required = false, defaultValue = "false") boolean detailed) {

        Map<String, Object> baseInfo = Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString(),
                "agents", agentDomainService.countAgents());

        if (detailed) {
            AgentConfigDtoCache.CacheStats cacheStats = agentConfigDtoCache.getStats();

            return Map.of(
                    "status", "ok",
                    "timestamp", Instant.now().toString(),
                    "agents", agentDomainService.countAgents(),
                    "cache", Map.of(
                            "size", cacheStats.size(),
                            "expiredCount", cacheStats.expiredCount(),
                            "maxSize", cacheStats.maxSize(),
                            "ttl", cacheStats.ttl()));
        }

        return baseInfo;
    }

    /**
     * 缓存清理（管理员功能）
     */
    @DeleteMapping("/cache")
    public Map<String, Object> cleanupCache(
            @RequestParam(required = false, defaultValue = "true") boolean detailed) {

        var before = agentConfigDtoCache.getStats();
        int cleaned = agentConfigDtoCache.cleanupExpired();
        var after = agentConfigDtoCache.getStats();

        if (detailed) {
            return Map.of(
                    "success", true,
                    "cleaned", cleaned,
                    "cache", Map.of(
                            "before", before.size(),
                            "after", after.size(),
                            "freed", before.expiredCount()));
        }
        return Map.of("success", true, "cleaned", cleaned);
    }

    /**
     * 列出所有Agent
     * <p>
     * 返回系统中所有已配置的Agent信息
     * 支持两种路径格式：
     * <ul>
     * <li>/agents/ - 带末尾斜杠</li>
     * <li>/agents - 不带末尾斜杠</li>
     * </ul>
     * 这样设计是为了兼容前端不同的调用方式，避免与静态资源路径冲突
     * </p>
     *
     * @return Agent 信息列表
     */
    @GetMapping({ "/", "" })
    public List<AgentInfoDto> listAgents() {
        return agentDomainService.listAgents();
    }

    /**
     * 获取指定 Agent 信息
     * <p>
     * 根据Agent名称查询详细的配置信息
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 信息
     */
    @GetMapping("/{name}")
    public AgentInfoDto getAgent(@PathVariable String name) {
        return agentDomainService.getAgent(name);
    }

    /**
     * 创建Agent
     * <p>
     * 创建新的 Agent 配置或更新已存在的Agent
     * </p>
     *
     * @param name   Agent 名称（路径参数）
     * @param config Agent 配置对象
     * @return Agent 信息
     */
    @PostMapping("/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentInfoDto createAgent(@PathVariable String name,
            @RequestBody(required = false) AgentConfigDto config) {
        return agentDomainService.createAgent(name, config);
    }

    /**
     * 删除Agent
     */
    @DeleteMapping("/{name}")
    public void deleteAgent(@PathVariable String name) {
        agentDomainService.deleteAgent(name);
    }

    /**
     * 获取Agent运行时状态和能力清单
     * <p>
     * 返回Agent的当前运行时信息和能力支持，用于调试、监控和客户端适配
     * </p>
     *
     * @param name Agent名称
     * @param mode 查询模式: "info"(默认) / "capabilities" / "all"
     * @return Agent运行时信息或能力清单
     */
    @GetMapping("/{name}/inspect")
    public Object inspectAgent(
            @PathVariable String name,
            @RequestParam(required = false, defaultValue = "all") String mode) {
        try {
            var agent = agentDomainService.getAgentInstance(name);

            var caps = Map.of(
                    "chat", true,
                    "stream", true,
                    "structuredOutput", true,
                    "memory", true,
                    "tools", true);

            var info = Map.of(
                    "agentId", agent.getAgentId(),
                    "name", agent.getName(),
                    "maxIters", agent.getMaxIters(),
                    "model", agent.getModel().getModelName());

            return switch (mode.toLowerCase()) {
                case "info" -> Map.of("info", info);
                case "capabilities" -> Map.of("capabilities", caps);
                default -> Map.of("info", info, "capabilities", caps);
            };

        } catch (Exception e) {
            return Map.of("error", e.getMessage());
}
    }

    /**
     * 获取 Agent 的可用 Profile 列表
     * <p>
     * 返回该 Agent 配置的所有 Profile，每个 Profile 有不同的工具集和运行参数。
     * 前端根据此列表展示 Profile 选择器。
     * </p>
     *
     * @param name Agent 名称
     * @return Profile 信息列表
     */
    @GetMapping("/{name}/profiles")
    public List<ProfileInfo> getAgentProfiles(@PathVariable String name) {
        return profileRouter.getAvailableProfiles(name);
    }
}
