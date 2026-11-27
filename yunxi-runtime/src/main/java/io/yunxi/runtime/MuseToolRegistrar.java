package io.yunxi.runtime;

import io.agentscope.core.tool.Toolkit;

/**
 * MUSE 自进化元工具注册契约。
 *
 * <p>定义于运行时平面（装配点所在模块），供 {@link AgentConfigurer} 零侵入地挂载自进化元工具；
 * 真正实现在可选模块 yunxi-intelligence，仅当 yunxi.muse.enabled=true 时作为 Bean 存在。
 * 这样运行时平面不反向依赖智能平面，规避循环依赖。</p>
 *
 * <p><b>是否挂载由装配点统一裁决</b>：本契约只负责「往 toolkit 注册整组元工具」，不携带
 * 「该不该注册 / 注册子集」的判定——写类与只读工具对不具备技能能力的 Agent 同为幻觉诱饵
 * （实证事故），部分注册的降级语义已删除；门控判定唯一持有者是 {@code AgentConfigurer}
 * 的技能边界判定（与技能边界同源）。</p>
 */
public interface MuseToolRegistrar {

    /**
     * 把 MUSE 自进化元工具组（muse_skill_eval / muse_skill_evolve / muse_skill_prune）挂入 toolkit。
     *
     * @param toolkit AgentScope toolkit 实例
     */
    void registerTools(Toolkit toolkit);
}
