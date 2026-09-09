package io.yunxi.platform.conversation;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.execution.AgentExecutionEngine;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.ExecutionResult;
import io.yunxi.platform.execution.strategy.StructuredBlockingStrategy;
import io.yunxi.platform.security.auth.SecurityContext;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.ConversationChatRequest;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import io.yunxi.platform.agent.AgentConfigurer;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.structured.SchemaClassRegistry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话服务门面：4 个 public 入口 + 2 个结构化输出入口统一委托 {@link AgentExecutionEngine} 执行。
 *
 * <p>本类只保留：入口签名、请求归一化、会话持久化与错误兜底。
 * 意图/记忆/RAG/事件编排等逻辑全部下沉到执行引擎的拦截器、策略、算子与适配器。</p>
 *
 * <p>入口行为约定：</p>
 * <ul>
 *   <li>阻塞入口：结果消息由调用方收口持久化（用户消息 + 助手回复 + saveConversation）；</li>
 *   <li>流式入口：用户消息在引擎拦截器组装完成后由门面写入会话，
 *       SseAgentEventAdapter 在 AGENT_RESULT 时补存助手回复并 saveConversation；</li>
 *   <li>异常兜底：阻塞抛 {@code RuntimeException("对话执行失败")}，流式输出错误 SSE 事件；</li>
 *   <li>结构化输出（阻塞）：经引擎拦截器链 + StructuredBlockingStrategy 执行；
 *       结构化输出（流式）：双模式（流/表单）+ 取消令牌 + requestId 的 SSE 专属编排放门面，
 *       暂不经执行引擎（流式结构化编排为门面专属逻辑）。</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
@Service
public class ChatAppService {

    private static final Logger log = LoggerFactory.getLogger(ChatAppService.class);

    private final AgentExecutionEngine executionEngine;
    private final ConversationDomainService conversationDomainService;
    private final SecurityContext securityContext;
    private final SseMessageBuilder sseMessageBuilder;
    private final io.yunxi.platform.agent.service.AgentService agentDomainService;
    private final SchemaClassRegistry schemaClassRegistry;
    private final DistributedRequestManager requestManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 对话接口等待超时时间（秒） */
    @Value("${conversation.timeout-seconds:300}")
    private int conversationTimeoutSeconds;

    public ChatAppService(AgentExecutionEngine executionEngine,
                          ConversationDomainService conversationDomainService,
                          SecurityContext securityContext,
                          SseMessageBuilder sseMessageBuilder,
                          io.yunxi.platform.agent.service.AgentService agentDomainService,
                          SchemaClassRegistry schemaClassRegistry,
                          DistributedRequestManager requestManager) {
        this.executionEngine = executionEngine;
        this.conversationDomainService = conversationDomainService;
        this.securityContext = securityContext;
        this.sseMessageBuilder = sseMessageBuilder;
        this.agentDomainService = agentDomainService;
        this.schemaClassRegistry = schemaClassRegistry;
        this.requestManager = requestManager;
    }

    /**
     * 阻塞对话（无会话）。
     */
    public ChatResponse chat(String name, ChatRequest request) {
        String message = request == null ? null : request.getMessage();
        if (message == null || message.isBlank()) {
            throw new BadRequestException("message 不能为空");
        }
        String userId = securityContext.getCurrentUserId();
        log.info("开始对话: Agent={}, Message={}", name, message);

        try {
            ExecutionRequest execRequest = ExecutionRequest.builder()
                    .agentName(name)
                    .conversationId(null)
                    .userId(userId)
                    .message(message)
                    .streaming(false)
                    .build();
            ExecutionResult result = executionEngine.execute(execRequest, null);
            if (result.isError()) {
                throw new RuntimeException(result.getErrorMessage());
            }
            Msg responseMsg = result.getResult();
            if (responseMsg == null) {
                throw new RuntimeException("Agent 响应为空");
            }
            return new ChatResponse(responseMsg.getTextContent());
        } catch (Exception e) {
            log.error("对话执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话执行失败", e);
        }
    }

