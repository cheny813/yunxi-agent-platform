package io.yunxi.platform.framework.spi;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用服务 SPI
 *
 * <p>
 * 抽象 LLM 调用接口，支持切换不同 LLM 提供商（千问/OpenAI/Ollama 等）。
 * 框架层通过此接口调用 LLM，不绑定特定供应商。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface LlmInvocationService {

    /**
     * 调用 LLM 生成文本
     *
     * @param prompt 用户提示词
     * @return LLM 生成的文本，失败返回 null
     */
    String invoke(String prompt);

    /**
     * 调用 LLM 生成文本（带系统提示词）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 生成的文本，失败返回 null
     */
    String invoke(String systemPrompt, String userPrompt);

    /**
     * 调用 LLM 生成文本（带消息列表）
     *
     * @param messages 消息列表，每条消息包含 role 和 content
     * @return LLM 生成的文本，失败返回 null
     */
    String invoke(List<Map<String, String>> messages);

    /**
     * 检查服务是否可用
     */
    boolean isAvailable();
}
