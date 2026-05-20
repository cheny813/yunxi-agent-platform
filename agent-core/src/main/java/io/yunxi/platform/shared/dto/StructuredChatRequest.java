package io.yunxi.platform.shared.dto;

/**
 * 结构化聊天请求 DTO
 *
 * <p>
 * 扩展了 ChatRequest，添加结构化输出选项。
 * </p>
 *
 * @author yunxi-agent-platform
 */
public class StructuredChatRequest extends ChatRequest {

    /**
     * 结构化输出配置（可选）
     */
    private StructuredOutputConfigDto structuredOutput;

    /**
     * 默认构造函数
     */
    public StructuredChatRequest() {
    }

    /**
     * 带参构造函数
     *
     * @param message 用户消息
     */
    public StructuredChatRequest(String message) {
        super(message);
    }

    public StructuredOutputConfigDto getStructuredOutput() {
        return structuredOutput;
    }

    public void setStructuredOutput(StructuredOutputConfigDto structuredOutput) {
        this.structuredOutput = structuredOutput;
    }
}