    /**
     * 阻塞对话（基于会话）。
     */
    public ChatResponse chatWithConversation(String name, ConversationChatRequest request) {
        log.info("开始基于会话的对话: Agent={}, ConversationId={}", name, request.getConversationId());

        ConversationEntity conversation = conversationDomainService.getConversation(request.getConversationId());
        String userId = conversation.getUserId() != null
                ? conversation.getUserId()
                : securityContext.getCurrentUserId();

        try {
            ExecutionRequest execRequest = ExecutionRequest.builder()
                    .agentName(conversation.getAgentName())
                    .conversationId(request.getConversationId())
                    .userId(userId)
                    .message(request.getMessage())
                    .streaming(false)
                    .memoryConfig(request.getMemoryConfig())
                    .includeHistory(request.isIncludeHistory())
                    .historyMessages(conversation.getMessages())
                    .build();
            ExecutionResult result = executionEngine.execute(execRequest, conversation);
            if (result.isError()) {
                throw new RuntimeException(result.getErrorMessage());
            }
            Msg responseMsg = result.getResult();
            if (responseMsg == null) {
                throw new RuntimeException("Agent 响应为空");
            }

            // 会话持久化：用户消息（拦截器组装后的最终形态，含实体注入）+ 助手回复
            Msg userMsg = result.getContext().getInputMessage();
            if (userMsg != null) {
                conversation.addMessage(userMsg);
            }
            conversation.addMessage(responseMsg);
            conversationDomainService.saveConversation(conversation);

            ChatResponse response = new ChatResponse(responseMsg.getTextContent());
            response.setConversationId(request.getConversationId());
            return response;
        } catch (Exception e) {
            log.error("对话执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话执行失败", e);
        }
    }

