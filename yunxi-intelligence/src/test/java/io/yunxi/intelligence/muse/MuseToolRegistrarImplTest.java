package io.yunxi.intelligence.muse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.Toolkit.ToolRegistration;
import io.yunxi.intelligence.muse.tool.SkillEvalTool;
import io.yunxi.intelligence.muse.tool.SkillPruneTool;
import io.yunxi.intelligence.muse.tool.SkillRefineTool;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

/**
 * MUSE 元工具注册验证（离线）：{@link MuseToolRegistrarImpl#registerTools} 必须把三个自进化元工具
 * 整组挂入 toolkit 的 {@code muse} 工具组（组默认激活）。是否挂载由 {@code AgentConfigurer} 的技能
 * 边界判定统一裁决（NONE 的 Agent 整组不注册），本实现不携带判定。
 */
class MuseToolRegistrarImplTest {

    private record Harness(
            Toolkit toolkit,
            ToolRegistration reg,
            SkillEvalTool evalTool,
            SkillRefineTool refineTool,
            SkillPruneTool pruneTool) {}

    private Harness build() {
        ObjectProvider<SkillEvalTool> evalProvider = mock(ObjectProvider.class);
        ObjectProvider<SkillRefineTool> refineProvider = mock(ObjectProvider.class);
        ObjectProvider<SkillPruneTool> pruneProvider = mock(ObjectProvider.class);
        SkillEvalTool evalTool = mock(SkillEvalTool.class);
        SkillRefineTool refineTool = mock(SkillRefineTool.class);
        SkillPruneTool pruneTool = mock(SkillPruneTool.class);
        when(evalProvider.getIfAvailable()).thenReturn(evalTool);
        when(refineProvider.getIfAvailable()).thenReturn(refineTool);
        when(pruneProvider.getIfAvailable()).thenReturn(pruneTool);

        MuseToolRegistrarImpl registrar =
                new MuseToolRegistrarImpl(evalProvider, refineProvider, pruneProvider);

        Toolkit toolkit = mock(Toolkit.class);
        ToolRegistration reg = mock(ToolRegistration.class);
        when(toolkit.registration()).thenReturn(reg);
        when(reg.tool(any())).thenReturn(reg);
        when(reg.group(anyString())).thenReturn(reg);

        registrar.registerTools(toolkit);
        return new Harness(toolkit, reg, evalTool, refineTool, pruneTool);
    }

    @Test
    void registerTools_registersAllThreeToolsInMuseGroup() {
        Harness h = build();
        verify(h.reg()).tool(h.evalTool());
        verify(h.reg()).tool(h.refineTool());
        verify(h.reg()).tool(h.pruneTool());
        verify(h.toolkit()).createToolGroup(eq("muse"), anyString(), eq(true));
    }

    @Test
    void registerTools_skipsWhenAnyToolMissing() {
        ObjectProvider<SkillEvalTool> evalProvider = mock(ObjectProvider.class);
        ObjectProvider<SkillRefineTool> refineProvider = mock(ObjectProvider.class);
        ObjectProvider<SkillPruneTool> pruneProvider = mock(ObjectProvider.class);
        when(evalProvider.getIfAvailable()).thenReturn(null); // 元工具未就绪
        when(refineProvider.getIfAvailable()).thenReturn(mock(SkillRefineTool.class));
        when(pruneProvider.getIfAvailable()).thenReturn(mock(SkillPruneTool.class));

        MuseToolRegistrarImpl registrar =
                new MuseToolRegistrarImpl(evalProvider, refineProvider, pruneProvider);

        Toolkit toolkit = mock(Toolkit.class);
        ToolRegistration reg = mock(ToolRegistration.class);
        when(toolkit.registration()).thenReturn(reg);
        when(reg.tool(any())).thenReturn(reg);
        when(reg.group(anyString())).thenReturn(reg);

        registrar.registerTools(toolkit);

        verify(toolkit, Mockito.never()).createToolGroup(eq("muse"), anyString(), eq(true));
    }
}
