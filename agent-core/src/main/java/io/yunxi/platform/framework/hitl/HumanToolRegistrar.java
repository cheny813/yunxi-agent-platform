package io.yunxi.platform.framework.hitl;

import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.shared.config.HumanToolConfig;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 人机协作工具注册器
 *
 * <p>将 HumanTool（如 ask_user）以 SchemaOnlyTool 模式注册到 Toolkit 中。
 * 当 LLM 调用这些工具时，AgentScope SDK 的 {@link io.agentscope.core.tool.ToolSuspendException}
 * 机制会触发 Agent 挂起，等待人类提供输入。
 *
 * <p>注册原理：
 * <ol>
 *   <li>通过 {@code Toolkit.registerSchema(ToolSchema)} 注册工具定义（只有 schema，没有执行逻辑）</li>
 *   <li>SDK 内部创建 {@code SchemaOnlyTool}，其 {@code callAsync()} 始终抛出 {@code ToolSuspendException}</li>
 *   <li>Agent 收到 {@code GenerateReason.TOOL_SUSPENDED} 后暂停，等待外部提供工具执行结果</li>
 * </ol>
 *
 * <p>不需要额外的框架基础设施——完全复用 SDK 原生的 ToolSuspendException 机制。
 *
 * @author yunxi-platform
 */
public class HumanToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(HumanToolRegistrar.class);

    /** 预定义的工具 schema 定义 */
    private static final Map<String, ToolSchemaDefinition> PREDEFINED_TOOLS = Map.of(
            "ask_user", new ToolSchemaDefinition(
                    "ask_user",
                    "向人类用户提问，获取信息、确认或决策。当需要人类专业知识、主观判断或敏感决策时使用此工具。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "question", Map.of(
                                            "type", "string",
                                            "description", "向人类提出的问题，应清晰、具体、包含必要的上下文"
                                    ),
                                    "options", Map.of(
                                            "type", "array",
                                            "items", Map.of("type", "string"),
                                            "description", "可选的回答选项列表，供人类选择"
                                    )
                            ),
                            "required", List.of("question")
                    )
            )
    );

    private final HumanToolConfig config;

    /**
     * 创建 HumanToolRegistrar
     *
     * @param config 人机协作配置
     */
    public HumanToolRegistrar(HumanToolConfig config) {
        this.config = config;
    }

    /**
     * 注册所有已配置的 HumanTool 到 Toolkit
     *
     * @param toolkit 目标 Toolkit
     */
    public void registerTools(Toolkit toolkit) {
        if (!config.isEnabled() || toolkit == null) {
            return;
        }

        List<String> toolNames = config.getTools();
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }

        for (String toolName : toolNames) {
            ToolSchema schema = buildSchema(toolName);
            if (schema != null) {
                toolkit.registerSchema(schema);
                log.info("已注册 HumanTool: {} (SchemaOnlyTool)", toolName);
            } else {
                log.warn("未知的 HumanTool 名称: {}，跳过注册", toolName);
            }
        }
    }

    /**
     * 根据工具名称构建 ToolSchema
     */
    private ToolSchema buildSchema(String toolName) {
        ToolSchemaDefinition def = PREDEFINED_TOOLS.get(toolName);
        if (def != null) {
            return ToolSchema.builder()
                    .name(def.name())
                    .description(def.description())
                    .parameters(def.parameters())
                    .build();
        }

        // 对于未预定义的工具，创建一个通用的 schema
        return ToolSchema.builder()
                .name(toolName)
                .description("向人类用户请求输入或确认的工具")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "message", Map.of(
                                        "type", "string",
                                        "description", "向人类展示的消息内容"
                                )
                        ),
                        "required", List.of("message")
                ))
                .build();
    }

    /**
     * 工具 Schema 定义记录
     */
    private record ToolSchemaDefinition(String name, String description, Map<String, Object> parameters) {}
}