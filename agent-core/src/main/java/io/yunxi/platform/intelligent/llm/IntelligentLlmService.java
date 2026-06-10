package io.yunxi.platform.intelligent.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import reactor.core.publisher.Flux;

/**
 * 智能 LLM 服务
 *
 * <p>
 * 基于 AgentScope 框架封装的智能 LLM 调用服务。
 * 通过 {@link Model} 接口和 {@link ModelFactory} 创建模型实例，
 * 提供简化的 LLM 调用接口。
 * </p>
 *
 * <h3>调用流程</h3>
 *
 * <pre>
 *   IntelligentLlmService.generate()
 *     -> ModelFactory.create()             // 创建 Model
 *     -> Model.stream(messages)            // 流式调用
 *     -> Flux&lt;ChatResponse&gt; -> blockFirst() // 阻塞获取
 *     -> 解析 TextBlock 返回结果
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Service
public class IntelligentLlmService {

    private static final Logger log = LoggerFactory.getLogger(IntelligentLlmService.class);

    /** AgentScope 核心配置 */
    @Autowired
    private AgentscopeCoreProperties agentscopeProperties;

    /** 模型工厂，用于创建和配置 Model 实例 */
    @Autowired
    private ModelFactory modelFactory;

    /**
     * 简化调用：仅传入用户提示词
     *
     * @param userPrompt 用户提示词
     * @return 模型生成的文本内容，失败返回 null
     */
    @Nullable
    public String generate(String userPrompt) {
        return generate(null, userPrompt);
    }

    /**
     * 完整调用：传入系统提示词和用户提示词
     *
     * <p>
     * 使用 {@link Model#stream} 进行流式调用，
     * 等待流式响应完成或超时后返回结果。
     * 适用于需要系统提示词的场景。
     * </p>
     *
     * @param systemPrompt 系统提示词，可为 null
     * @param userPrompt   用户提示词
     * @return 模型生成的文本内容，失败返回 null
     */
    @Nullable
    public String generate(@Nullable String systemPrompt, String userPrompt) {
        try {
            // 通过 ModelFactory 创建 Model（使用默认配置）
            Model model = modelFactory.create(null);

            // 构建消息列表：system 消息（可选）+ user 消息
            List<Msg> messages = new ArrayList<>();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                messages.add(Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .textContent(systemPrompt)
                        .build());
            }
            messages.add(Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(userPrompt)
                    .build());

            // 执行流式调用，阻塞获取首个响应
            // 支持配置 role 参数和 cache_control 等高级选项
            Duration timeout = Duration.ofSeconds(agentscopeProperties.getChatTimeoutSeconds());
            Flux<ChatResponse> responseFlux = model.stream(messages, null, null);
            ChatResponse response = responseFlux.blockFirst(timeout);

            if (response == null || response.getContent() == null) {
                log.warn("LLM 返回空响应");
                return null;
            }

            // 从 ChatResponse 的 ContentBlock 中提取文本内容
            String result = response.getContent().stream()
                    .filter(block -> block instanceof TextBlock)
                    .map(block -> ((TextBlock) block).getText())
                    .collect(Collectors.joining());

            if (result.isBlank()) {
                log.warn("LLM 返回空白内容");
                return null;
            }

            return result;

        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 带默认值的调用：失败时返回指定的默认值
     *
     * @param systemPrompt  系统提示词，可为 null
     * @param userPrompt    用户提示词
     * @param defaultResult 默认值
     * @return 模型生成的文本内容，失败时返回 defaultResult
     */
    public String generateOrDefault(@Nullable String systemPrompt, String userPrompt, String defaultResult) {
        String result = generate(systemPrompt, userPrompt);
        return result != null ? result : defaultResult;
    }
}
