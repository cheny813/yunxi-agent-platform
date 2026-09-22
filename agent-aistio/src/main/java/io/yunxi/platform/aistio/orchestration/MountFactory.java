package io.yunxi.platform.aistio.orchestration;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.yunxi.platform.agent.ToolkitContributor;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.aistio.orchestration.workflow.WorkflowTool;
import io.yunxi.platform.aistio.orchestration.workflow.WorkflowRunStateStore;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.MountBodyConfig;
import io.yunxi.platform.shared.config.MountConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;

/**
 * 编排挂载工厂（产品平面实现框架级 {@link ToolkitContributor}）。
 *
 * <p>把 {@code AgentDefinition.orchestration.mounts} 编译并挂载到 toolkit 上，实现「工作流挂载融合」：
 * <ul>
 *   <li><b>通道①（kind=agent）</b>：目标 Agent 经 {@code toolkit.registration().subAgent(...)} 挂为子 Agent；
 *       每次 {@code provide()} 由 {@link AgentService#getAgentInstance} 返回新实例（框架 SubAgentProvider 设计意图，
 *       避免单例并发），与 Supervisor 专家挂载同源。</li>
 *   <li><b>通道②（kind=workflow）</b>：定义引用（{@code body.definition}）经 {@link WorkflowTool} 挂为工作流触发工具；
 *       内联 {@code body.graph} 在 M3 不直接触发（需先由 OrchestrationTranslator 导出并在控制台导入定义）。</li>
 * </ul>
 *
 * <p>依赖方向：本类在 agent-aistio，依赖 agent-core 定义的框架级接口与配置类（正向），由 Spring 收集为
 * {@code ToolkitContributor} 注入 agent-core 的 {@code AgentConfigurer}（核心只回调、不反向依赖产品平面）。</p>
 *
 * <p>单 Agent 多工作流挂载在 M3 仅支持一个（工具名固定，多实例会冲突），超出部分记警告并跳过。</p>
 */
public class MountFactory implements ToolkitContributor {

    private static final Logger log = LoggerFactory.getLogger(MountFactory.class);

    private final AgentService agentService;
    private final OrchestrationTrigger trigger;
    private final WorkflowRunStateStore runStateStore;

    public MountFactory(AgentService agentService, OrchestrationTrigger trigger, WorkflowRunStateStore runStateStore) {
        this.agentService = agentService;
        this.trigger = trigger;
        this.runStateStore = runStateStore;
    }

    @Override
    public void contribute(AgentDefinition def, Toolkit toolkit) {
        OrchestrationConfig orch = def.getOrchestration();
        if (orch == null) {
            return;
        }
        List<MountConfig> mounts = orch.getMounts();
        if (mounts == null || mounts.isEmpty()) {
            return;
        }
        boolean workflowMounted = false;
        for (MountConfig mount : mounts) {
            MountBodyConfig body = mount.getBody();
            if (body == null) {
                log.warn("[mount] 挂载声明缺少 body，已跳过 agent={} mount={}", def.getName(), mount.getName());
                continue;
            }
            String kind = body.getKind();
            if ("agent".equalsIgnoreCase(kind)) {
                contributeAgent(mount, body, toolkit);
            } else if ("workflow".equalsIgnoreCase(kind)) {
                if (workflowMounted) {
                    log.warn("[mount] 单 Agent 多工作流挂载在 M3 不支持（工具名冲突），跳过 mount={} agent={}",
                            mount.getName(), def.getName());
                    continue;
                }
                contributeWorkflow(mount, body, toolkit);
                workflowMounted = true;
            } else {
                log.warn("[mount] 未知挂载类型 kind={} 已跳过 agent={} mount={}", kind, def.getName(), mount.getName());
            }
        }
    }

    private void contributeAgent(MountConfig mount, MountBodyConfig body, Toolkit toolkit) {
        String ref = body.getRef();
        if (ref == null || ref.isBlank()) {
            log.warn("[mount] 子 Agent 挂载缺少 ref（目标 Agent 名），已跳过 mount={}", mount.getName());
            return;
        }
        ensureGroup(toolkit, "agent", "子 Agent 工具组");
        try {
            toolkit.registration()
                    .subAgent(() -> agentService.getAgentInstance(ref),
                            SubAgentConfig.builder().forwardEvents(true).build())
                    .group("agent").apply();
            log.info("[mount] 已挂子 Agent ref={} 到 agent 组", ref);
        } catch (Exception e) {
            log.error("[mount] 挂子 Agent 失败 ref={} mount={}", ref, mount.getName(), e);
        }
    }

    private void contributeWorkflow(MountConfig mount, MountBodyConfig body, Toolkit toolkit) {
        String defKey = body.getDefinition();
        if (defKey == null || defKey.isBlank()) {
            log.warn("[mount] 工作流挂载缺少 definition 引用（内联 graph 需先由控制台导入定义），已跳过 agent={} mount={}",
                    mount.getName());
            return;
        }
        String group = (mount.getGroup() == null || mount.getGroup().isBlank()) ? "workflow" : mount.getGroup();
        ensureGroup(toolkit, group, "工作流工具组: " + defKey);
        WorkflowTool tool = new WorkflowTool(defKey, trigger, runStateStore);
        toolkit.registration().tool(tool).group(group).apply();
        log.info("[mount] 已挂工作流工具 definitionKey={} group={}", defKey, group);
    }

    /** 仅在组尚不存在时创建，避免同一 toolkit 上重复创建（如 Supervisor 路径已建 agent 组）抛异常 */
    private static void ensureGroup(Toolkit toolkit, String group, String description) {
        if (toolkit.getToolGroup(group) == null) {
            toolkit.createToolGroup(group, description, true);
        }
    }
}
