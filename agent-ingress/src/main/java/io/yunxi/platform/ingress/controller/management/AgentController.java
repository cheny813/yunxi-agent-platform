package io.yunxi.platform.ingress.controller.management;

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
 * 提供 Agent 的 CRUD 操作和健康检查端点。
 * <b>对话相关操作请使用 ConversationController</b>
 * </p>
 * <p>
 * <b>功能：</b>
 * <ul>
 * <li>Agent CRUD 操作（创建/读取/更新/删除）</li>
 * <li>Agent 健康检查和状态查询</li>
 * <li>查询已注册 Agent 的详细信息</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/agents")
public class AgentController {

    /** Agent 服务，负责 Agent 生命周期和查找入口 */
    private final AgentService agentDomainService;

    /** Profile 路由，管理 Profile 路由 */
    private final ProfileRouter profileRouter;

    /**
     * 构造 Agent 控制器
     *
     * @param agentDomainService Agent 服务，负责 Agent 生命周期和查找入口
     * @param profileRouter      Profile 路由，管理 Profile 路由
     */
    public AgentController(AgentService agentDomainService,
            ProfileRouter profileRouter) {
        this.agentDomainService = agentDomainService;
        this.profileRouter = profileRouter;
    }

    /**
     * 系统健康检查
     * <p>
     * 返回平台健康状态，包括 Agent 数量。
     * </p>
     *
     * @return 健康状态信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "timestamp", Instant.now().toString(),
                "agents", agentDomainService.countAgents());
    }

    /**
     * 列出所有 Agent
     * <p>
     * 返回所有已注册 Agent 的概要信息。
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
     * 获取指定 Agent
     * <p>
     * 根据名称获取 Agent 的配置详细信息
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
     * 创建 Agent
     * <p>
     * 根据名称和配置创建新的 Agent 实例
     * </p>
     *
     * @param name   Agent 唯一名称
     * @param config Agent 配置
     * @return 创建的 Agent 信息
     */
    @PostMapping("/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentInfoDto createAgent(@PathVariable String name,
            @RequestBody(required = false) AgentConfigDto config) {
        return agentDomainService.createAgent(name, config);
    }

    /**
     * 删除 Agent
     */
    @DeleteMapping("/{name}")
    public void deleteAgent(@PathVariable String name) {
        agentDomainService.deleteAgent(name);
    }

    /**
     * 查看 Agent 详细信息
     * <p>
     * 返回 Agent 的真实运行标识（agentId / name），不含任何推断或硬编码的能力声明。
     * 能力探测不属于 HTTP 边缘层职责，统一由内核执行引擎在运行期兑现。
     * </p>
     *
     * @param name Agent 名称
     * @return Agent 真实信息
     */
    @GetMapping("/{name}/inspect")
    public Object inspectAgent(@PathVariable String name) {
        try {
            var agent = agentDomainService.getAgentInstance(name);
            return Map.of(
                    "agentId", agent.getAgentId(),
                    "name", agent.getName());
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    /**
     * 获取 Agent 的 Profile 列表
     * <p>
     * 列出 Agent 关联的所有 Profile，用于路由和配置管理。
     * 返回该 Agent 可用的 Profile 列表。
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
