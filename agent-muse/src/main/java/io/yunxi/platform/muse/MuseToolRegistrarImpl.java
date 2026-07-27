package io.yunxi.platform.muse;

import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.agent.spi.MuseToolRegistrar;
import io.yunxi.platform.muse.tool.SkillEvalTool;
import io.yunxi.platform.muse.tool.SkillPruneTool;
import io.yunxi.platform.muse.tool.SkillRefineTool;
import org.springframework.beans.factory.ObjectProvider;
import lombok.extern.slf4j.Slf4j;

/**
 * 把 MUSE 三个自进化元工具挂入 AgentScope toolkit 的 {@code muse} 工具组。
 *
 * <p>三个元工具均通过 {@link ObjectProvider#getIfAvailable()} 懒取，仅当
 * {@code yunxi.muse.enabled=true} 时它们作为 Bean 存在，否则本注册器在
 * AgentConfigurer 侧直接被跳过，对未启用 MUSE 的 Agent 零侵入。</p>
 */
@Slf4j
public class MuseToolRegistrarImpl implements MuseToolRegistrar {

    private final ObjectProvider<SkillEvalTool> evalToolProvider;
    private final ObjectProvider<SkillRefineTool> refineToolProvider;
    private final ObjectProvider<SkillPruneTool> pruneToolProvider;

    public MuseToolRegistrarImpl(ObjectProvider<SkillEvalTool> evalToolProvider,
                                 ObjectProvider<SkillRefineTool> refineToolProvider,
                                 ObjectProvider<SkillPruneTool> pruneToolProvider) {
        this.evalToolProvider = evalToolProvider;
        this.refineToolProvider = refineToolProvider;
        this.pruneToolProvider = pruneToolProvider;
    }

    /**
     * 把三个元工具注册进 toolkit 的 muse 组（组默认激活）。
     *
     * @param toolkit AgentScope toolkit 实例
     */
    @Override
    public void registerTools(Toolkit toolkit) {
        SkillEvalTool evalTool = evalToolProvider.getIfAvailable();
        SkillRefineTool refineTool = refineToolProvider.getIfAvailable();
        SkillPruneTool pruneTool = pruneToolProvider.getIfAvailable();
        if (evalTool == null || refineTool == null || pruneTool == null) {
            log.warn("[muse] 元工具未全部就绪，跳过 toolkit 注册");
            return;
        }
        toolkit.createToolGroup("muse", "MUSE 自进化元工具", true);
        toolkit.registration().tool(evalTool).group("muse").apply();
        toolkit.registration().tool(refineTool).group("muse").apply();
        toolkit.registration().tool(pruneTool).group("muse").apply();
        log.info("[muse] 已注册 MUSE 元工具组: muse_skill_eval / muse_skill_evolve / muse_skill_prune");
    }
}
