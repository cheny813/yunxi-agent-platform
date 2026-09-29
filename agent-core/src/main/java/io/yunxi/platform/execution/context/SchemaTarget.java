package io.yunxi.platform.execution.context;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 结构化输出 Schema 目标（驻留 agent-core 的数据契约）。
 *
 * <p>二选一：命名/默认 Schema 类，或请求内联 JSON Schema 节点。由统一执行引擎的
 * {@code StructuredBlockingStrategy} 写入 {@link #ATTR_SCHEMA_TARGET} 属性，
 * 门面层（ChatAppService）读取并据此从 {@code Msg} 提取结构化数据帧。</p>
 *
 * <p>该类型作为契约驻留 agent-core，避免 agent-core 反向依赖 agent-execution，
 * 从而从根上断开循环依赖。</p>
 *
 * @param schemaClass 命名/默认 Schema 类（内联 Schema 时为 null）
 * @param schemaNode  请求内联 JSON Schema 节点（命名 Schema 时为 null）
 * @author yunxi-agent-platform
 */
public record SchemaTarget(Class<?> schemaClass, JsonNode schemaNode) {

    /** 结构化目标在 ExecutionContext 属性中的键（供门面提取结构化数据） */
    public static final String ATTR_SCHEMA_TARGET = "structuredTarget";
}
