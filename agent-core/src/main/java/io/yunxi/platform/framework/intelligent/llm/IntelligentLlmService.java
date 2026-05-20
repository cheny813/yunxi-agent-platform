package io.yunxi.platform.framework.intelligent.llm;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.framework.intelligent.config.IntelligentProperties;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能模块 LLM 调用服务
 *
 * <p>
 * 封装 AgentScope 模型调用，为智能化模块提供统一的 LLM 访问接口。
 * </p>
 *
 * <h3>设计说明</h3>
 * <p>
 * 本服务直接使用 {@link ChatModelProvider#stream} 进行模型调用，
 * 而非通过 ReActAgent 包装。原因：
 * </p>
 * <ul>
 * <li>ReActAgent 适用于「推理 + 工具调用」的 ReAct 循环场景，
 * 其内部会执行思考→选工具→执行→观察的迭代流程</li>
 * <li>本服务的调用场景（背景审查、技能生成、会话摘要）均为简单的
 * prompt → response 纯文本生成，不涉及工具调用和推理循环</li>
 * <li>使用 ReActAgent 做简单文本生成属于语义错配，会引入不必要的开销
 * （Agent 对象创建、迭代循环检查、额外 prompt 格式化等）</li>
 * </ul>
 *
 * <h3>调用链路</h3>
 * 
 * <pre>
 *   IntelligentLlmService.generate()
 *     → ModelProviderFactory.createProvider()  // 创建 ChatModelProvider
 *     → ChatModelProvider.stream(messages)     // 直接调模型，不经过 Agent
 *     → Flux&lt;ChatResponse&gt; → blockFirst()     // 同步阻塞获取结果
 *     → 提取 TextBlock 文本
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Service
public class IntelligentLlmService {

    private static final Logger log = LoggerFactory.getLogger(IntelligentLlmService.class);

    /** 模型提供商工厂 */
    @Autowired
    private ModelProviderFactory modelFactory;

    /** AgentScope 配置属性 */
    @Autowired
    private AgentscopeCoreProperties agentscopeProperties;

    /** 智能化配置属性 */
    @Autowired
    private IntelligentProperties intelligentProperties;

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
     * 直接调用 {@link ChatModelProvider#stream} 获取模型响应，
     * 无需经过 ReActAgent 的推理循环，适用于纯文本生成场景。
     * </p>
     *
     * @param systemPrompt 系统提示词（可为 null）
     * @param userPrompt   用户提示词
     * @return 生成的文本，失败时返回 null
     */
    @Nullable
    public String generate(@Nullable String systemPrompt, String userPrompt) {
        try {
            ChatModelProvider model = createModel();

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

    /**
     * 创建模型提供者
     *
     * <p>
     * 优先使用 IntelligentProperties.LearningLoopConfig 中配置的 reviewModel，
     * 如果未配置或为 "default"，则使用 AgentscopeCoreProperties 的默认配置。
     * </p>
     */
    private ChatModelProvider createModel() {
        String reviewModel = intelligentProperties.getLearningLoop().getReviewModel();

        String provider;
        String apiKey;
        String modelName;

        if (reviewModel != null && !"default".equals(reviewModel) && !reviewModel.isBlank()) {
            // 使用 IntelligentProperties 中配置的专用审查模型
            provider = agentscopeProperties.getProvider();
            apiKey = agentscopeProperties.getApiKey();
            modelName = reviewModel;
        } else {
            // 使用 AgentscopeProperties 默认配置
            provider = agentscopeProperties.getProvider();
            apiKey = agentscopeProperties.getApiKey();
            modelName = agentscopeProperties.getModelName();
        }

        ModelConfig config = new ModelConfig(provider, apiKey, modelName);
        return modelFactory.createProvider(config);
    }
}
