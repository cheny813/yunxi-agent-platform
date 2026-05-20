package io.yunxi.platform.framework.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;

/**
 * 结构化输出工具
 * <p>
 * 实现 Agent 自主决定生成结构化数据的能力，支持：
 * 1. 根据 Schema 生成结构化 JSON 数据
 * 2. 自我纠错 - 当 JSON 解析失败时，提醒 Agent 切换使用 LLM 自身的结构化输出
 * </p>
 * 
 * <p>
 * 使用场景：
 * - Agent 分析任务后，认为需要输出结构化数据时主动调用
 * - 而不是被动等待外部强制要求结构化输出
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class GenerateResponseTool implements Tool {

    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "generate_response";
    }

    @Override
    public String getDescription() {
        return """
                生成结构化响应数据。当你认为需要返回结构化的业务数据（如用户信息、订单详情、分析结果等）时调用此工具。
                此工具会尝试将你的响应解析为指定的 JSON 格式。
                
                如果解析失败（JSON 格式异常），请注意工具返回的错误信息，
                此时应切换使用 LLM 自身的结构化输出能力（在响应开头使用特定标记）。
                
                调用场景：
                - 需要返回 JSON 格式的业务数据
                - 需要返回结构化的分析结果
                - 需要返回符合业务规范的响应
                
                不需要使用的场景：
                - 纯文本对话回复
                - 简单的确认或否定回答
                - 不需要结构化的长篇回答""";
    }

    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "response_content": {
                            "type": "string",
                            "description": "你需要返回的结构化内容，请以 JSON 格式提供"
                        },
                        "schema": {
                            "type": "string",
                            "description": "期望的 JSON Schema 描述（可选），用于验证和提示输出格式"
                        },
                        "fallback_hint": {
                            "type": "boolean",
                            "description": "是否需要回退提示。当解析失败时，如果为 true，工具会提供回退建议",
                            "default": true
                        }
                    },
                    "required": ["response_content"]
                }""";
    }

    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        String content = input.getString("response_content");
        String schema = input.getString("schema");
        boolean fallbackHint = input.getBoolean("fallback_hint", true);

        if (content == null || content.isBlank()) {
            throw new ToolExecutionException(getName(), "response_content 不能为空");
        }

        try {
            // 尝试解析为有效 JSON
            JsonNode jsonNode = objectMapper.readTree(content);
            
            // 验证是否为对象或数组
            if (!jsonNode.isObject() && !jsonNode.isArray()) {
                throw new ToolExecutionException(getName(), "JSON 根节点必须是对象或数组");
            }

            // 格式化输出
            String formattedJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(jsonNode);

            log.debug("成功生成结构化响应: {}", formattedJson);

            return ToolResult.success(formattedJson);

        } catch (ToolExecutionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());

            // 提供纠错建议
            String errorMsg = "JSON 解析失败: " + e.getMessage();
            
            if (fallbackHint) {
                String correctionHint = buildCorrectionHint(content, schema);
                return ToolResult.error(errorMsg + "\n\n" + correctionHint);
            }
            
            return ToolResult.error(errorMsg);
        }
    }

    /**
     * 构建纠错提示
     * <p>
     * 当 JSON 解析失败时，提供给 Agent 的回退建议
     * </p>
     */
    private String buildCorrectionHint(String content, String schema) {
        StringBuilder hint = new StringBuilder();
        hint.append("【自我纠错建议】\n");
        hint.append("检测到你尝试输出的 JSON 格式存在异常。");
        hint.append("请选择以下方式之一进行修正：\n\n");
        
        hint.append("方式1：修正 JSON 格式\n");
        hint.append("- 检查括号、引号、逗号是否匹配\n");
        hint.append("- 确保所有字符串值用双引号包裹\n");
        hint.append("- 移除 JSON 不允许的尾随逗号\n\n");
        
        hint.append("方式2：使用 LLM 自身的结构化输出\n");
        hint.append("当工具调用失败时，请直接在响应中使用以下格式：\n");
        hint.append("```json\n");
        
        if (schema != null && !schema.isBlank()) {
            hint.append("// 根据以下 Schema 输出结构化数据\n");
            hint.append(schema).append("\n");
        }
        
        hint.append("你的结构化数据...\n");
        hint.append("```\n\n");
        
        hint.append("提示：直接返回符合格式的 JSON 数据，框架会自动提取。");
        
        return hint.toString();
    }
}