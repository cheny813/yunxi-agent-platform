package io.yunxi.platform.conversation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.IntentContext;
import io.yunxi.platform.intent.IntentEngine;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.routing.IntentAwareAgentResolver;
import io.yunxi.platform.intent.routing.RouteDecision;
import io.yunxi.platform.security.auth.SecurityContext;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.MemoryConfig;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.ConversationChatRequest;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.entity.ConversationEntity;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.tracing.LlmMetrics;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 对话应用服务
 *
 * <p>
 * 【应用层】负责对话业务流程的编排，协调多个领域服务完成对话（业务编排）
 * </p>
 * <p>
 * <b>职责范围</b>:
 * <ul>
 * <li>编排对话流程（协调 Agent、会话、记忆等领域服务）</li>
 * <li>管理对话超时</li>
 * <li>处理流式响应</li>
 * <li>管理对话状态</li>
 * </ul>
 * </p>
 * <p>
 * <b>层级说明</b>:
 * <ul>
 * <li>所属层级：应用层</li>
 * <li>职责：业务流程编排，协调多个领域服务</li>
 * <li>依赖服务:
 * <ul>
 * <li>AgentService - Agent 生命周期管理</li>
 * <li>ConversationDomainService - 会话管理</li>
 * <li>MemoryCoordinatorService - 记忆管理</li>
 * </ul>
 * </li>
 * <li>不包含：具体业务逻辑（由领域层负责）</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
public class ChatAppService {

    /** 日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(ChatAppService.class);

    /**
     * Agent 服务
     */
    private final AgentService agentService;

    /** Profile 路由器 */
    private final ProfileRouter profileRouter;

    /** AgentScope 配置属性 */
    private final AgentscopeCoreProperties properties;

    /** 会话领域服务 */
    private final ConversationDomainService conversationDomainService;

    /** SSE 消息构建器 */
    private final SseMessageBuilder sseMessageBuilder;

    /** 意图引擎（四阶段前置管道：NER → 改写 → 分类 → 映射） */
    private final IntentEngine intentEngine;

    /** 意图路由解析器（识别→路由闭环，advisory 改道） */
    private final IntentAwareAgentResolver intentRouter;

    /** 文件上传服务 */
    private final FileUploadService fileUploadService;

    /** 安全上下文 */
    private final SecurityContext securityContext;

    /** LLM 指标与日志收集器（记录 token 消耗与耗时） */
    private final LlmMetrics llmMetrics;

    /**
     * 构造对话应用服务
     *
     * @param agentService              Agent 服务
     * @param properties                AgentScope 配置属性
     * @param conversationDomainService 会话领域服务
     * @param sseMessageBuilder         SSE 消息构建器
     * @param intentEngine               意图引擎（四阶段前置管道）
     * @param intentRouter               意图路由解析器（advisory 改道）
     * @param fileUploadService         文件上传服务
     * @param securityContext           安全上下文
     * @param profileRouter             Profile 路由器
     */
    public ChatAppService(AgentService agentService, AgentscopeCoreProperties properties,
            ConversationDomainService conversationDomainService,
            SseMessageBuilder sseMessageBuilder,
            IntentEngine intentEngine,
            IntentAwareAgentResolver intentRouter,
            FileUploadService fileUploadService,
            SecurityContext securityContext,
            LlmMetrics llmMetrics,
            ProfileRouter profileRouter) {
        this.agentService = agentService;
        this.profileRouter = profileRouter;
        this.properties = properties;
        this.conversationDomainService = conversationDomainService;
        this.sseMessageBuilder = sseMessageBuilder;
        this.intentEngine = intentEngine;
        this.intentRouter = intentRouter;
        this.fileUploadService = fileUploadService;
        this.securityContext = securityContext;
        this.llmMetrics = llmMetrics;
    }

    /**
     * 解析 Agent 实例（支持用户工作区隔离和 Profile 路由）
     * <p>
     * 路由优先级：用户工作区 > Profile 路由 > 默认 Agent
     * </p>
     *
     * @param name    Agent 名称
     * @param profile Profile 名称（可为 null）
     * @param userId  用户 ID（可为 null）
     * @return Agent 实例
     */
    private Agent resolveAgent(String name, String profile, String userId) {
        // 多租户工作空间隔离完全复用 AgentScope 原生能力（HarnessAgent.workspaceFor）：
        // Agent 为共享实例，调用时通过 RuntimeContext(userId, sessionId) 由框架按用户命名空间隔离，
        // yunxi 不再自建每用户 Bean 与路径。userId 经 RuntimeContext 透传，此处仅用于路由决策。
        // 优先：Profile 路由
        if (profile != null && !profile.isBlank()) {
            return profileRouter.resolve(name, profile);
        }
        // 默认：全局共享 Agent（多租户由 AgentScope 运行时隔离）
        return agentService.getAgentInstance(name);
    }

    /**
     * 构建 AgentScope 原生运行时上下文，携带 userId 与 sessionId。
     *
     * <p>AgentScope 的 HarnessAgent.workspaceFor(userId, sessionId) 据此按用户命名空间隔离工作空间，
     * 并对 AgentState 按 (userId, sessionId) 分会话槽。yunxi 不在此做任何封装。</p>
     *
     * @param userId    用户 ID（可为 null/blank）
     * @param sessionId 会话 ID（通常为 conversationId，可为 null/blank）
     * @return RuntimeContext
     */
    private RuntimeContext buildRuntimeContext(String userId, String sessionId) {
        RuntimeContext.Builder b = RuntimeContext.builder();
        if (userId != null && !userId.isBlank()) {
            b.userId(userId);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            b.sessionId(sessionId);
        }
        return b.build();
    }

