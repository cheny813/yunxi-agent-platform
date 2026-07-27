package io.yunxi.platform.agent.spi;

import io.agentscope.core.tool.Toolkit;

/**
 * MUSE 自进化元工具注册契约。
 *
 * <p>定义于 agent-core，供 AgentConfigurer 零侵入地挂载自进化元工具；
 * 真正实现在可选模块 agent-muse，仅当 yunxi.muse.enabled=true 时作为 Bean 存在。
 * 这样 agent-core 不反向依赖 agent-muse，规避循环依赖。</p>
 */
public interface MuseToolRegistrar {

    /** 把 MUSE 自进化元工具组（eval / evolve / prune）挂入 toolkit。 */
    void registerTools(Toolkit toolkit);
}
