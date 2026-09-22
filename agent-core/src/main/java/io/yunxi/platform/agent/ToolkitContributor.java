package io.yunxi.platform.agent;

import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.shared.config.AgentDefinition;

/**
 * toolkit 构建期贡献者 SPI（框架级通用扩展点）。
 *
 * <p>在 Agent 的 {@link Toolkit} 构建完成、但尚未执行 {@code applyToolGroupActivation} 之前，
 * 核心（agent-core）统一回调所有实现，允许产品平面向 toolkit 注入额外工具或子 Agent。
 * 这是与 {@link AgentCustomizer}（构建后定制 Agent 实例）互补的构建期扩展点：
 * 需要在工具集生效前挂载的能力（如子 Agent、工作流触发工具）应走本扩展点。</p>
 *
 * <p>依赖方向：本接口定义在 agent-core（框架共有），产品平面模块（如 agent-aistio）实现并依赖 core，
 * 由 Spring 收集全部实现注入 {@code AgentConfigurer} 的构造器。核心只回调、不反向依赖产品平面。</p>
 *
 * <p>实现方约定：从 {@code def} 读取自身关心的声明，向 {@code toolkit} 注册能力；单条贡献失败应抛异常，
 * 由核心统一捕获并降级（单条失败不阻断其余 Agent 装配）。</p>
 */
public interface ToolkitContributor {

    /**
     * 把给定 Agent 定义上关心的声明编译并注册到 toolkit 上。
     *
     * @param def     待构建的 Agent 定义
     * @param toolkit 已构建、尚未执行 {@code applyToolGroupActivation} 的 Toolkit
     */
    void contribute(AgentDefinition def, Toolkit toolkit);
}
