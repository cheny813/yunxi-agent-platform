package io.yunxi.runtime;

import io.agentscope.core.tool.Toolkit;

/**
 * MUSE 自进化元工具注册契约。
 *
 * <p>定义于运行时平面（装配点所在模块），供 {@link AgentConfigurer} 零侵入地挂载自进化元工具；
 * 真正实现在可选模块 yunxi-intelligence，仅当 yunxi.muse.enabled=true 时作为 Bean 存在。
 * 这样运行时平面不反向依赖智能平面，规避循环依赖。</p>
 */
public interface MuseToolRegistrar {

    /**
     * 把 MUSE 自进化元工具组挂入 toolkit。
     *
     * @param toolkit          AgentScope toolkit 实例
     * @param allowWriteTools  true 表示允许写类元工具（evolve / prune）；false 仅注册只读评估工具 eval。
     *                         「写技能」的前提是「该 Agent 能加载自己写出的技能」，否则写出来自己用不了；
     *                         该判定由 {@code AgentConfigurer} 基于技能模式统一裁决（与技能边界判定同源），此处仅执行。
     */
    void registerTools(Toolkit toolkit, boolean allowWriteTools);
}