    /**
     * 格式化 Agent 调用异常为用户友好消息
     * <p>
     * 对已知的内部错误进行翻译，避免向用户暴露技术细节。
     * </p>
     *
     * @param e 原始异常
     * @return 面向用户的友好错误描述
     */
    private String formatAgentError(Throwable e) {
        if (e instanceof IllegalArgumentException) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("different type of Path")) {
                log.warn("Path 文件系统类型不匹配，这通常发生在从 JAR 加载 classpath 资源时。"
                        + "可尝试设置 agentscope.extensions.skills.enabled=false", e);
                return "服务内部错误，请联系管理员";
            }
        }
        // 其他异常直接返回消息
        String message = e.getMessage();
        return message != null ? message : "未知错误";
    }

    /**
     * 发起对话请求
     *
     * @param name    Agent 名称
     * @param request 对话请求
     * @return 对话响应
     */
    public ChatResponse chat(String name, ChatRequest request) {
        // 获取真实 Agent 实例（支持用户工作区隔离）
        String userId = securityContext.getCurrentUserId();
        Agent agent = resolveAgent(name, null, userId);

        String message = request == null ? null : request.getMessage();
        if (message == null || message.isBlank()) {
            throw new BadRequestException("message 不能为空");
        }

        try {
            Msg userMsg = Msg.builder()
                    .textContent(message)
                    .build();

            // 调用 Agent，设置超时；多租户隔离经 RuntimeContext 透传 userId
            // 统一复用 streamEvents 的收尾语义（collectResultMsg），避免 agent.call 在 Supervisor
            // 以 tool-call 父轮结束时丢失子 Agent 最终合成文本（仅拿到 preamble）
            Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
            Msg responseMsg = collectResultMsg(agent, userMsg, userId, userId, timeout);

            if (responseMsg == null) {
                throw new RuntimeException("Agent 响应为空");
            }

            llmMetrics.recordAndLogUsage(name, "yunxi", responseMsg.getUsage());

            return new ChatResponse(responseMsg.getTextContent());

        } catch (Exception e) {
            log.error("对话执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话执行失败", e);
        }
    }

    /**
     * 基于会话的对话（支持多轮对话上下文）
     *
     * @param conversationId 会话 ID
     * @param request        对话请求
     * @return 对话响应
     */
    public ChatResponse chatWithConversation(String name, ConversationChatRequest request) {
        log.info("开始基于会话的对话: Agent={}, ConversationId={}", name, request.getConversationId());

        // 获取会话信息（用于提取用户上下文）
        ConversationEntity conversation = conversationDomainService.getConversation(request.getConversationId());

        // 从会话中获取真实用户ID
        String userId = conversation.getUserId() != null ? conversation.getUserId()
                : securityContext.getCurrentUserId();

        // 获取 Agent 实例（支持用户工作区隔离）
        Agent agent = resolveAgent(conversation.getAgentName(), null, userId);

        try {
            // 构建用户消息
            Msg userMsg = Msg.builder()
                    .textContent(request.getMessage())
                    .build();

            // 获取记忆配置
            var memoryConfig = request.getMemoryConfig();
            Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
            Msg responseMsg;

            // 意图分析（四阶段前置管道，取代场景检测；sceneName 语义不变）
            // ConversationChatRequest 无 profile 字段，非流式入口 profile 传 null
            IntentResult intentResult = intentEngine.analyze(buildIntentContext(
                    request.getMessage(), conversation, userId, request.getConversationId(), null));
            String sceneName = intentResult.sceneName();

            // 意图路由：命中且采纳则改道（advisory，未命中/失败保持原 Agent；
            // ConversationChatRequest 无 profile 字段，意图路由不叠加 Profile）
            RouteDecision decision = intentRouter.resolve(
                    conversation.getAgentName(), null, userId, intentResult);
            if (decision.adopted()) {
                agent = decision.agent();
            }

            // 实体注入（K6：重建 userMsg，仅在实体非空时）
            if (!intentResult.entities().isEmpty()) {
                userMsg = Msg.builder()
                        .textContent(userMsg.getTextContent() + "\n\n"
                                + buildEntityContextBlock(intentResult.entities()))
                        .build();
            }

            // RAG检索：获取相关文件内容
            List<FileSearchResult> relevantFiles = new ArrayList<>();
            try {
                FileSearchRequest searchRequest = FileSearchRequest.builder()
                        .userId(conversation.getUserId() != null ? conversation.getUserId() : "default")
                        .query(request.getMessage())
                        .topK(3)
                        .threshold(0.7)
                        .includeContent(true)
                        .build();

                relevantFiles = fileUploadService.searchRelevantFiles(searchRequest);

                if (!relevantFiles.isEmpty()) {
                    log.info("RAG检索到 {} 个相关文件: {}", relevantFiles.size(),
                            relevantFiles.stream()
                                    .map(f -> f.getFileName() + "(" + String.format("%.2f", f.getSimilarity()) + ")")
                                    .collect(Collectors.joining(", ")));
                }
            } catch (Exception e) {
                log.warn("RAG检索失败（不影响对话）: {}", e.getMessage());
            }

            // 根据记忆模式处理
            if (memoryConfig.isNone() || !request.isIncludeHistory()) {
                // 无记忆模式 - 不包含历史，作为新对话
                log.debug("无记忆模式: ConversationId={}", request.getConversationId());
                responseMsg = collectResultMsg(agent, userMsg, userId, request.getConversationId(), timeout);
            } else {
                // 使用智能记忆系统
                try {
                    // 获取当前会话的所有历史消息
                    List<Msg> historyMessages = conversation.getMessages();
                    log.info("同步对话 - 历史消息数量: {}, conversationId={}",
                            historyMessages.size(), request.getConversationId());

                    // 添加用户消息到记忆（由 HarnessAgent 内部 MemoryFlushHook 自动处理）
                    // 将用户消息封装为 Msg 列表，供后续记忆处理
                    List<Msg> userMessagesList = new ArrayList<>();
                    userMessagesList.add(userMsg);
                    List<Msg> enhancedMessages = new ArrayList<>(historyMessages);
                    enhancedMessages.addAll(userMessagesList);

                    // 构建最终上下文消息列表（完整历史消息作为上下文，压缩由 HarnessAgent 内部处理）
                    List<Msg> contextMessages = new ArrayList<>(enhancedMessages);

                    // RAG增强：添加相关文件内容到上下文
                    if (!relevantFiles.isEmpty()) {
                        String fileContext = buildFileContext(relevantFiles);
                        log.debug("添加RAG文件上下文: {}", fileContext);

                        // 创建系统消息包含文件上下文
                        Msg ragContextMsg = Msg.builder()
                                .textContent(fileContext)
                                .build();

                        // 将RAG上下文插入到消息列表前面
                        List<Msg> enhancedContext = new ArrayList<>();
                        enhancedContext.add(ragContextMsg);
                        enhancedContext.addAll(contextMessages);

                        contextMessages = enhancedContext;
                    }

                    // 调用 Agent
                    log.debug("调用 Agent: ConversationId={}, contextCount={}, mode={}, ragFiles={}",
                            request.getConversationId(), contextMessages.size(), memoryConfig.getMemoryMode(),
                            relevantFiles.size());
                    responseMsg = collectResultMsg(agent, contextMessages, userId, request.getConversationId(), timeout);

                    // 添加助手回复到记忆（由 HarnessAgent 内部 MemoryFlushHook 自动处理）
                    // 助手回复由 HarnessAgent 内部 MemoryFlushHook 自动写入记忆

                } catch (Exception e) {
                    log.error("智能记忆系统失败，降级为简单模式: ConversationId={}", request.getConversationId(), e);

                    // 降级处理：使用简单记忆模式
                    List<Msg> history = conversation.getMessages();
                    int maxHistory = request.getMaxHistory();
                    if (maxHistory > 0 && history.size() > maxHistory) {
                        history = history.subList(history.size() - maxHistory, history.size());
                    }

                    List<Msg> allMessages = new ArrayList<>(history);
                    allMessages.add(userMsg);

                    responseMsg = collectResultMsg(agent, allMessages, userId, request.getConversationId(), timeout);
                }
            }

            if (responseMsg == null) {
                throw new RuntimeException("Agent 响应为空");
            }

            // 保存用户消息和助手回复到会话（作为完整历史记录）
            conversation.addMessage(userMsg);
            conversation.addMessage(responseMsg);

            // 持久化会话到数据库（含消息内容）
            conversationDomainService.saveConversation(conversation);

            String reply = responseMsg.getTextContent();

            llmMetrics.recordAndLogUsage(conversation.getAgentName(), "yunxi", responseMsg.getUsage());

            ChatResponse response = new ChatResponse(reply);
            response.setConversationId(request.getConversationId());
            return response;

        } catch (Exception e) {
            log.error("对话执行失败: {}", e.getMessage(), e);
            throw new RuntimeException("对话执行失败", e);
        }
    }

    /**
     * 从 Agent 的事件流中收集最终回复消息（同步语义）。
     *
     * <p>统一复用 {@link #buildStreamResponse} 流式分支的收尾语义：最终文本取自
     * {@code AGENT_RESULT} 事件的 {@code resultMsg}，与流式分支完全一致。
     * 这解决了原先使用 {@code agent.call(...).getReply()} 在 Supervisor 模式下，Agent 把任务
     * 委派给子 Agent 并以"父轮 tool-call"结束时，只能拿到 preamble（如"我将为您生成…"）而
     * 丢失子 Agent 最终合成文本的问题。</p>
     *
     * <p>入参 {@code inputMsg} 兼容两种形态：单条 {@link Msg} 或 {@link List}{@code <Msg>}，
     * 分别映射到 {@code streamEvents(Msg, RuntimeContext)} 与
     * {@code streamEvents(List, RuntimeContext)}。</p>
     *
     * @return Agent 最终合成的回复消息；若事件流未产生 AGENT_RESULT 则返回 null
     */
    private Msg collectResultMsg(Agent agent, Object inputMsg, String userId,
            String conversationId, Duration timeout) {
        HarnessAgent harnessAgent = (HarnessAgent) agent;
        io.agentscope.core.agent.RuntimeContext rc = buildRuntimeContext(userId, conversationId);

        Flux<io.agentscope.core.event.AgentEvent> eventFlux;
        if (inputMsg instanceof List) {
            @SuppressWarnings("unchecked")
            List<Msg> messages = (List<Msg>) inputMsg;
            eventFlux = harnessAgent.streamEvents(messages, rc);
        } else {
            eventFlux = harnessAgent.streamEvents((Msg) inputMsg, rc);
        }

        final Msg[] resultHolder = { null };
        eventFlux
                .timeout(timeout)
                .filter(e -> e.getType() == io.agentscope.core.event.AgentEventType.AGENT_RESULT)
                .ofType(io.agentscope.core.event.AgentResultEvent.class)
                .doOnNext(resultEvent -> resultHolder[0] = resultEvent.getResult())
                .blockLast(); // 同步消费整个事件流，等价于"收集完再返回"的非流式

        return resultHolder[0];
    }

    /**
     * 发起无会话的流式对话（不使用多轮记忆）
     *
     * <p>用于一次性问答场景：构建用户消息后经 {@link #buildStreamResponse} 调用 Agent 的
     * {@code streamEvents} 并以 SSE 格式流式返回。多租户隔离经 {@code RuntimeContext} 透传 userId。</p>
     *
     * @param name    Agent 名称
     * @param request 流式对话请求
     * @return Flux&lt;String&gt; SSE 格式的响应流
     */
    public Flux<String> chatStream(String name, StreamChatRequest request) {
        log.info("开始流式对话: Agent={}, Message={}, responseMode={}", name, request.getMessage(),
                request.getResponseMode());

        // 从安全上下文获取用户ID（支持请求头 X-User-Id、ThreadLocal 等多种来源）
        String userId = securityContext.getCurrentUserId();

        return Flux.defer(() -> {
            try {
                // 获取 Agent 实例（支持用户工作区隔离和 Profile 路由）
                Agent agent = resolveAgent(name, request.getProfile(), userId);

                // 构建用户消息（含上下文数据）
                Msg userMsg = buildUserMessage(request.getMessage(), request.getContextData());

                // 构建响应式流（无会话，不使用记忆）
                // 注：计划模式已交由 AgentScope PlanModeMiddleware 统一处理（见 AgentConfigurer），
                // 此处不再做 yunxi 自建的规划预创建。
                return buildStreamResponse(agent, userMsg, request, null, null, null, null, null, userId)
                        .doOnNext(chunk -> {
                            // 流式输出的每个 chunk 可以在这里处理
                            log.trace("流式输出chunk: {}", chunk);
                        });

            } catch (Exception e) {
                log.error("流式对话失败: Agent={}", name, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(formatAgentError(e)));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建用户消息（含上下文数据注入）
     *
     * <p>
     * 如果请求中包含 contextData，会自动将其注入到用户消息中，
     * 让 AI 能够理解当前页面的业务上下文。
     * </p>
     *
     * @param message     用户原始消息
     * @param contextData 上下文数据（可选）
     * @return 注入上下文后的用户消息
     */
    private Msg buildUserMessage(String message, Map<String, Object> contextData) {
        String finalMessage = message;

        log.info("构建用户消息 - contextData={}", contextData);

        if (contextData != null && !contextData.isEmpty()) {
            try {
                // 通用上下文格式化
                String contextStr = formatContextData(contextData);
                StringBuilder fullContext = new StringBuilder();
                fullContext.append(contextStr);

                if (fullContext.length() > 0) {
                    finalMessage = fullContext.toString() + "\n\n用户问题: " + message;
                }

                log.info("上下文数据已注入: {}", contextData.keySet());
            } catch (Exception e) {
                log.warn("上下文数据格式化失败，使用原始消息", e);
            }
        } else {
            // contextData 为空是正常行为，只有传入 contextData 时才需要注入上下文
            log.debug("快速模式: 跳过 RAG/记忆/场景检测，contextData为空，直接使用原始消息");
        }

        return Msg.builder().textContent(finalMessage).build();
    }

    /**
     * 格式化上下文数据为可读文本
     *
     * <p>
     * 处理通用 key（configSummary、formData、pageType），
     * 业务上下文通过工作区 knowledge/ 文件自动注入。
     * </p>
     *
     * @param contextData 页面收集的上下文数据
     * @return 拼接后的可读文本（含引导语）
     */
    private String formatContextData(Map<String, Object> contextData) {
        StringBuilder sb = new StringBuilder();
        sb.append("[当前页面上下文信息]\n");
        sb.append("注意：以下信息是系统自动从当前页面收集的，AI应该直接使用这些数据回答用户问题，不要再询问用户。\n\n");

        for (Map.Entry<String, Object> entry : contextData.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value == null) {
                continue;
            }

            // 跳过 pageType（仅用于场景判断，不注入上下文）
            if ("pageType".equals(key)) {
                continue;
            }

            // 通用处理 configSummary
            if ("configSummary".equals(key) && value instanceof Map) {
                sb.append("## 配置概要\n");
                Map<?, ?> summary = (Map<?, ?>) value;
                for (Map.Entry<?, ?> se : summary.entrySet()) {
                    if (se.getValue() != null && !"".equals(se.getValue())) {
                        sb.append("- ").append(se.getKey()).append(": ").append(se.getValue()).append("\n");
                    }
                }
                continue;
            }

            // 通用处理 formData
            if ("formData".equals(key) && value instanceof Map) {
                sb.append("## 表单数据\n");
                Map<?, ?> formData = (Map<?, ?>) value;
                for (Map.Entry<?, ?> fe : formData.entrySet()) {
                    if (fe.getValue() != null && !"".equals(fe.getValue())) {
                        sb.append("- ").append(fe.getKey()).append(": ").append(fe.getValue()).append("\n");
                    }
                }
                continue;
            }

            // 其他字段默认处理
            sb.append("- ").append(key).append(": ");
            if (value instanceof Map) {
                sb.append(formatMapValue((Map<?, ?>) value));
            } else if (value instanceof List) {
                List<?> listValue = (List<?>) value;
                sb.append(listValue.isEmpty() ? "(无数据)" : "[共" + listValue.size() + "项]");
            } else {
                sb.append(value);
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 格式化 Map 值
     */
    private String formatMapValue(Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first)
                sb.append(", ");
            Object v = entry.getValue();
            if (v != null && !"".equals(v)) {
                sb.append(entry.getKey()).append("=").append(v);
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * 基于会话的流式对话（支持历史上下文）
     * 在已有会话中进行流式对话，自动管理多轮上下文。
     *
     * @param conversationId 会话 ID
     * @param request        流式对话请求
     * @return Flux<String> SSE 格式的响应流
     */
    public Flux<String> chatStreamWithConversation(String conversationId, StreamChatRequest request) {
        log.info("开始基于会话的流式对话: ConversationId={}, Message={}, responseMode={}",
                conversationId, request.getMessage(), request.getResponseMode());

        return Flux.defer(() -> {
            try {
                // 获取会话（用于提取用户上下文）
                ConversationEntity conversation = conversationDomainService.getConversation(conversationId);
                String userId = conversation.getUserId() != null ? conversation.getUserId()
                        : securityContext.getCurrentUserId();

                // 获取 Agent 实例（支持用户工作区隔离和 Profile 路由）
                Agent agent = resolveAgent(conversation.getAgentName(), request.getProfile(), userId);

                // 构建用户消息（含上下文数据）
                Msg userMsg = buildUserMessage(request.getMessage(), request.getContextData());

                // ---- 快速模式：跳过 RAG、记忆、场景检测，直接调用 Agent ----
                // 注：规划模式已交由 AgentScope PlanModeMiddleware 统一处理，此处不再做 yunxi 自建规划预创建。
                if (request.isQuickMode()) {
                    log.info("快速模式: ConversationId={}, 跳过 RAG/记忆/场景检测", conversationId);
                    List<Msg> quickMessages = new ArrayList<>();
                    quickMessages.add(userMsg);
                    conversation.addMessage(userMsg);

                    return buildStreamResponse(agent, quickMessages, request, conversation, null,
                            conversationId, null, null, userId)
                            .doOnComplete(() -> log.info("快速模式对话完成: ConversationId={}", conversationId));
                }

                // ---- 标准模式 / 深度模式 ----

                // 意图分析（四阶段前置管道，取代场景检测；quickMode 分支已提前返回，天然零开销）
                // 流式入口传 request.getProfile() 参与域规则匹配
                IntentResult intentResult = intentEngine.analyze(buildIntentContext(
                        request.getMessage(), conversation, userId, conversationId, request.getProfile()));
                String sceneName = intentResult.sceneName();

                // 意图路由：命中且采纳则改道（advisory，未命中/失败保持原 Agent；
                // 命中目标 agent 仍尊重 request.profile 的 Profile 路由）
                RouteDecision decision = intentRouter.resolve(
                        conversation.getAgentName(), request.getProfile(), userId, intentResult);
                if (decision.adopted()) {
                    agent = decision.agent();
                }

                // 实体注入（K6：重建 userMsg，仅在实体非空时）
                if (!intentResult.entities().isEmpty()) {
                    userMsg = Msg.builder()
                            .textContent(userMsg.getTextContent() + "\n\n"
                                    + buildEntityContextBlock(intentResult.entities()))
                            .build();
                }

                // 获取记忆配置
                var memoryConfig = request.getMemoryConfig();
                List<Msg> allMessages;

                // 根据记忆模式处理
                if (memoryConfig.isNone()) {
                    // 无记忆模式
                    log.debug("流式对话 - 无记忆模式: ConversationId={}", conversationId);
                    allMessages = new ArrayList<>();
                    allMessages.add(userMsg);
                    // 保存用户消息到会话（只保存一次）
                    conversation.addMessage(userMsg);
                } else {
                    // 使用智能记忆系统
                    try {
                        // 获取当前会话的所有历史消息
                        List<Msg> historyMessages = conversation.getMessages();
                        log.info("流式对话 - 历史消息数量: {}, conversationId={}",
                                historyMessages.size(), conversationId);

                        // 添加用户消息到记忆（由 HarnessAgent MemoryFlushHook 自动处理）

                        // 构建完整上下文：历史消息 + 当前用户消息
                        List<Msg> allHistoryMessages = new ArrayList<>(historyMessages);
                        allHistoryMessages.add(userMsg);

                        // 使用完整历史消息作为上下文（HarnessAgent 内部自动管理压缩和溢出恢复）
                        allMessages = new ArrayList<>(allHistoryMessages);

                        log.info("流式对话 - 智能记忆: ConversationId={}, historyCount={}, contextCount={}, mode={}",
                                conversationId, historyMessages.size(), allMessages.size(),
                                memoryConfig.getMemoryMode());

                        // 保存用户消息到会话（只保存一次）
                        conversation.addMessage(userMsg);

                    } catch (Exception e) {
                        log.error("流式对话 - 智能记忆失败，降级为简单模式: ConversationId={}",
                                conversationId, e);
                        // 降级处理：使用简单记忆（直接包含所有历史消息）
                        allMessages = new ArrayList<>(conversation.getMessages());
                        allMessages.add(userMsg);
                        // 保存用户消息到会话（只保存一次）
                        conversation.addMessage(userMsg);
                    }
                }

                // 思考事件文本：展示 Agent 正在分析的实际问题，比空洞的"A2A 协作模式"更有意义
                String thinkingText;
                boolean useA2A = request.isUseA2A() || request.isDeepMode();
                if (useA2A || request.isEnableThinking()) {
                    String userQuestion = request.getMessage();
                    if (userQuestion != null && userQuestion.length() > 80) {
                        userQuestion = userQuestion.substring(0, 80) + "...";
                    }
                    if (useA2A) {
                        thinkingText = String.format("分析需求: %s\n正在协调专家智能体处理 (%d 条上下文)..",
                                userQuestion, allMessages.size());
                    } else {
                        thinkingText = String.format("分析: %s", userQuestion);
                    }
                } else {
                    thinkingText = null;
                }

                // 构建响应式流并保存消息
                return buildStreamResponse(agent, allMessages, request, conversation, thinkingText,
                        conversationId, memoryConfig, sceneName, userId)
                        .doOnComplete(() -> {
                            log.info("基于会话的流式对话完成: ConversationId={}", conversationId);
                        });

            } catch (Exception e) {
                log.error("基于会话的流式对话失败: ConversationId={}", conversationId, e);
                return Flux.just(sseMessageBuilder.buildErrorMessage(formatAgentError(e)));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 构建统一的 SSE 流式响应。
     *
     * <p>编排流式对话的完整事件链路：开始事件 → 思考事件 → Agent 事件流 → 结束事件。
     * 通过 AgentScope 的 {@code HarnessAgent.streamEvents} 获取标准化 {@code AgentEvent}，再将内容类事件
     * （文本/推理增量、最终结果）转换为 SSE 内容消息，其余事件原样透传前端，并附加 UX 友好的状态消息。</p>
     *
     * @param agent          Agent 实例
     * @param inputMsg       输入消息（单条 {@code Msg} 或 {@code List<Msg>} 历史上下文）
     * @param request        流式对话请求（用于判断运行模式、超时等）
     * @param conversation   会话实体（可为 null，无会话场景）
     * @param thinkingText   思考事件文本（可 null，表示不展示思考态）
     * @param conversationId 会话 ID（可为 null）
     * @param memoryConfig   记忆配置（可为 null，无会话场景）
     * @param sceneName      场景名称（可为 null）
     * @param userId         用户 ID（用于运行时上下文隔离）
     * @return Flux&lt;String&gt; 组合后的 SSE 事件流
     */
    private Flux<String> buildStreamResponse(Agent agent,
            Object inputMsg,
            StreamChatRequest request,
            ConversationEntity conversation,
            String thinkingText,
            String conversationId,
            MemoryConfig memoryConfig,
            String sceneName,
            String userId) {
        // 1. 开始事件（如果有会话ID，包含在开始消息中）
        Flux<String> startFlux = Flux.just(
                conversationId != null ? sseMessageBuilder.buildStartMessageWithConversationId(conversationId)
                        : sseMessageBuilder.buildStartMessage());

        // 2. 确定运行模式（深度/A2A 模式使用更长超时）
        boolean useA2A = request.isUseA2A() || request.isDeepMode();
        int timeoutSeconds = useA2A
                ? properties.getChatTimeoutSeconds() * 3 // 深度/A2A 模式 3 倍超时
                : properties.getChatTimeoutSeconds();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        if (useA2A) {
            log.info("深度/A2A 协作模式已启用，超时时间: {}s", timeoutSeconds);
        }

        // 3. 思考事件（A2A 模式用 agent_status 类型，前端按独立状态行展示；
        //    普通模式用 thinking 类型，推理文本流式拼接）
        Flux<String> thinkingFlux = thinkingText != null
                ? Flux.just(useA2A
                        ? sseMessageBuilder.buildAgentStatusMessage(thinkingText)
                        : sseMessageBuilder.buildThinkingMessage(thinkingText))
                : Flux.empty();

        // 4. 使用 agent.streamEvents() 获取标准化流式事件
        // HarnessAgent 通过 streamEvents() 暴露流式事件，承载运行时上下文
        io.agentscope.harness.agent.HarnessAgent harnessAgent = (io.agentscope.harness.agent.HarnessAgent) agent;
        io.agentscope.core.agent.RuntimeContext rc = buildRuntimeContext(userId, conversationId);
        Flux<io.agentscope.core.event.AgentEvent> eventFlux;
        if (inputMsg instanceof List) {
            @SuppressWarnings("unchecked")
            List<Msg> messages = (List<Msg>) inputMsg;
            eventFlux = harnessAgent.streamEvents(messages, rc);
        } else {
            eventFlux = harnessAgent.streamEvents((Msg) inputMsg, rc);
        }

        // 积累推理文本，最终存入 Msg metadata
        StringBuilder thinkingAccumulator = new StringBuilder();

        // 6. 将 AgentEvent 流转换为 SSE 事件流（内容事件与块边界事件被消费，其余原生透传）
        Flux<String> contentFlux = eventFlux
                .timeout(timeout)
                .flatMapSequential(event -> {
                    io.agentscope.core.event.AgentEventType type = event.getType();

                    // ── 已消费事件：需要后端处理（流式输出/保存会话）或仅作块边界标记 ──
                    // 这类事件已被完整消费，不再额外透传（避免重复流量与「半截」原生块）
                    if (type == io.agentscope.core.event.AgentEventType.TEXT_BLOCK_DELTA) {
                        String delta = event instanceof io.agentscope.core.event.TextBlockDeltaEvent
                                ? ((io.agentscope.core.event.TextBlockDeltaEvent) event).getDelta()
                                : null;
                        return delta != null && !delta.isEmpty()
                                ? Flux.just(sseMessageBuilder.buildContentMessage(delta))
                                : Flux.empty();
                    }
                    if (type == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_DELTA) {
                        String text = event instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent
                                ? ((io.agentscope.core.event.ThinkingBlockDeltaEvent) event).getDelta()
                                : null;
                        if (text != null && !text.isEmpty()) {
                            thinkingAccumulator.append(text);
                            return Flux.just(sseMessageBuilder.buildThinkingMessage(text));
                        }
                        return Flux.empty();
                    }

                    // 文本/思考块的边界事件（START/END）：块内容已由 *_BLOCK_DELTA 消费并替换为
                    // 友好 content/thinking 消息，此处直接消费、不再透传原生边界事件，
                    // 避免前端收到「START 却永远等不到 DELTA」的半截原生块
                    if (type == io.agentscope.core.event.AgentEventType.TEXT_BLOCK_START
                            || type == io.agentscope.core.event.AgentEventType.TEXT_BLOCK_END
                            || type == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_START
                            || type == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_END) {
                        return Flux.empty();
                    }

                    if (type == io.agentscope.core.event.AgentEventType.AGENT_RESULT) {
                    if (event instanceof io.agentscope.core.event.AgentResultEvent resultEvent) {
                        Msg resultMsg = resultEvent.getResult();
                        ChatUsage usage = resultMsg != null ? resultMsg.getUsage() : null;
                        if (usage != null) {
                            llmMetrics.recordAndLogUsage(
                                    conversationId != null ? conversationId : "stream", "yunxi", usage);
                        }
                        String reasoningText = thinkingAccumulator.toString();
                            if (resultMsg != null && !reasoningText.isEmpty()) {
                                Map<String, Object> metadata = new HashMap<>();
                                if (resultMsg.getMetadata() != null) {
                                    metadata.putAll(resultMsg.getMetadata());
                                }
                                metadata.put("thinking", reasoningText);
                                resultMsg = Msg.builder()
                                        .role(resultMsg.getRole())
                                        .textContent(resultMsg.getTextContent())
                                        .metadata(metadata)
                                        .build();
                            }
                            String text = resultMsg != null ? resultMsg.getTextContent() : null;
                            if (text != null && !text.isEmpty()) {
                                // 持久化会话：用户消息已在前面 addMessage，此处补存助手回复，
                                // 确保刷新 UI 后能恢复完整对话
                                if (conversation != null) {
                                    conversation.addMessage(resultMsg);
                                    conversationDomainService.saveConversation(conversation);
                                }
                                int chunkSize = 200;
                                return Flux.fromStream(
                                        splitTextIntoChunks(text, chunkSize).stream())
                                        .map(chunk -> sseMessageBuilder.buildContentMessage(chunk));
                            }
                        }
                        return Flux.empty();
                    }

                    // ── 透传模式：所有其他事件 ——
                    // 收集要发送的消息列表：UX 友好消息 + 原生事件透传
                    List<String> messages = new ArrayList<>();

                    // 对需要向用户展示进度的事件附加 UX 友好消息
                    // 注意：MODEL_CALL_START/END 不附加 agent_status
                    // 原因：qwen-plus 等模型不产生 THINKING_BLOCK_DELTA，
                    // MODEL_CALL_START 几乎和 TEXT_BLOCK_DELTA 同时到达，
                    // 此时推理块显示"正在调用模型..."而答案已在流式输出，没有意义
                    switch (type) {
                        case MODEL_CALL_START:
                        case MODEL_CALL_END:
                            // 不附加 agent_status，仅透传原生事件
                            break;
                        case TOOL_CALL_START: {
                            io.agentscope.core.event.ToolCallStartEvent tc =
                                    (io.agentscope.core.event.ToolCallStartEvent) event;
                            messages.add(sseMessageBuilder.buildToolCallMessage(
                                    tc.getToolCallId(), tc.getToolCallName()));
                            messages.add(sseMessageBuilder.buildAgentStatusMessage(
                                    "正在执行: " + friendlyToolName(tc.getToolCallName())));
                            break;
                        }
                        case TOOL_CALL_END: {
                            io.agentscope.core.event.ToolCallEndEvent tc =
                                    (io.agentscope.core.event.ToolCallEndEvent) event;
                            messages.add(sseMessageBuilder.buildToolCallDoneMessage(
                                    tc.getToolCallId(), tc.getToolCallName()));
                            break;
                        }
                        case TOOL_RESULT_END: {
                            io.agentscope.core.event.ToolResultEndEvent tr =
                                    (io.agentscope.core.event.ToolResultEndEvent) event;
                            String stateValue = tr.getState() != null ? tr.getState().getValue() : "unknown";
                            String stateLabel = "SUCCESS".equalsIgnoreCase(stateValue) ? " 完成" : " (" + stateValue + ")";
                            messages.add(sseMessageBuilder.buildToolResultMessage(
                                    tr.getToolCallId(), tr.getToolCallName(), stateValue));
                            messages.add(sseMessageBuilder.buildAgentStatusMessage(
                                    friendlyToolName(tr.getToolCallName()) + stateLabel));
                            break;
                        }
                        default:
                            break;
                    }

                    // 原生事件透传给前端（所有事件，包括已发 agent_status 的）
                    messages.add(sseMessageBuilder.buildAgentEvent(event));

                    return Flux.fromIterable(messages);
                })
                .onErrorResume(e -> {
                    log.error("Agent 推理异常: {}", e.getMessage(), e);
                    return Flux.just(sseMessageBuilder.buildErrorMessage(formatAgentError(e)));
                });

        // 组合所有事件流（深度/A2A 模式进度通过工具调用事件驱动，不再使用定时心跳）
        // 关键修复：始终发送"处理完成"事件，确保前端能正确退出"正在生成回复..."状态
        // 修复前：quick 模式下 thinkingText 为 null，不发送完成事件 → 前端永远等待
        Flux<String> finishFlux = Flux.just(sseMessageBuilder.buildAgentStatusMessage("处理完成"));
        return Flux.concat(startFlux, thinkingFlux, contentFlux, finishFlux)
                .timeout(Duration.ofSeconds(properties.getChatTimeoutSeconds() + 30));
    }

    /**
     * 将文本按指定大小切块
     *
     * @param text     待切分的文本
     * @param chunkSize 每块的最大字符数
     * @return 切分后的文本块列表
     */
    private static List<String> splitTextIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    /**
     * 将 AgentScope 工具名称映射为中文描述（用于 A2A 推理块状态展示）。
     *
     * @param toolName AgentScope 原始工具名
     * @return 对应的中文友好描述；未匹配时返回原名称
     */
    private static String friendlyToolName(String toolName) {
        switch (toolName) {
            case "agent_spawn":         return "启动专家智能体";
            case "agent_send":          return "与专家智能体沟通";
            case "agent_list":          return "列出可用智能体";
            case "task_list":           return "查看任务列表";
            case "task_output":         return "产出任务结果";
            case "task_cancel":         return "取消任务";
            case "read_file":           return "读取文件";
            case "write_file":          return "写入文件";
            case "edit_file":           return "编辑文件";
            case "list_files":          return "浏览目录";
            case "grep_files":          return "搜索文件内容";
            case "glob_files":          return "匹配文件";
            case "execute":             return "执行命令";
            case "memory_search":       return "搜索记忆";
            case "memory_get":          return "获取记忆";
            case "session_history":     return "获取会话历史";
            case "session_list":        return "列出会话";
            case "session_search":      return "搜索会话";
            case "load_skill_through_path": return "加载技能";
            default:                    return toolName;
        }
    }

    /**
     * 构建RAG文件上下文
     *
     * @param relevantFiles 相关文件列表
     * @return 文件上下文文本
     */
    private String buildFileContext(List<FileSearchResult> relevantFiles) {
        StringBuilder context = new StringBuilder();
        context.append("[相关文件上下文]\n\n");

        for (FileSearchResult file : relevantFiles) {
            context.append(String.format("文件: %s (相似度: %.2f%%)\n", file.getFileName(), file.getSimilarity() * 100));
            context.append(String.format("类型: %s\n", file.getFileType().getDescription()));
            if (file.getContent() != null && !file.getContent().isEmpty()) {
                // 限制内容长度，避免token超限
                int maxContentLength = 1000;
                String content = file.getContent();
                if (content.length() > maxContentLength) {
                    content = content.substring(0, maxContentLength) + "...(内容已截断)";
                }
                context.append("内容:\n").append(content).append("\n");
            }
            context.append("---\n\n");
        }

        context.append("[请基于以上文件内容回答用户问题]\n");
        return context.toString();
    }

    /**
     * 构建意图分析上下文（取最近 3 轮消息供后续指代消解使用）。
     *
     * <p>加 profile 组件参与域规则匹配（流式入口传 {@code request.getProfile()}，
     * 非流式入口 ConversationChatRequest 无 profile 字段传 null）；domain 默认 null，
     * 由 DomainResolver 按规则链解析。</p>
     *
     * @param message        用户原始消息
     * @param conversation   会话实体
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param profile        Profile 名称（可 null）
     * @return 意图分析上下文
     */
    private IntentContext buildIntentContext(String message, ConversationEntity conversation,
            String userId, String conversationId, String profile) {
        List<Msg> recent = List.of();
        List<Msg> all = conversation.getMessages();          // F3：永不 null
        if (all != null && all.size() > 3) {
            recent = new ArrayList<>(all.subList(all.size() - 3, all.size()));
        } else if (all != null) {
            recent = new ArrayList<>(all);
        }
        return new IntentContext(message, conversation.getAgentName(), userId, conversationId, recent, null, profile);
    }

    /**
     * 构建实体上下文注入块。
     *
     * @param entities NER 实体列表
     * @return 注入文本（无实体时为空串）
     */
    private String buildEntityContextBlock(List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[问题实体]\n注意：以下实体已从问题中识别，AI 应直接使用。\n");
        for (Entity e : entities) {
            sb.append("- ").append(e.type()).append(": ").append(e.value()).append("\n");
        }
        return sb.toString();
    }
}
