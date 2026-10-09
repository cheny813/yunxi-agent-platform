package io.yunxi.platform.ingress.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.yunxi.platform.agent.runtime.YunxiRuntimeContextBuilder;
import io.yunxi.platform.agent.runtime.AgentInvoker;
import io.agentscope.core.chat.completions.builder.ChatCompletionsResponseBuilder;
import io.agentscope.core.chat.completions.converter.ChatMessageConverter;
import io.agentscope.core.chat.completions.converter.OpenAIToolConverter;
import io.agentscope.core.chat.completions.model.ChatCompletionsRequest;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.spring.boot.chat.service.ChatCompletionsStreamingService;
import io.agentscope.spring.boot.chat.web.ChatCompletionsController;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.security.auth.SecurityContext;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Chat Completions（OpenAI 兼容）协议的产品平面适配层：覆盖框架 starter 默认创建的
 * {@link ChatCompletionsController}（后者每次新建一个裸 {@link ReActAgent}，既不经 yunxi 统一装配链、
 * 也不携带调用方身份，是「全协议统一生效」唯一例外）。
 *
 * <p>本控制器以 {@code @ConditionalOnMissingBean(ChatCompletionsController.class)} 类型判定覆盖框架默认实例，
 * 复用框架 starter 提供的转换 / 构建 / 流式 Bean（{@code ChatMessageConverter} / {@code
 * ChatCompletionsResponseBuilder} / {@code ChatCompletionsStreamingService} / {@code
 * ChatCompletionsStreamingAdapter} / {@code OpenAIToolConverter}），仅替换「Agent 来源」一处：
 * 经 {@link AgentService} 取得 yunxi 已装配的 Agent（含全部 AgentScope-Java 原生 Middleware），不再新建裸 agent。
 *
 * <p>Chat Completions 本身保持无状态（每次请求独立 RuntimeContext，sessionId 用 requestId），与 OpenAI
 * 语义一致；yunxi 装配的 Agent 为共享单例，故不在此注册调用方声明的 schema-only 工具（以 Agent 自身已装配的工具为准）。
 *
 * <p>身份注入（userId）说明：AgentScope-Java 2.0.4 的 Chat Completions 入口
 * {@code Agent.call} 与 {@code ChatCompletionsStreamingService.streamAsSse} 均不接受 {@link RuntimeContext}
 * 参数，因此此处暂沿用框架无状态调用；yunxi 已装配 Agent 仍经统一装配链（内容安全 / 审计 / 画像 / 历史等
 * Middleware）生效。待框架在 Chat Completions 入口透传 RuntimeContext 后，可在此构造带 userId 的
 * RuntimeContext 注入调用（逻辑锚点见 {@link #buildRuntimeContext}）。
 */
@RestController
public class YunxiChatCompletionsController extends ChatCompletionsController {

    private static final Logger log = LoggerFactory.getLogger(YunxiChatCompletionsController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentService agentService;
    private final SecurityContext securityContext;
    private final ChatMessageConverter messageConverter;
    private final ChatCompletionsResponseBuilder responseBuilder;
    private final ChatCompletionsStreamingService streamingService;
    private final String defaultAgentName;

    public YunxiChatCompletionsController(
            ObjectProvider<ReActAgent> agentProvider,
            ChatMessageConverter messageConverter,
            ChatCompletionsResponseBuilder responseBuilder,
            ChatCompletionsStreamingService streamingService,
            OpenAIToolConverter toolConverter,
            AgentService agentService,
            SecurityContext securityContext,
            @Value("${yunxi.chat-completions.default-agent:}") String defaultAgentName) {
        super(agentProvider, messageConverter, responseBuilder, streamingService, toolConverter);
        this.agentService = agentService;
        this.securityContext = securityContext;
        this.messageConverter = messageConverter;
        this.responseBuilder = responseBuilder;
        this.streamingService = streamingService;
        this.defaultAgentName = defaultAgentName;
    }

    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object createCompletion(@Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();
        if (Boolean.TRUE.equals(request.getStream())) {
            Flux<ServerSentEvent<String>> stream = createCompletionStream(request);
            return ResponseEntity.ok()
                    .header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
                    .body(stream);
        }
        try {
            ReActAgent agent = resolveAgent(request.getModel());
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                return Mono.error(new IllegalArgumentException("At least one message is required"));
            }
            return AgentInvoker.call(agent, messages)
                    .map(reply -> responseBuilder.buildResponse(request, reply, requestId))
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error processing chat completion request: requestId={}",
                                        requestId,
                                        error);
                                return Mono.just(
                                        responseBuilder.buildErrorResponse(request, error, requestId));
                            });
        } catch (Exception e) {
            log.error("Error creating agent or processing request: requestId={}", requestId, e);
            return Mono.error(e);
        }
    }

    @PostMapping(
            value = "${agentscope.chat-completions.base-path:/v1/chat/completions}",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> createCompletionStream(
            @Valid @RequestBody ChatCompletionsRequest request) {
        String requestId = UUID.randomUUID().toString();
        if (Boolean.FALSE.equals(request.getStream())) {
            return Flux.error(
                    new IllegalArgumentException(
                            "Non-streaming requests (stream=false) should use the non-streaming"
                                    + " endpoint without Accept: text/event-stream header"));
        }
        try {
            ReActAgent agent = resolveAgent(request.getModel());
            List<Msg> messages = messageConverter.convertMessages(request.getMessages());
            if (messages.isEmpty()) {
                return Flux.error(new IllegalArgumentException("At least one message is required"));
            }
            String model = request.getModel();
            return streamingService.streamAsSse(agent, messages, requestId, model)
                    .onErrorResume(
                            error -> {
                                log.error(
                                        "Error in streaming response: requestId={}", requestId, error);
                                return Flux.just(
                                        streamingService.createErrorSseEvent(error, requestId, model));
                            });
        } catch (Exception e) {
            log.error("Error processing streaming request: requestId={}", requestId, e);
            return Flux.error(e);
        }
    }

    private ReActAgent resolveAgent(String model) {
        String name = model;
        if (name == null || name.isBlank() || agentService.findAgent(name) == null) {
            if (defaultAgentName != null
                    && !defaultAgentName.isBlank()
                    && agentService.findAgent(defaultAgentName) != null) {
                name = defaultAgentName;
            } else {
                var all = agentService.listAgents();
                if (!all.isEmpty()) {
                    name = all.get(0).getName();
                }
            }
        }
        if (agentService.findAgent(name) == null) {
            throw new IllegalArgumentException(
                    "No agent available for model: " + (model == null ? "<null>" : model));
        }
        Agent agent = agentService.getAgentInstance(name);
        if (agent instanceof HarnessAgent harnessAgent) {
            return harnessAgent.getDelegate();
        }
        throw new IllegalStateException(
                "Agent " + name + " is not a HarnessAgent and cannot be used for Chat Completions");
    }

    private RuntimeContext buildRuntimeContext(String sessionId) {
        // AgentScope-Java 2.0.4 的 Chat Completions 入口不接受 RuntimeContext，故本方法当前未被调用；
        // 统一经 YunxiRuntimeContextBuilder 构造，待框架入口透传 RuntimeContext 后直接接线即可。
        return YunxiRuntimeContextBuilder.build(securityContext.getCurrentUserId(), sessionId, false, null, null, null);
    }
}
