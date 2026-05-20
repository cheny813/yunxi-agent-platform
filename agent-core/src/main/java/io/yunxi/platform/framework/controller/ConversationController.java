package io.yunxi.platform.framework.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.framework.agent.AgentDomainService;
import io.yunxi.platform.framework.agent.AgentInterruptService;
import io.yunxi.platform.framework.tool.ToolGroupManager;
import io.yunxi.platform.framework.conversation.ChatAppService;
import io.yunxi.platform.framework.conversation.ConversationDomainService;
import io.yunxi.platform.framework.structured.SchemaClassRegistry;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.ConversationChatRequest;
import io.yunxi.platform.shared.dto.ConversationInfoDto;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.framework.conversation.DistributedRequestManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 会话控制器
 *
 * <p>
 * 提供对话、流式输出、结构化输出、会话管理等 API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    /** 对话应用服务 */
    private final ChatAppService chatAppService;
    /** 会话领域服务 */
    private final ConversationDomainService conversationDomainService;
    /** Schema 类注册表 */
    private final SchemaClassRegistry schemaClassRegistry;
    /** Agent 领域服务 */
    private final AgentDomainService agentDomainService;
    /** SSE 消息构建器 */
    private final SseMessageBuilder sseMessageBuilder;
    /** 分布式请求管理器 */
    private final DistributedRequestManager requestManager;
    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 工具分组管理器（可选） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ToolGroupManager toolGroupManager;

    /**
     * 获取用户会话列表
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    @GetMapping("/list")
    public List<ConversationInfoDto> listConversations(@RequestParam String userId) {
        return conversationDomainService.listConversationsByUserId(userId);
    }

    /**
     * 通用对话接口（支持创建新会话或使用现有会话）
     *
     * @param request 对话请求
     * @return 对话响应
     */
    @PostMapping("/chat")
    public Object chat(@RequestBody UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }

        // 根据模式选择处理方式
        if (request.isStreamMode()) {
            throw new UnsupportedOperationException("请使用 /api/conversations/chat/stream 接口进行流式对话");
        }

        // 结构化输出处理
        if (request.isStructuredOutput()) {
            return handleStructuredOutput(request);
        }

        // 普通同步对话
        String conversationId = request.getConversationId();
        boolean needsConversation = (conversationId != null && !conversationId.isBlank()) ||
                Boolean.TRUE.equals(request.getAutoManageConversation());

        if (needsConversation) {
            // 如果有 conversationId，使用现有的；如果没有，创建新的
            if (conversationId == null || conversationId.isBlank()) {
                // 创建新会话
                CreateConversationRequest createReq = new CreateConversationRequest();
                createReq.setAgentName(agentName);
                if (request.getUserId() != null) {
                    createReq.setUserId(request.getUserId());
                }
                // 使用用户消息的前20个字符作为标题
                String title = request.getMessage().substring(0, Math.min(20, request.getMessage().length()));
                createReq.setTitle(title);

                ConversationInfoDto convInfo = conversationDomainService.createConversation(createReq);
                conversationId = convInfo.getId();
                log.info("创建新会话: {}", conversationId);
            }

            // 基于会话的同步对话
            ConversationChatRequest chatRequest = new ConversationChatRequest();
            chatRequest.setMessage(request.getMessage());
            chatRequest.setConversationId(conversationId);
            chatRequest.setAgentName(agentName);
            return chatAppService.chatWithConversation(agentName, chatRequest);
        } else {
            // 单轮对话，不使用会话
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setMessage(request.getMessage());
            chatRequest.setEnableThinking(request.getEnableThinking());
            return chatAppService.chat(agentName, chatRequest);
        }
    }

    /**
     * 处理同步结构化输出
     */
    private Object handleStructuredOutput(UnifiedChatRequest request) {
        String agentName = request.getAgentName();

        // 获取 Agent 实例
        ReActAgent agent = agentDomainService.getAgentInstance(agentName);

        // 构建用户消息
        Msg userMsg = Msg.builder()
                .textContent(request.getMessage())
                .role(MsgRole.USER)
                .build();

        try {
            Duration timeout = Duration.ofSeconds(300); // 5分钟超时

            // 方式1：使用动态 JSON Schema（最高优先级）
            Map<String, Object> schema = request.getSchema();
            if (schema != null && !schema.isEmpty()) {
                log.info("使用动态 JSON Schema 进行结构化输出");
                JsonNode schemaNode = objectMapper.valueToTree(schema);
                Msg response = agent.call(userMsg, schemaNode).block(timeout);
                return response.getStructuredData(false);
            }

            // 方式2：使用命名 Schema（通过 schemaName 参数）
            String schemaName = request.getSchemaName();
            if (schemaName != null && !schemaName.isBlank()) {
                Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
                if (schemaClass != null) {
                    log.info("使用命名 Schema [{}] 进行结构化输出: {}", schemaName, schemaClass.getName());
                    Msg response = agent.call(userMsg, schemaClass).block(timeout);
                    return response.getStructuredData(schemaClass);
                }
                log.warn("未找到命名 Schema [{}], 尝试使用默认 Schema", schemaName);
            }

            // 方式3：使用默认 Schema 类
            Class<?> schemaClass = schemaClassRegistry.get(agentName);
            if (schemaClass != null) {
                log.info("使用默认 Schema 类进行结构化输出: {}", schemaClass.getName());
                Msg response = agent.call(userMsg, schemaClass).block(timeout);
                return response.getStructuredData(schemaClass);
            }

            // 方式4：未配置 Schema
            throw new BadRequestException("未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数");

        } catch (Exception e) {
            log.error("结构化输出失败: {}", e.getMessage(), e);
            throw new RuntimeException("结构化输出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通用流式对话接口（支持创建新会话或使用现有会话）
     *
     * @param request 对话请求
     * @return 流式响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }

        // 流式结构化输出处理
        if (request.isStructuredOutput()) {
            return handleStreamStructuredOutput(request);
        }

        // 普通流式对话
        StreamChatRequest streamRequest = new StreamChatRequest();
        streamRequest.setMessage(request.getMessage());
        if (request.getEnableThinking() != null) {
            streamRequest.setEnableThinking(request.getEnableThinking());
        }
        if (request.getChunkSize() != null) {
            streamRequest.setChunkSize(request.getChunkSize());
        }
        if (request.getMemoryMode() != null) {
            streamRequest.setMemoryMode(request.getMemoryMode());
        }
        // 传递 A2A 模式设置
        streamRequest.setUseA2A(Boolean.TRUE.equals(request.getEnableA2A()));

        // 传递响应深度模式
        if (request.getResponseMode() != null) {
            streamRequest.setResponseMode(request.getResponseMode());
        }

        // 传递 Profile 选择
        if (request.getProfile() != null) {
            streamRequest.setProfile(request.getProfile());
        }

        // 传递上下文数据（Context Injection）
        if (request.getContextData() != null) {
            streamRequest.setContextData(request.getContextData());
        }

        // 判断是否需要使用会话
        String conversationId = request.getConversationId();
        boolean needsConversation = (conversationId != null && !conversationId.isBlank()) ||
                Boolean.TRUE.equals(request.getAutoManageConversation());

        if (needsConversation) {
            // 如果有 conversationId，使用现有的；如果没有，创建新的
            if (conversationId == null || conversationId.isBlank()) {
                // 创建新会话
                CreateConversationRequest createReq = new CreateConversationRequest();
                createReq.setAgentName(agentName);
                if (request.getUserId() != null) {
                    createReq.setUserId(request.getUserId());
                }
                // 使用用户消息的前20个字符作为标题
                String title = request.getMessage().substring(0, Math.min(20, request.getMessage().length()));
                createReq.setTitle(title);

                ConversationInfoDto convInfo = conversationDomainService.createConversation(createReq);
                conversationId = convInfo.getId();
                log.info("创建新会话: {}", conversationId);
            }

            // 基于会话的流式对话
            return chatAppService.chatStreamWithConversation(conversationId, streamRequest);
        } else {
            // 单轮对话，不使用会话
            return chatAppService.chatStream(agentName, streamRequest);
        }
    }

    /**
     * 处理流式结构化输出
     * <p>
     * 使用 AgentScope 的流式结构化输出功能，实时发送执行事件和最终结构化数据。
     * </p>
     * <p>
     * 新增功能：
     * <ul>
     * <li>多 Schema 支持：通过 schemaName 参数选择不同的 Schema</li>
     * <li>事件过滤：通过 eventFilter 参数控制接收哪些事件类型</li>
     * <li>取消请求：支持客户端主动取消正在进行的请求</li>
     * <li>更多事件类型：支持 HINT、SUMMARY 等事件</li>
     * </ul>
     * </p>
     */
    private Flux<String> handleStreamStructuredOutput(UnifiedChatRequest request) {
        String agentName = request.getAgentName();

        return Flux.defer(() -> {
            try {
                // 获取 Agent 实例
                ReActAgent agent = agentDomainService.getAgentInstance(agentName);

                // 获取 Schema 类（支持多 Schema 选择）
                Class<?> schemaClass = resolveSchemaClass(agentName, request);
                if (schemaClass == null) {
                    return Flux.just(buildErrorMessage("未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数"));
                }

                // 注册请求（支持取消）
                DistributedRequestManager.RequestInfo requestInfo = requestManager.registerRequestWithToken(
                        agentName,
                        request.getCancelToken() != null ? request.getCancelToken()
                                : requestManager.generateCancelToken());
                String requestId = requestInfo.getRequestId();

                // 构建用户消息
                Msg userMsg = Msg.builder()
                        .textContent(request.getMessage())
                        .role(MsgRole.USER)
                        .build();

                // 解析事件过滤器
                List<String> eventFilter = request.getEventFilter();

                log.info("开始流式结构化输出: Agent={}, Schema={}, requestId={}, eventFilter={}",
                        agentName, schemaClass.getName(), requestId, eventFilter);

                // 开始事件（包含 requestId）
                Flux<String> startFlux = Flux.just(sseMessageBuilder.buildMessageWithRequestId(
                        "start", null, requestId));

                // 调用 Agent 的流式结构化输出
                Flux<String> streamFlux = agent.stream(userMsg,
                        StreamOptions.defaults(),
                        schemaClass)
                        .takeWhile(event -> !requestManager.isRequestCancelled(requestId)) // 检查取消状态
                        .flatMap(event -> {
                            EventType eventType = event.getType();
                            Msg message = event.getMessage();

                            // 事件过滤：如果指定了 eventFilter，只发送在列表中的事件类型
                            if (eventFilter != null && !eventFilter.isEmpty()) {
                                String sseEventType = convertEventTypeToSSE(eventType);
                                if (!eventFilter.contains(sseEventType)) {
                                    return Flux.empty();
                                }
                            }

                            // 将 AgentScope Event 转换为 SSE 消息
                            return processEvent(eventType, message, schemaClass);
                        })
                        .doOnComplete(() -> {
                            log.info("流式结构化输出完成: Agent={}, requestId={}", agentName, requestId);
                            requestManager.unregisterRequest(requestId);
                        })
                        .doOnCancel(() -> {
                            log.info("流式结构化输出被取消: Agent={}, requestId={}", agentName, requestId);
                            requestManager.unregisterRequest(requestId);
                        })
                        .onErrorResume(e -> {
                            log.error("流式结构化输出失败: Agent={}, requestId={}", agentName, requestId, e);
                            requestManager.unregisterRequest(requestId);
                            return Flux.just(sseMessageBuilder.buildErrorMessage(e.getMessage()));
                        });

                // 完成事件
                Flux<String> doneFlux = Flux.just(sseMessageBuilder.buildDoneMessage());

                // 组合所有事件流
                return Flux.concat(startFlux, streamFlux, doneFlux);

            } catch (Exception e) {
                log.error("流式结构化输出初始化失败: Agent={}", agentName, e);
                return Flux.just(buildErrorMessage(e.getMessage()));
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /**
     * 解析 Schema 类（支持多 Schema 选择）
     */
    private Class<?> resolveSchemaClass(String agentName, UnifiedChatRequest request) {
        // 优先级1：动态 JSON Schema
        Map<String, Object> schema = request.getSchema();
        if (schema != null && !schema.isEmpty()) {
            log.info("使用动态 JSON Schema");
            return null; // 动态 Schema 不需要 Class
        }

        // 优先级2：命名 Schema（通过 schemaName 参数）
        String schemaName = request.getSchemaName();
        if (schemaName != null && !schemaName.isBlank()) {
            Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
            if (schemaClass != null) {
                log.info("使用命名 Schema [{}]: {}", schemaName, schemaClass.getName());
                return schemaClass;
            }
            log.warn("未找到命名 Schema [{}], 尝试使用默认 Schema", schemaName);
        }

        // 优先级3：默认 Schema 类
        return schemaClassRegistry.get(agentName);
    }

    /**
     * 处理事件并转换为 SSE 消息
     */
    private Flux<String> processEvent(EventType eventType, Msg message, Class<?> schemaClass) {
        String content = message.getTextContent();
        if (content == null || content.isEmpty()) {
            return Flux.empty();
        }

        switch (eventType) {
            case REASONING:
                // 推理过程 -> thinking 事件
                return Flux.just(sseMessageBuilder.buildThinkingMessage(content));

            case TOOL_RESULT:
                // 工具调用结果 -> content 事件
                return Flux.just(sseMessageBuilder.buildContentMessage(content));

            case HINT:
                // 提示信息（RAG、memory、planning）-> hint 事件
                return Flux.just(sseMessageBuilder.buildMessage("hint", content));

            case SUMMARY:
                // 摘要事件（最大迭代次数到达）-> summary 事件
                return Flux.just(sseMessageBuilder.buildMessage("summary", content));

            case AGENT_RESULT:
                // 最终结果 - 提取结构化数据（结构化输出时使用）
                try {
                    Object structuredData = message.getStructuredData(schemaClass);
                    String jsonStr = sseMessageBuilder.toJsonString(structuredData);
                    return Flux.just(sseMessageBuilder.buildMessage("structured", jsonStr));
                } catch (Exception e) {
                    log.error("提取结构化数据失败", e);
                    return Flux.just(sseMessageBuilder.buildErrorMessage("提取结构化数据失败: " + e.getMessage()));
                }

            default:
                return Flux.empty();
        }
    }

    /**
     * 将 AgentScope EventType 转换为 SSE 事件类型
     */
    private String convertEventTypeToSSE(EventType eventType) {
        switch (eventType) {
            case REASONING:
                return "thinking";
            case TOOL_RESULT:
                return "content";
            case HINT:
                return "hint";
            case SUMMARY:
                return "summary";
            case AGENT_RESULT:
                return "structured";
            default:
                return "unknown";
        }
    }

    /**
     * 构建错误消息（便捷方法）
     */
    private String buildErrorMessage(String error) {
        return sseMessageBuilder.buildErrorMessage(error);
    }

    /**
     * 取消正在进行的流式请求
     *
     * @param cancelToken 取消令牌（在开始事件中返回）
     * @return 取消结果
     */
    @PostMapping("/cancel/{cancelToken}")
    public Map<String, Object> cancelRequest(@PathVariable String cancelToken) {
        boolean success = requestManager.cancelRequest(cancelToken);
        return Map.of(
                "success", success,
                "message", success ? "请求已取消" : "取消失败：请求不存在或已完成");
    }

    /**
     * 获取当前活跃的请求数量
     *
     * @return 活跃请求数量
     */
    @GetMapping("/requests/active-count")
    public Map<String, Object> getActiveRequestCount() {
        return Map.of("count", requestManager.getActiveRequestCount());
    }

    /**
     * 创建会话
     */
    @PostMapping
    public ConversationInfoDto createConversation(@RequestBody CreateConversationRequest request) {
        log.info("创建会话: agentName={}", request.getAgentName());
        return conversationDomainService.createConversation(request);
    }

    /**
     * 获取会话信息
     */
    @GetMapping("/{conversationId}")
    public ConversationInfoDto getConversation(@PathVariable String conversationId) {
        return conversationDomainService.getConversationInfo(conversationId);
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/{conversationId}/messages")
    public List<Object> getConversationMessages(@PathVariable String conversationId) {
        log.info("获取会话消息: conversationId={}", conversationId);
        return conversationDomainService.getConversationMessages(conversationId);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationDomainService.deleteConversation(conversationId);
    }

    /**
     * 基于会话的对话
     */
    @PostMapping("/{conversationId}/chat")
    public ChatResponse chat(
            @PathVariable String conversationId,
            @RequestBody ConversationChatRequest request) {
        log.info("会话对话: conversationId={}, message={}", conversationId, request.getMessage());
        request.setConversationId(conversationId);
        return chatAppService.chatWithConversation(request.getAgentName(), request);
    }

    /**
     * 基于会话的流式对话
     */
    @PostMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(
            @PathVariable String conversationId,
            @RequestBody StreamChatRequest request) {
        log.info("会话流式对话: conversationId={}", conversationId);
        return chatAppService.chatStreamWithConversation(conversationId, request);
    }

    // ========== Agent 中断控制 ==========

    /**
     * 中断 Agent 执行
     *
     * @param name    Agent 名称
     * @param message 用户消息（可选，用于用户介入）
     * @return 中断结果
     */
    @PostMapping("/agent/{name}/interrupt")
    public Map<String, Object> interruptAgent(
            @PathVariable String name,
            @RequestParam(required = false) String message) {
        log.info("中断 Agent: name={}, message={}", name, message);
        AgentInterruptService.InterruptResult result = agentInterruptService.interrupt(name, message);
        return Map.of(
                "success", result.isSuccess(),
                "message", result.getMessage());
    }

    /**
     * 查询 Agent 执行状态
     *
     * @param name Agent 名称
     * @return Agent 状态
     */
    @GetMapping("/agent/{name}/status")
    public Map<String, Object> getAgentStatus(@PathVariable String name) {
        log.info("查询 Agent 状态: name={}", name);
        var status = agentInterruptService.getAgentStatus(name);
        return Map.of(
                "agentName", status.getAgentName(),
                "state", status.getState() != null ? status.getState().name() : "UNKNOWN",
                "message", status.getMessage() != null ? status.getMessage() : "",
                "timestamp", status.getTimestamp() != null ? status.getTimestamp() : 0);
    }

    /**
     * 恢复 Agent（清除中断状态）
     *
     * @param name Agent 名称
     * @return 恢复结果
     */
    @PostMapping("/agent/{name}/resume")
    public Map<String, Object> resumeAgent(@PathVariable String name) {
        log.info("恢复 Agent: name={}", name);
        var result = agentInterruptService.resume(name);
        return Map.of(
                "success", result.isSuccess(),
                "message", result.getMessage());
    }

    // ========== 工具组管理 ==========

    /**
     * 获取可用工具组列表
     */
    @GetMapping("/agent/toolgroups")
    public Map<String, Object> getToolGroups() {
        if (toolGroupManager == null) {
            return Map.of("error", "ToolGroupManager not available");
        }
        var groups = toolGroupManager.getRuntimeGroups();
        return Map.of(
                "groups", groups.values().stream()
                        .map(g -> Map.of(
                                "groupId", g.getGroupId(),
                                "groupName", g.getGroupName(),
                                "active", g.isActive(),
                                "toolNames", g.getToolNames()))
                        .toList());
    }

    /**
     * 动态切换 Agent 的工具组
     *
     * @param name        Agent 名称
     * @param activateIds 要激活的工具组 ID 列表
     * @return 切换结果
     */
    @PostMapping("/agent/{name}/toolgroups")
    public Map<String, Object> updateToolGroups(
            @PathVariable String name,
            @RequestBody List<String> activateIds) {
        log.info("更新 Agent 工具组: name={}, groups={}", name, activateIds);

        if (toolGroupManager == null) {
            return Map.of("success", false, "message", "ToolGroupManager not available");
        }

        // 获取 Agent 实例的 Toolkit
        io.agentscope.core.tool.Toolkit toolkit = agentDomainService.getAgentToolkit(name);
        if (toolkit == null) {
            // 如果 Agent 没有 Toolkit，创建一个新的
            toolkit = new io.agentscope.core.tool.Toolkit();
            log.info("Agent [{}] 无 Toolkit，创建新的工具包", name);
        }

        // 激活指定的工具组
        toolGroupManager.activateGroups(toolkit, activateIds);

        return Map.of(
                "success", true,
                "message", "Tool groups updated successfully",
                "activatedGroups", activateIds);
    }

    // ========== 注入 AgentInterruptService ==========
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentInterruptService agentInterruptService;
}
