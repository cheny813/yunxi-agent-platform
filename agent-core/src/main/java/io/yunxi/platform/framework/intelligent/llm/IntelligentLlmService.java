package io.yunxi.platform.framework.intelligent.llm;

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
import io.yunxi.platform.framework.model.ModelFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import reactor.core.publisher.Flux;

/**
 * 智能模块 LLM 调用服务
 *
 * <p>
 * 封装 AgentScope 模型调用，为智能化模块提供统一的 LLM 访问接口。
 * 直接使用框架 {@link Model} 接口（通过 {@link ModelFactory} 创建），
 * 利用框架内置的正确角色映射和缓存支持。
 * </p>
 *
 * <h3>调用链路</h3>
 *
 * <pre>
 *   IntelligentLlmService.generate()
 *     → ModelFactory.create()             // 创建框架 Model
 *     → Model.stream(messages)            // 直接调模型
 *     → Flux&lt;ChatResponse&gt; → blockFirst() // 同步阻塞获取结果
 *     → 提取 TextBlock 文本
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Service
public class IntelligentLlmService {

    private static final Logger log = LoggerFactory.getLogger(IntelligentLlmService.class);

    /** AgentScope 配置属性 */
    @Autowired
    private AgentscopeCoreProperties agentscopeProperties;

    /** 模型工厂 — 创建框架 Model 实例 */
    @Autowired
    private ModelFactory modelFactory;

    /**
     * 使用默认系统提示词生成文本
     *
     * @param userPrompt 用户提示词
     * @return 生成的文本，失败时返回 null
     */
    @Nullable
    public String generate(String userPrompt) {
        return generate(null, userPrompt);
    }

    /**
     * 使用指定系统提示词生成文本
     *
     * <p>
     * 直接调用 {@link Model#stream} 获取模型响应，
     * 无需经过 ReActAgent 的推理循环，适用于纯文本生成场景。
     * 消息角色映射和缓存由框架内部自动处理。
     * </p>
     *
     * @param systemPrompt 系统提示词（可为 null）
     * @param userPrompt   用户提示词
     * @return 生成的文本，失败时返回 null
     */
    @Nullable
    public String generate(@Nullable String systemPrompt, String userPrompt) {
        try {
            // 使用 ModelFactory 创建框架 Model（使用全局默认配置）
            Model model = modelFactory.create(null);

            // 构建消息列表：system 消息（如有）+ user 消息
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

            // 直接调用模型 stream 接口，同步阻塞获取首个响应
            // 框架自动处理 role 映射和 cache_control（如已配置）
            Duration timeout = Duration.ofSeconds(agentscopeProperties.getChatTimeoutSeconds());
            Flux<ChatResponse> responseFlux = model.stream(messages, null, null);
            ChatResponse response = responseFlux.blockFirst(timeout);

            if (response == null || response.getContent() == null) {
                log.warn("LLM 调用返回空响应");
                return null;
            }

            // 从 ChatResponse 的 ContentBlock 列表中提取文本内容
            String result = response.getContent().stream()
                    .filter(block -> block instanceof TextBlock)
                    .map(block -> ((TextBlock) block).getText())
                    .collect(Collectors.joining());

            if (result.isBlank()) {
                log.warn("LLM 响应中无有效文本内容");
                return null;
            }

            return result;

        } catch (Exception e) {
            log.error("LLM 调用失败: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 使用指定系统提示词生成文本，失败时返回默认值
     *
     * @param systemPrompt  系统提示词（可为 null）
     * @param userPrompt    用户提示词
     * @param defaultResult 默认返回值
     * @return 生成的文本，失败时返回 defaultResult
     */
    public String generateOrDefault(@Nullable String systemPrompt, String userPrompt, String defaultResult) {
        String result = generate(systemPrompt, userPrompt);
        return result != null ? result : defaultResult;
    }
}
