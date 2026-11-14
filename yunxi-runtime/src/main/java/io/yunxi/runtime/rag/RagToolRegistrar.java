package io.yunxi.runtime.rag;

import io.agentscope.core.tool.Toolkit;

/**
 * RAG 检索工具注册器（SPI 扩展点，仿 {@code MuseToolRegistrar}）。
 *
 * <p>产品平面在 {@code AgentConfigurer.buildToolkit} 遍历所有实现，把 RAG 检索工具挂入每个 Agent 的
 * toolkit 的 {@code rag} 工具组。未启用 RAG 时无实现，对 Agent 零侵入。</p>
 */
public interface RagToolRegistrar {
    void registerTools(Toolkit toolkit);
}