    /**
     * 流式对话（无会话）。
     */
    public Flux<String> chatStream(String name, StreamChatRequest request) {
        log.info("开始流式对话: Agent={}, Message={}, responseMode={}",
                name, request.getMessage(), request.getResponseMode());
        String userId = securityContext.getCurrentUserId();

        return Flux.defer(() -> {
            try {
                ExecutionRequest execRequest = ExecutionRequest.builder()
                        .agentName(name)
                        .conversationId(null)
                        .userId(userId)
                        .message(request.getMessage())
                        .streaming(true)
                        .profile(request.getProfile())
                        .contextData(request.getContextData())
                        .useA2A(request.isUseA2A())
                        .deepMode(request.isDeepMode())
                        .enableThinking(request.isEnableThinking())
                        .confirmResults(request.getConfirmResults())
                        .build();
                ExecutionResult result = executionEngine.execute(execRequest, null);
                if (result.isError()) {
                    return Flux.just(sseMessageBuilder.buildErrorMessage(result.getErrorMessage()));
                }
                return result.getStream()
                        .doOnNext(chunk -> log.trace("流式输出chunk: {}", chunk));
            } catch (Exception e) {
                log.error("流式对话失败: Agent={}", name, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(AgentExecutionEngine.formatAgentError(e)));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 流式对话（基于会话）。
     */
    public Flux<String> chatStreamWithConversation(String conversationId, StreamChatRequest request) {
        log.info("开始基于会话的流式对话: ConversationId={}, Message={}, responseMode={}",
                conversationId, request.getMessage(), request.getResponseMode());

        return Flux.defer(() -> {
            try {
                ConversationEntity conversation = conversationDomainService.getConversation(conversationId);
                String userId = conversation.getUserId() != null
                        ? conversation.getUserId()
                        : securityContext.getCurrentUserId();

                ExecutionRequest execRequest = ExecutionRequest.builder()
                        .agentName(conversation.getAgentName())
                        .conversationId(conversationId)
                        .userId(userId)
                        .message(request.getMessage())
                        .streaming(true)
                        .profile(request.getProfile())
                        .contextData(request.getContextData())
                        .memoryConfig(request.getMemoryConfig())
                        .quickMode(request.isQuickMode())
                        .useA2A(request.isUseA2A())
                        .deepMode(request.isDeepMode())
                        .enableThinking(request.isEnableThinking())
                        .historyMessages(conversation.getMessages())
                        .confirmResults(request.getConfirmResults())
                        .build();
                ExecutionResult result = executionEngine.execute(execRequest, conversation);
                if (result.isError()) {
                    return Flux.just(sseMessageBuilder.buildErrorMessage(result.getErrorMessage()));
                }

                // 会话持久化：用户消息在拦截器组装完成后写入会话（SseAgentEventAdapter
                // 在 AGENT_RESULT 时补存助手回复并 saveConversation，刷新 UI 后恢复完整对话）
                Msg userMsg = result.getContext().getInputMessage();
                if (conversation != null && userMsg != null) {
                    conversation.addMessage(userMsg);
                }

                return result.getStream()
                        .doOnComplete(() -> log.info("基于会话的流式对话完成: ConversationId={}", conversationId));
            } catch (Exception e) {
                log.error("基于会话的流式对话失败: ConversationId={}", conversationId, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(AgentExecutionEngine.formatAgentError(e)));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 阻塞结构化输出（经执行引擎的 StructuredBlockingStrategy 执行）。
     *
     * <p>与普通阻塞通道一样经过拦截器链（AuthResolve/Memory/IntentPipeline/RagRetrieval/Audit），
     * 由 {@code StructuredBlockingStrategy} 完成 schema 绑定调用；结构化数据由本方法
     * 从返回 Msg 中提取（Class 走 getStructuredData(schemaClass)，
     * 内联 JsonNode 走 getStructuredData(false)）。</p>
     *
     * @param name    Agent 名称
     * @param request 统一请求（含 message/schema/schemaName）
     * @return 结构化数据对象
     */
    public Object chatStructured(String name, UnifiedChatRequest request) {
        log.info("开始结构化对话: Agent={}", name);
        String userId = securityContext.getCurrentUserId();

        ExecutionRequest execRequest = ExecutionRequest.builder()
                .agentName(name)
                .conversationId(null)
                .userId(userId)
                .message(request.getMessage())
                .contextData(request.getContextData())
                .profile(request.getProfile())
                .streaming(false)
                .structuredOutput(true)
                .structuredSchema(request.getSchema())
                .structuredSchemaName(request.getSchemaName())
                .build();
        ExecutionResult result = executionEngine.execute(execRequest, null);
        if (result.isError()) {
            // 未配置 Schema 时保持 400 HTTP 语义
            if (result.getErrorMessage() != null && result.getErrorMessage().contains("未配置 Schema")) {
                throw new BadRequestException(result.getErrorMessage());
            }
            throw new RuntimeException(result.getErrorMessage());
        }
        Msg responseMsg = result.getResult();
        if (responseMsg == null) {
            throw new RuntimeException("Agent 响应为空");
        }
        StructuredBlockingStrategy.SchemaTarget target = (StructuredBlockingStrategy.SchemaTarget)
                result.getContext().getAttribute(StructuredBlockingStrategy.ATTR_SCHEMA_TARGET);
        try {
            if (target != null && target.schemaClass() != null) {
                return responseMsg.getStructuredData(target.schemaClass());
            }
            return responseMsg.getStructuredData(false);
        } catch (Exception e) {
            throw new RuntimeException("结构化数据提取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式结构化输出（门面专属的 SSE 编排）。
     *
     * <p>双模式（流/表单）+ 取消令牌 + requestId 的 SSE 专属编排，暂不经执行引擎。</p>
     *
     * @param agentName Agent 名称
     * @param request   统一请求（含 message/schema/schemaName/structuredStream/cancelToken）
     * @return SSE 消息流
     */
    public Flux<String> chatStreamStructured(String agentName, UnifiedChatRequest request) {
        return Flux.defer(() -> {
            try {
                // 获取 Agent 实例
                Agent agent = agentDomainService.getAgentInstance(agentName);
                // 解析 Structured Output 目标（类 or 内联 JsonNode）
                StructuredBlockingStrategy.SchemaTarget target = resolveSchemaTarget(agentName, request);
                if (target == null) {
                    return Flux.just(sseMessageBuilder.buildErrorMessage(
                            "未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数"));
                }
                // 注册请求（根据令牌）
                DistributedRequestManager.RequestInfo requestInfo = requestManager.registerRequestWithToken(
                        agentName,
                        request.getCancelToken() != null ? request.getCancelToken()
                                : requestManager.generateCancelToken());
                String requestId = requestInfo.getRequestId();
                // 构造用户消息
                Msg userMsg = Msg.builder()
                        .textContent(request.getMessage())
                        .role(MsgRole.USER)
                        .build();
                // 会话级工具组激活：覆盖持久化/遗留空激活组，确保 MCP 工具在每次会话可用
                String structUserId = request.getUserId();
                String structSessionId = request.getConversationId() != null ? request.getConversationId()
                        : (structUserId != null ? structUserId : "structured-" + agentName);
                RuntimeContext rc = RuntimeContext.builder().userId(structUserId).sessionId(structSessionId).build();
                AgentConfigurer.activateSessionToolGroups((HarnessAgent) agent, structUserId, structSessionId);
                // 开始事件（包含 requestId）
                Flux<String> startFlux = Flux.just(sseMessageBuilder.buildMessageWithRequestId("start", null, requestId));
                // 完成事件
                Flux<String> doneFlux = Flux.just(sseMessageBuilder.buildDoneMessage());

                // 流模式：streamEvents 实时吐字 + 末尾自解析结构化数据
                if (request.isStructuredStream()) {
                    log.info("流式结构化输出(流模式, 逐字流): Agent={}, requestId={}", agentName, requestId);
                    Flux<String> streamFlux = ((HarnessAgent) agent).streamEvents(List.of(userMsg), rc)
                            .takeWhile(event -> !requestManager.isRequestCancelled(requestId))
                            .flatMap(event -> processStructuredAgentEvent(event, target))
                            .doOnComplete(() -> {
                                log.info("流式差异化输出完成: Agent={}, requestId={}", agentName, requestId);
                                requestManager.unregisterRequest(requestId);
                            })
                            .doOnCancel(() -> {
                                log.info("流式差异化输出取消: Agent={}, requestId={}", agentName, requestId);
                                requestManager.unregisterRequest(requestId);
                            })
                            .onErrorResume(e -> {
                                log.error("流式差异化输出异常: Agent={}, requestId={}", agentName, requestId, e);
                                requestManager.unregisterRequest(requestId);
                                return Flux.just(sseMessageBuilder.buildErrorMessage("结构化数据解析失败: " + e.getMessage()));
                            });
                    return Flux.concat(startFlux, streamFlux, doneFlux);
                }

                // 表单模式（默认）：使用 AgentScope 提供的结构化重载 call(List, Class/JsonNode, RuntimeContext)。
                // HarnessAgent.streamEvents 不接受 schema 参数、无法绑定 structured_output 元数据，故不可用。
                // 该重载内部走 native(json_schema) 或 fallback(generate_response 合成工具) 并自动降级，与官方文档一致；
                // 代价是结构化模式下不提供逐字 token 流（框架未公开 stream+structured 组合 API）。
                log.info("流式结构化输出(表单模式, 阻塞校验): Agent={}, requestId={}", agentName, requestId);
                Duration timeout = Duration.ofSeconds(conversationTimeoutSeconds);
                Mono<String> structuredFlux = Mono.fromCallable(() -> {
                    Msg result;
                    Object data;
                    if (target.schemaClass() != null) {
                        result = ((HarnessAgent) agent).call(List.of(userMsg), target.schemaClass(), rc).block(timeout);
                        data = result.getStructuredData(target.schemaClass());
                    } else {
                        result = ((HarnessAgent) agent).call(List.of(userMsg), target.schemaNode(), rc).block(timeout);
                        data = result.getStructuredData(false);
                    }
                    return sseMessageBuilder.buildMessage("structured", sseMessageBuilder.toJsonString(data));
                }).doFinally(signal -> requestManager.unregisterRequest(requestId))
                        .onErrorResume(e -> {
                            log.error("流式差异化输出异常: Agent={}, requestId={}", agentName, requestId, e);
                            return Mono.just(sseMessageBuilder.buildErrorMessage("结构化数据解析失败: " + e.getMessage()));
                        });
                return Flux.concat(startFlux, structuredFlux, doneFlux);
            } catch (Exception e) {
                log.error("流式差异化输出初始化异常: Agent={}", agentName, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 处理流模式下的 AgentEvent：转发 thinking/content 逐字事件，并在 AGENT_RESULT 处自解析结构化数据。
     */
    private Flux<String> processStructuredAgentEvent(io.agentscope.core.event.AgentEvent event,
                                                     StructuredBlockingStrategy.SchemaTarget target) {
        var type = event.getType();
        if (type == AgentEventType.THINKING_BLOCK_DELTA && event instanceof ThinkingBlockDeltaEvent tde) {
            String text = tde.getDelta();
            if (text != null && !text.isEmpty())
                return Flux.just(sseMessageBuilder.buildThinkingMessage(text));
            return Flux.empty();
        }
        if (type == AgentEventType.AGENT_RESULT && event instanceof AgentResultEvent are) {
            try {
                Object data = parseStructuredFromMessage(are.getResult(), target);
                return Flux.just(sseMessageBuilder.buildMessage("structured", sseMessageBuilder.toJsonString(data)));
            } catch (Exception e) {
                log.error("结构化数据解析失败", e);
                return Flux.just(sseMessageBuilder.buildErrorMessage("结构化数据解析失败: " + e.getMessage()));
            }
        }
        if (type == AgentEventType.TEXT_BLOCK_DELTA && event instanceof TextBlockDeltaEvent tde) {
            String text = tde.getDelta();
            if (text != null && !text.isEmpty())
                return Flux.just(sseMessageBuilder.buildContentMessage(text));
            return Flux.empty();
        }
        return Flux.empty();
    }

    /**
     * 从模型结果中提取结构化数据：优先用框架绑定的 structured_output 元数据，否则把文本当 JSON 自解析。
     */
    private Object parseStructuredFromMessage(Msg message, StructuredBlockingStrategy.SchemaTarget target) throws Exception {
        if (message.hasStructuredData() && target.schemaClass() != null) {
            return message.getStructuredData(target.schemaClass());
        }
        String text = message.getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("模型未返回可解析的结构化文本");
        }
        String json = stripCodeFence(text);
        if (target.schemaClass() != null) {
            return objectMapper.readValue(json, target.schemaClass());
        }
        return objectMapper.convertValue(objectMapper.readTree(json), Map.class);
    }

    /** 去除模型可能包裹的 markdown 代码围栏（```json ... ```），便于直接反序列化 */
    private static String stripCodeFence(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline >= 0) {
                t = t.substring(firstNewline + 1);
            }
            if (t.endsWith("```")) {
                t = t.substring(0, t.length() - 3);
            }
            t = t.trim();
        }
        return t;
    }

    /** 解析 Structured Output 目标（按优先级：内联 JSON Schema > 命名 Schema > 默认 Schema 表） */
    private StructuredBlockingStrategy.SchemaTarget resolveSchemaTarget(String agentName, UnifiedChatRequest request) {
        // 优先检查：内联 JSON Schema
        Map<String, Object> schema = request.getSchema();
        if (schema != null && !schema.isEmpty()) {
            log.info("流式差异化输出使用内联 JSON Schema");
            return new StructuredBlockingStrategy.SchemaTarget(null, objectMapper.valueToTree(schema));
        }
        // 其次检查：命名 Schema（通过 schemaName 参数）
        String schemaName = request.getSchemaName();
        if (schemaName != null && !schemaName.isBlank()) {
            Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
            if (schemaClass != null) {
                log.info("流式差异化输出使用命名 Schema [{}]: {}", schemaName, schemaClass.getName());
                return new StructuredBlockingStrategy.SchemaTarget(schemaClass, null);
            }
            log.warn("未找到命名 Schema [{}]，尝试使用默认 Schema", schemaName);
        }
        // 最后检查：默认 Schema 表
        Class<?> schemaClass = schemaClassRegistry.get(agentName);
        if (schemaClass != null) {
            log.info("流式差异化输出使用默认 Schema 表: {}", schemaClass.getName());
            return new StructuredBlockingStrategy.SchemaTarget(schemaClass, null);
        }
        return null;
    }
}
