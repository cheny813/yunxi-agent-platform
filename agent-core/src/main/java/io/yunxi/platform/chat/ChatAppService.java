package io.yunxi.platform.chat;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.yunxi.platform.execution.ExecutionRequest;
import io.yunxi.platform.execution.ExecutionResult;
import io.yunxi.platform.execution.context.SchemaTarget;
import io.yunxi.platform.execution.spi.ExecutionEngine;
import io.yunxi.platform.infrastructure.DistributedRequestManager;
import io.yunxi.platform.integration.structured.SchemaClassRegistry;
import io.yunxi.platform.security.auth.SecurityContext;
import io.yunxi.platform.shared.codec.CodecSpec;
import io.yunxi.platform.shared.dto.ChatConversationRequest;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对话服务门面：4 个 public 入口 + 2 个结构化输出入口统一委托 {@link ExecutionEngine} 执行。
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

    private final ExecutionEngine executionEngine;
    private final ChatDomainService chatDomainService;
    private final SecurityContext securityContext;
    private final SseMessageBuilder sseMessageBuilder;
    private final io.yunxi.platform.agent.service.AgentService agentDomainService;
    private final SchemaClassRegistry schemaClassRegistry;
    private final DistributedRequestManager requestManager;
    private final ObjectMapper objectMapper = CodecSpec.storage();

    /** 对话接口等待超时时间（秒） */
    @Value("${conversation.timeout-seconds:300}")
    private int conversationTimeoutSeconds;

    public ChatAppService(ExecutionEngine executionEngine,
                          ChatDomainService chatDomainService,
                          SecurityContext securityContext,
                          SseMessageBuilder sseMessageBuilder,
                          io.yunxi.platform.agent.service.AgentService agentDomainService,
                          SchemaClassRegistry schemaClassRegistry,
                          DistributedRequestManager requestManager) {
        this.executionEngine = executionEngine;
        this.chatDomainService = chatDomainService;
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
    public ChatResponse chatWithConversation(String name, ChatConversationRequest request) {
        log.info("开始基于会话的对话: Agent={}, ConversationId={}", name, request.getConversationId());

        ChatEntity conversation = chatDomainService.getConversation(request.getConversationId());
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
            chatDomainService.saveConversation(conversation);

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
                return Flux.just(sseMessageBuilder.buildErrorMessage(ExecutionEngine.formatAgentError(e)));
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
                ChatEntity conversation = chatDomainService.getConversation(conversationId);
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

        long clientStartNanos = System.nanoTime();
        return result.getStream()
                .doOnSubscribe(s -> log.info("[TRACE-CLIENT] 客户端流订阅(开始) conv={} thread={}",
                        conversationId, Thread.currentThread().getName()))
                .doOnComplete(() -> log.info("基于会话的流式对话完成: ConversationId={}", conversationId))
                .doFinally(sig -> {
                    long ms = (System.nanoTime() - clientStartNanos) / 1_000_000;
                    log.info("[TRACE-CLIENT] 客户端流终结 conv={} signal={} elapsedMs={}",
                            conversationId, sig, ms);
                });
            } catch (Exception e) {
                log.error("基于会话的流式对话失败: ConversationId={}", conversationId, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(ExecutionEngine.formatAgentError(e)));
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
        SchemaTarget target = (SchemaTarget)
                result.getContext().getAttribute(SchemaTarget.ATTR_SCHEMA_TARGET);
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
     * <p>执行语义统一交由 {@link ExecutionEngine} 兑现（Agent 解析、拦截器链、会话级工具组激活、
     * 统一 RuntimeContext）；本方法只负责边缘层 SSE 帧封装（start/requestId、逐字、structured、done）
     * 与取消令牌管理——符合"运行时内兑现执行语义、HTTP 边缘只做传输 framing"的边界。</p>
     *
     * <ul>
     *   <li>表单模式（structuredStream=false）：请求以 streaming=false 交引擎，由
     *       {@code StructuredBlockingStrategy} 执行并返回 {@code Msg}，本方法从中提取结构化数据帧封装；</li>
     *   <li>流模式（structuredStream=true）：请求以 streaming=true 交引擎，由 {@code StreamingStrategy}
     *       产出原始事件流，本方法在 AGENT_RESULT 处自解析结构化数据帧封装。</li>
     * </ul>
     *
     * @param agentName Agent 名称
     * @param request   统一请求（含 message/schema/schemaName/structuredStream/cancelToken）
     * @return SSE 消息流
     */
    public Flux<String> chatStreamStructured(String agentName, UnifiedChatRequest request) {
        return Flux.defer(() -> {
            try {
                // 解析结构化目标（仅用于前置校验与帧封装时的解析；执行由引擎兑现）
                SchemaTarget target = resolveSchemaTarget(agentName, request);
                if (target == null) {
                    return Flux.just(sseMessageBuilder.buildErrorMessage(
                            "未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数"));
                }
                // 注册请求（取消令牌）
                DistributedRequestManager.RequestInfo requestInfo = requestManager.registerRequestWithToken(
                        agentName,
                        request.getCancelToken() != null ? request.getCancelToken()
                                : requestManager.generateCancelToken());
                String requestId = requestInfo.getRequestId();
                // 开始事件（包含 requestId）
                Flux<String> startFlux = Flux.just(sseMessageBuilder.buildMessageWithRequestId("start", null, requestId));
                // 完成事件
                Flux<String> doneFlux = Flux.just(sseMessageBuilder.buildDoneMessage());

                // 表单模式（阻塞校验）：以 streaming=false 交引擎，取回 Msg 后帧封装结构化数据
                if (!request.isStructuredStream()) {
                    log.info("流式结构化输出(表单模式, 经执行引擎): Agent={}, requestId={}", agentName, requestId);
                    ExecutionRequest execReq = ExecutionRequest.builder()
                            .agentName(agentName)
                            .message(request.getMessage())
                            .userId(request.getUserId())
                            .conversationId(request.getConversationId())
                            .streaming(false)
                            .structuredOutput(true)
                            .structuredSchema(request.getSchema())
                            .structuredSchemaName(request.getSchemaName())
                            .build();
                    Mono<String> structuredFlux = Mono.fromCallable(() -> {
                        ExecutionResult res = executionEngine.execute(execReq, null);
                        if (res.isError()) {
                            throw new RuntimeException(res.getErrorMessage());
                        }
                        Msg result = res.getResult();
                        if (result == null) {
                            throw new IllegalStateException("Agent 响应为空");
                        }
                        Object data = parseStructuredFromMessage(result, target);
                        return sseMessageBuilder.buildMessage("structured", sseMessageBuilder.toJsonString(data));
                    }).doFinally(sig -> requestManager.unregisterRequest(requestId))
                            .onErrorResume(e -> {
                                log.error("流式差异化输出异常: Agent={}, requestId={}", agentName, requestId, e);
                                return Mono.just(sseMessageBuilder.buildErrorMessage("结构化数据解析失败: " + e.getMessage()));
                            });
                    return Flux.concat(startFlux, structuredFlux, doneFlux);
                }

                // 流模式（逐字流 + 末尾自解析结构化）：以 streaming=true 交引擎产出原始事件流，边沿帧封装
                log.info("流式结构化输出(流模式, 经执行引擎): Agent={}, requestId={}", agentName, requestId);
                ExecutionRequest execReq = ExecutionRequest.builder()
                        .agentName(agentName)
                        .message(request.getMessage())
                        .userId(request.getUserId())
                        .conversationId(request.getConversationId())
                        .streaming(true)
                        .structuredOutput(true)
                        .structuredSchema(request.getSchema())
                        .structuredSchemaName(request.getSchemaName())
                        .build();
                Flux<String> streamFlux = executionEngine.frameStructuredStream(execReq, null,
                                msg -> {
                                    try {
                                        return parseStructuredFromMessage(msg, target);
                                    } catch (Exception e) {
                                        throw new RuntimeException(e);
                                    }
                                },
                                () -> requestManager.isRequestCancelled(requestId))
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
            } catch (Exception e) {
                log.error("流式差异化输出初始化异常: Agent={}", agentName, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(e.getMessage()));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 从模型结果中提取结构化数据：优先用框架绑定的 structured_output 元数据，否则把文本当 JSON 自解析。
     */
    private Object parseStructuredFromMessage(Msg message, SchemaTarget target) throws Exception {
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
    private SchemaTarget resolveSchemaTarget(String agentName, UnifiedChatRequest request) {
        // 优先检查：内联 JSON Schema
        Map<String, Object> schema = request.getSchema();
        if (schema != null && !schema.isEmpty()) {
            log.info("流式差异化输出使用内联 JSON Schema");
            return new SchemaTarget(null, objectMapper.valueToTree(schema));
        }
        // 其次检查：命名 Schema（通过 schemaName 参数）
        String schemaName = request.getSchemaName();
        if (schemaName != null && !schemaName.isBlank()) {
            Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
            if (schemaClass != null) {
                log.info("流式差异化输出使用命名 Schema [{}]: {}", schemaName, schemaClass.getName());
                return new SchemaTarget(schemaClass, null);
            }
            log.warn("未找到命名 Schema [{}]，尝试使用默认 Schema", schemaName);
        }
        // 最后检查：默认 Schema 表
        Class<?> schemaClass = schemaClassRegistry.get(agentName);
        if (schemaClass != null) {
            log.info("流式差异化输出使用默认 Schema 表: {}", schemaClass.getName());
            return new SchemaTarget(schemaClass, null);
        }
        return null;
    }
}
