package io.yunxi.runtime.middleware;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个 Agent 在 build 期装配的「yunxi 托管工具组」登记处（单次写入、调用期只读）。
 *
 * <p>AgentScope-Java 2.1.0-BETA1 把工具组作为框架原生概念：{@code Toolkit.registration().group(name)} 即创建一个组，
 * 每次调用的工具面由会话级 {@code AgentState.toolContext} 的激活组决定（{@code ReActAgent#activateSlotForContext}
 * 加载会话状态后，工具解析读取 {@code state.getToolContext().getActivatedGroups()}，全程不触碰共享 {@code Toolkit}
 * 的可变激活标志）。
 *
 * <p>本登记处只记录「该 Agent build 期装配的全部工具组名」，供 {@link SessionToolGroupActivationMiddleware}
 * 在每次调用把这些组并入会话级 toolContext，从而在不改动共享 toolkit、不做跨会话共享可变标志的前提下，保证配置
 * 的工具对会话可用。这样既能避免 MCP 工具因激活组被覆盖而返回 Unauthorized，也能让新上线的工具组对既有持久化
 * 会话即时生效。
 *
 * <p>本登记处是 Spring Bean，build 期写入、调用期只读，不持有第二条横切真相。HITL 权限上下文已在 build 期由
 * {@code io.yunxi.runtime.permission.PermissionTemplateResolver} 翻译并经 {@code builder.permissionContext(...)} 接线，
 * 与本登记处职责正交，故此处不重复处理。
 */
public class SessionToolGroupRegistry {

    private final Map<String, List<String>> plans = new ConcurrentHashMap<>();

    /** 登记某 Agent 的 build 期工具组名（build 期调用一次）。 */
    public void register(String agentName, List<String> activeGroups) {
        plans.put(agentName, List.copyOf(activeGroups));
    }

    /** 返回该 Agent 的 yunxi 托管工具组名；未知 Agent 返回空列表（中间件据此跳过）。 */
    public List<String> planFor(String agentName) {
        return plans.getOrDefault(agentName, List.of());
    }
}
