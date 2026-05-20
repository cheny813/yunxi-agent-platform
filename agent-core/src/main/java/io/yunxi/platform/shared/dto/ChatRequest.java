package io.yunxi.platform.shared.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 对话请求数据传输对象
 *
 * <p>
 * 封装用户发送给 Agent 的对话请求，支持普通对话和结构化输出两种模式。
 * 结构化输出通过 JSON Schema 定义输出格式，强制模型返回符合规范的结构化数据。
 * </p>
 *
 * <h3>普通对话模式：</h3>
 *
 * <pre>
 * {
 *   "message": "请介绍一下 Spring Boot"
 * }
 * </pre>
 *
 * <h3>结构化输出模式：</h3>
 *
 * <pre>
 * {
 *   "message": "请分析这段代码的问题",
 *   "schema": {
 *     "type": "object",
 *     "properties": {
 *       "issues": {
 *         "type": "array",
 *         "items": {
 *           "type": "object",
 *           "properties": {
 *             "line": {"type": "integer"},
 *             "description": {"type": "string"},
 *             "severity": {"type": "string", "enum": ["error", "warning", "info"]}
 *           }
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @see ChatResponse 对话响应
 * @see UnifiedChatRequest 统一对话请求
 */
public class ChatRequest {

    /**
     * 用户输入消息（必填）
     *
     * <p>
     * 用户发送给 Agent 的自然语言文本，可以包含问题、指令或任意内容。
     * Agent 会根据系统提示词和上下文理解并响应该消息。
     * </p>
     */
    @NotBlank(message = "message 不能为空")
    private String message;

    /**
     * 结构化输出 Schema（可选）
     *
     * <p>
     * 使用 JSON Schema 语法定义期望的输出格式。
     * 设置后，Agent 将强制输出符合此 Schema 的结构化数据，
     * 而不是自由文本。适用于数据提取、表单填充等场景。
     * </p>
     *
     * <p>
     * 示例：强制输出包含 answer 字段的 JSON 对象
     * </p>
     *
     * <pre>
     * {
     *   "type": "object",
     *   "properties": {
     *     "answer": {"type": "string"}
     *   },
     *   "required": ["answer"]
     * }
     * </pre>
     */
    private Map<String, Object> schema;

    /**
     * 是否启用思考过程（可选，默认 false）
     *
     * <p>
     * 启用后，模型会在给出最终回答前展示推理过程，
     * 类似于 Chain-of-Thought（思维链）技术。
     * 适用于需要展示推理逻辑的复杂问题。
     * </p>
     */
    private Boolean enableThinking;

    /**
     * 流式输出的分块大小（可选，默认 50）
     *
     * <p>
     * 在流式输出模式下，每个 SSE 事件包含的字符数。
     * 较小的值意味着更频繁的前端更新，但增加了网络开销。
     * </p>
     */
    private Integer chunkSize;

    /**
     * 默认构造函数
     */
    public ChatRequest() {
    }

    /**
     * 便捷构造函数：创建简单的文本对话请求
     *
     * @param message 用户消息
     */
    public ChatRequest(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getSchema() {
        return schema;
    }

    public void setSchema(Map<String, Object> schema) {
        this.schema = schema;
    }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    /**
     * 判断是否请求结构化输出
     *
     * <p>
     * 当 schema 字段非空时，认为请求结构化输出模式。
     * </p>
     *
     * @return true 表示需要结构化输出
     */
    public boolean isStructuredOutput() {
        return schema != null && !schema.isEmpty();
    }
}
