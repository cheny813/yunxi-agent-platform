package io.yunxi.platform.framework.agent;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.dto.StructuredChatRequest;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.agentscope.core.ReActAgent;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 结构化输出领域服务
 *
 * <p>
 * 【领域层】处理结构化输出请求（单一职责）
 * </p>
 * <p>
 * 支持两种配置方式：
 * <ul>
 * <li><b>预定义模式</b>：使用预定义的字符串模式（推荐）</li>
 * <li><b>自定义Schema</b>：使用JSON Schema定义输出格式</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class StructuredOutputDomainService {

    private final AgentDomainService agentDomainService;
    private final AgentscopeCoreProperties properties;

    public StructuredOutputDomainService(AgentDomainService agentDomainService,
            AgentscopeCoreProperties properties) {
        this.agentDomainService = agentDomainService;
        this.properties = properties;
    }

    /**
     * 发起结构化输出请求
     *
     * @param name    Agent名称
     * @param request 对话请求（包含结构化输出配置）
     * @return 结构化输出结果
     */
    public Object chatStructured(String name, StructuredChatRequest request) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Agent name不能为空");
        }

        if (request == null || request.getMessage() == null || request.getMessage().isBlank()) {
            throw new BadRequestException("message不能为空");
        }

        // 获取Agent实例
        ReActAgent agent = agentDomainService.getAgentInstance(name);

        // 构建消息
        Msg userMsg = Msg.builder()
                .textContent(request.getMessage())
                .build();

        // 调用Agent
        Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
        Msg responseMsg = agent.call(userMsg).block(timeout);

        if (responseMsg == null) {
            throw new RuntimeException("Agent响应为空");
        }

        // 返回结构化结果
        return parseStructuredResponse(responseMsg.getTextContent(), request);
    }

    /**
     * 解析结构化响应
     */
    private Object parseStructuredResponse(String content, StructuredChatRequest request) {
        // 简单实现：直接返回文本内容
        // 实际应用中可以根据schema进行解析
        return content;
    }
}
