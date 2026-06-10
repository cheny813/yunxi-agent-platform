package io.yunxi.platform.controller;

import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.shared.dto.AgentConfigDto;
import io.yunxi.platform.shared.dto.AgentInfoDto;
import io.yunxi.platform.shared.dto.ProfileInfo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Agent 控制器
 * <p>
 * 提供Agent的CRUD操作和健康检查端点。
 * <b>对话相关操作请使用ConversationController</b>
 * </p>
 * <p>
 * <b>功能：</b>
 * <ul>
 * <li>Agent CRUD操作（创建/读取/更新/删除）</li>
 * <li>Agent健康检查和状态查询</li>
 * <li>查询已注册Agent的详细信息</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    /** Agent 服务，负责 Agent 生命周期和查找入口 */
    private final AgentService agentDomainService;

    /** Profile 路由，管理 Profile 路由 */
    private final ProfileRouter profileRouter;

    public AgentController(AgentService agentDomainService,
            ProfileRouter profileRouter) {
        this.agentDomainService = agentDomainService;
        this.profileRouter = profileRouter;
    }

    /**
     * 系统健康检查
     * <p>
     * 返回平台健康状态，包括Agent数量和缓存状态。
     * 默认返回基础信息，设置detailed=true可获取完整状态。
     * </p>
     *
     * <p>
     * <b>返回内容：</b>
     * <ul>
     * <li>服务运行状态</li>
     * <li>Agent数量统计</li>
     * <li>详细模式下包含缓存信息</li>
     * </ul>
     * </p>
     *
     * @param detailed 是否返回详细信息，默认false
     * @return 健康状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health(
            @RequestParam(required = false, defaultValue = "false") boolean detailed) {

        Map<String, Object> baseInfo = Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString(),
                "agents", agentDomainService.countAgents());

        if (detailed) {
            return Map.of(
                    "status", "ok",
                    "timestamp", Instant.now().toString(),
                    "agents", agentDomainService.countAgents(),
                    "cache", Map.of(
                            "note", "AgentConfigDtoCache removed in V2.0"));
        }

        return baseInfo;
    }

    /**
     * 清理Agent缓存（V2.0中AgentConfigDtoCache已移除）
     */
    @DeleteMapping("/cache")
    public Map<String, Object> cleanupCache() {
        return Map.of("success", true, "cleaned", 0,
                "note", "AgentConfigDtoCache removed in V2.0");
    }

    /**
     * 列出所有Agent
     * <p>
     * 返回所有已注册Agent的概要信息。
     * 支持以下路径：
     * <ul>
     * <li>/agents/ - 带尾部斜杠</li>
     * <li>/agents - 不带尾部斜杠</li>
     * </ul>
     * 两种路径返回相同结果
     * </p>
     *
     * @return Agent 列表
     */
    @GetMapping({ "/", "" })
    public List<AgentInfoDto> listAgents() {
        return agentDomainService.listAgents();
    }

    /**
     * 获取指定Agent
     * <p>
     * 根据名称获取Agent的配置详细信息
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 详细信息
     */
    @GetMapping("/{name}")
    public AgentInfoDto getAgent(@PathVariable String name) {
        return agentDomainService.getAgent(name);
    }

    /**
     * 创建Agent
     * <p>
     * 根据名称和配置创建新的Agent实例
     * </p>
     *
     * @param name   Agent 唯一名称
     * @param config Agent 配置
     * @return 创建的Agent信息
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
     * 查看Agent详细信息
     * <p>
     * 查看Agent的内部状态，包括能力列表和运行参数
     * </p>
     *
     * @param name Agent名称
     * @param mode 查看模式："info"(基本信息) / "capabilities"(能力) / "all"(全部)
     * @return Agent详细信息
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
                    "maxIters", "N/A (managed by HarnessAgent)",
                    "model", agent.getAgentId());

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
     * 获取Agent的Profile列表
     * <p>
     * 列出Agent关联的所有Profile，用于路由和配置管理。
     * 返回该Agent可用的Profile列表。
     * </p>
     *
     * @param name Agent 名称
     * @return Profile 列表
     */
    @GetMapping("/{name}/profiles")
    public List<ProfileInfo> getAgentProfiles(@PathVariable String name) {
        return profileRouter.getAvailableProfiles(name);
    }
}
