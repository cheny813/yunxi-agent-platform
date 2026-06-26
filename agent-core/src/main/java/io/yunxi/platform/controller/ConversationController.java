package io.yunxi.platform.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.yunxi.platform.agent.AgentInterruptService;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.conversation.ChatAppService;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.conversation.DistributedRequestManager;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.ConversationChatRequest;
import io.yunxi.platform.shared.dto.ConversationInfoDto;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.util.SseMessageBuilder;
import io.yunxi.platform.structured.SchemaClassRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 会话控制器
 * <p>
 * 提供会话、聊天、差异化聊天、管理等功能 API
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    /** 会话消费服务 - 调用 Agent 进行对话、管理对话内容、处理 RAG 缓存 */
    private final ChatAppService chatAppService;

    /** 会话领域服务 - 会话 CRUD，三级查询（Local -> Redis -> DB） */
    private final ConversationDomainService conversationDomainService;

    /** Schema 注册表 - 差异化聊天时根据 agentName 查找 Schema Class */
    private final SchemaClassRegistry schemaClassRegistry;

    /** Agent 领域服务 - 获取 Agent 实例 */
    private final AgentService agentDomainService;

    /** SSE 消息构建器 - 构建标准 SSE 事件格式（start/thinking/content/done） */
    private final SseMessageBuilder sseMessageBuilder;

    /** 分布式请求管理器 - 根据请求令牌管理请求 */
    private final DistributedRequestManager requestManager;

    /** JSON 序列化工具 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Agent 中断服务（可选，用于中断/恢复 Agent 执行） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AgentInterruptService agentInterruptService;

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
     * 通用会话入口（根据情况创建新会话或使用已有会话）
     *
     * @param request 会话请求
     * @return 会话结果
     */
    @PostMapping("/chat")
    public Object chat(@RequestBody UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }
        // 根据模式选择处理器
        if (request.isStreamMode()) {
            throw new UnsupportedOperationException("请使用 /api/conversations/chat/stream 入口进行流式聊天");
        }
        // 差异化输出处理
        if (request.isStructuredOutput()) {
            return handleStructuredOutput(request);
        }
        // 普通对话聊天
        String conversationId = request.getConversationId();
        boolean needsConversation = (conversationId != null && !conversationId.isBlank()) ||
                Boolean.TRUE.equals(request.getAutoManageConversation());
        if (needsConversation) {
            // 如果已有 conversationId 则使用，否则创建新会话
            if (conversationId == null || conversationId.isBlank()) {
                // 创建新会话
                CreateConversationRequest createReq = new CreateConversationRequest();
                createReq.setAgentName(agentName);
                if (request.getUserId() != null) {
                    createReq.setUserId(request.getUserId());
                }
                // 使用用户消息前20个字符作为标题
                String title = request.getMessage().substring(0, Math.min(20, request.getMessage().length()));
                createReq.setTitle(title);
                ConversationInfoDto convInfo = conversationDomainService.findOrCreateConversation(createReq);
                conversationId = convInfo.getId();
                log.info("创建/复用会话: {}", conversationId);
            }
            // 追加会话聊天
            ConversationChatRequest chatRequest = new ConversationChatRequest();
            chatRequest.setMessage(request.getMessage());
            chatRequest.setConversationId(conversationId);
            chatRequest.setAgentName(agentName);
            return chatAppService.chatWithConversation(agentName, chatRequest);
        } else {
            // 独立会话，不使用会话管理
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setMessage(request.getMessage());
            chatRequest.setEnableThinking(request.getEnableThinking());
            return chatAppService.chat(agentName, chatRequest);
        }
    }

    /**
     * 处理差异化输出
     */
    private Object handleStructuredOutput(UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        // 获取 Agent 实例
        Agent agent = agentDomainService.getAgentInstance(agentName);
        // 构造用户消息
        Msg userMsg = Msg.builder()
                .textContent(request.getMessage())
                .role(MsgRole.USER)
                .build();
        try {
            Duration timeout = Duration.ofSeconds(300); // 5分钟超时
            // 路径1：使用内联 JSON Schema（最优先）
            Map<String, Object> schema = request.getSchema();
            if (schema != null && !schema.isEmpty()) {
                log.info("使用内联 JSON Schema 执行差异化输出");
                JsonNode schemaNode = objectMapper.valueToTree(schema);
                Msg response = agent.call(userMsg, schemaNode).block(timeout);
                return response.getStructuredData(false);
            }
            // 路径2：使用命名 Schema（通过 schemaName 参数）
            String schemaName = request.getSchemaName();
            if (schemaName != null && !schemaName.isBlank()) {
                Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
                if (schemaClass != null) {
                    log.info("使用命名 Schema [{}] 执行差异化输出: {}", schemaName, schemaClass.getName());
                    Msg response = agent.call(userMsg, schemaClass).block(timeout);
                    return response.getStructuredData(schemaClass);
                }
                log.warn("未找到命名 Schema [{}]，尝试使用默认 Schema", schemaName);
            }
            // 路径3：使用默认 Schema 表
            Class<?> schemaClass = schemaClassRegistry.get(agentName);
            if (schemaClass != null) {
                log.info("使用默认 Schema 表执行差异化输出: {}", schemaClass.getName());
                Msg response = agent.call(userMsg, schemaClass).block(timeout);
                return response.getStructuredData(schemaClass);
            }
            // 路径4：未配置 Schema
            throw new BadRequestException("未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数");
        } catch (Exception e) {
            log.error("差异化输出异常 {}", e.getMessage(), e);
            throw new RuntimeException("差异化输出异常: " + e.getMessage(), e);
        }
    }

    /**
     * 通用流式聊天入口（根据情况创建新会话或使用已有会话）
     *
     * @param request 会话请求
     * @return 流式响应
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName 不能为空");
        }
        // 流式差异化输出处理
        if (request.isStructuredOutput()) {
            return handleStreamStructuredOutput(request);
        }
        // 普通流式聊天
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
        // 支持 A2A 调用模式
        streamRequest.setUseA2A(Boolean.TRUE.equals(request.getEnableA2A()));
        // 支持响应模式选择
        if (request.getResponseMode() != null) {
            streamRequest.setResponseMode(request.getResponseMode());
        }
        // 支持 Profile 选择
        if (request.getProfile() != null) {
            streamRequest.setProfile(request.getProfile());
        }
        // 支持上下文注入
        if (request.getContextData() != null) {
            streamRequest.setContextData(request.getContextData());
        }
        // 判断是否需要使用会话
        String conversationId = request.getConversationId();
        boolean needsConversation = (conversationId != null && !conversationId.isBlank()) ||
                Boolean.TRUE.equals(request.getAutoManageConversation());
        if (needsConversation) {
            // 如果已有 conversationId 则使用，否则创建新会话
            if (conversationId == null || conversationId.isBlank()) {
                // 创建新会话
                CreateConversationRequest createReq = new CreateConversationRequest();
                createReq.setAgentName(agentName);
                if (request.getUserId() != null) {
                    createReq.setUserId(request.getUserId());
                }
                // 使用用户消息前20个字符作为标题
                String title = request.getMessage().substring(0, Math.min(20, request.getMessage().length()));
                createReq.setTitle(title);
                ConversationInfoDto convInfo = conversationDomainService.findOrCreateConversation(createReq);
                conversationId = convInfo.getId();
                log.info("创建/复用会话: {}", conversationId);
            }
            // 追加会话的流式聊天
            return chatAppService.chatStreamWithConversation(conversationId, streamRequest);
        } else {
            // 独立会话，不使用会话管理
            return chatAppService.chatStream(agentName, streamRequest);
        }
    }

    /**
     * 处理流式差异化输出
     * <p>
     * 使用 AgentScope 的流式差异化输出接口，实时发送执行事件和最终差异化数据。
     * </p>
     */
    private Flux<String> handleStreamStructuredOutput(UnifiedChatRequest request) {
        String agentName = request.getAgentName();
        return Flux.defer(() -> {
            try {
                // 获取 Agent 实例
                Agent agent = agentDomainService.getAgentInstance(agentName);
                // 获取 Schema 表（根据优先级选择）
                Class<?> schemaClass = resolveSchemaClass(agentName, request);
                if (schemaClass == null) {
                    return Flux.just(buildErrorMessage("未配置 Schema，请在 Agent 配置中设置 schema_class 或在请求中提供 schema 参数"));
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
                // 事件过滤列表
                List<String> eventFilter = request.getEventFilter();
                log.info("开始流式差异化输出: Agent={}, Schema={}, requestId={}, eventFilter={}",
                        agentName, schemaClass.getName(), requestId, eventFilter);
                // 开始事件（包含 requestId）
                Flux<String> startFlux = Flux.just(sseMessageBuilder.buildMessageWithRequestId(
                        "start", null, requestId));
                // 调用 Agent 的流式差异化输出（streamEvents 替代已废弃的 stream）
                // V2.0-RC3: HarnessAgent 自身提供 streamEvents()，不再继承 ReActAgent
                Flux<String> streamFlux = ((io.agentscope.harness.agent.HarnessAgent) agent).streamEvents(List.of(userMsg))
                        .takeWhile(event -> !requestManager.isRequestCancelled(requestId))
                        .flatMap(event -> {
                            // 将 AgentEvent 转换为 SSE 消息
                            return processAgentEvent(event, schemaClass);
                        })
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
                            return Flux.just(sseMessageBuilder.buildErrorMessage(e.getMessage()));
                        });
                // 完成事件
                Flux<String> doneFlux = Flux.just(sseMessageBuilder.buildDoneMessage());
                // 合并所有事件
                return Flux.concat(startFlux, streamFlux, doneFlux);
            } catch (Exception e) {
                log.error("流式差异化输出初始化异常: Agent={}", agentName, e);
                return Flux.just(buildErrorMessage(e.getMessage()));
            }
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    /** 解析 Schema 表（根据优先级选择） */
    private Class<?> resolveSchemaClass(String agentName, UnifiedChatRequest request) {
        // 优先检查：内联 JSON Schema
        Map<String, Object> schema = request.getSchema();
        if (schema != null && !schema.isEmpty()) {
            log.info("使用内联 JSON Schema");
            return null;
        }
        // 其次检查：命名 Schema（通过 schemaName 参数）
        String schemaName = request.getSchemaName();
        if (schemaName != null && !schemaName.isBlank()) {
            Class<?> schemaClass = schemaClassRegistry.getSchema(agentName, schemaName);
            if (schemaClass != null) {
                log.info("使用命名 Schema [{}]: {}", schemaName, schemaClass.getName());
                return schemaClass;
            }
            log.warn("未找到命名 Schema [{}]，尝试使用默认 Schema", schemaName);
        }
        // 最后检查：默认 Schema 表
        return schemaClassRegistry.get(agentName);
    }

    /** 处理 AgentEvent，将其转换为 SSE 消息 */
    private Flux<String> processAgentEvent(AgentEvent event, Class<?> schemaClass) {
        var type = event.getType();
        if (type == AgentEventType.THINKING_BLOCK_DELTA && event instanceof ThinkingBlockDeltaEvent tde) {
            String text = tde.getDelta();
            if (text != null && !text.isEmpty())
                return Flux.just(sseMessageBuilder.buildThinkingMessage(text));
            return Flux.empty();
        }
        if (type == AgentEventType.AGENT_RESULT && event instanceof AgentResultEvent are) {
            Msg message = are.getResult();
            try {
                Object data = message.getStructuredData(schemaClass);
                return Flux.just(sseMessageBuilder.buildMessage("structured",
                        sseMessageBuilder.toJsonString(data)));
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

    /** 构造错误消息 */
    private String buildErrorMessage(String error) {
        return sseMessageBuilder.buildErrorMessage(error);
    }

    /** 取消正在执行的任务 */
    @PostMapping("/cancel/{cancelToken}")
    public Map<String, Object> cancelRequest(@PathVariable String cancelToken) {
        boolean success = requestManager.cancelRequest(cancelToken);
        return Map.of("success", success, "message", success ? "请求已取消" : "取消失败：请求不存在或已完成");
    }

    /** 获取当前活跃的请求数量 */
    @GetMapping("/requests/active-count")
    public Map<String, Object> getActiveRequestCount() {
        return Map.of("count", requestManager.getActiveRequestCount());
    }

    /** 创建会话 */
    @PostMapping
    public ConversationInfoDto createConversation(@RequestBody CreateConversationRequest request) {
        log.info("创建会话: agentName={}", request.getAgentName());
        return conversationDomainService.createConversation(request);
    }

    /** 获取会话信息 */
    @GetMapping("/{conversationId}")
    public ConversationInfoDto getConversation(@PathVariable String conversationId) {
        return conversationDomainService.getConversationInfo(conversationId);
    }

    /** 获取会话消息列表 */
    @GetMapping("/{conversationId}/messages")
    public List<Object> getConversationMessages(@PathVariable String conversationId) {
        log.info("获取会话消息: conversationId={}", conversationId);
        return conversationDomainService.getConversationMessages(conversationId);
    }

    /** 删除会话 */
    @DeleteMapping("/{conversationId}")
    public void deleteConversation(@PathVariable String conversationId) {
        conversationDomainService.deleteConversation(conversationId);
    }

    /** 追加会话聊天 */
    @PostMapping("/{conversationId}/chat")
    public ChatResponse chat(@PathVariable String conversationId, @RequestBody ConversationChatRequest request) {
        log.info("会话聊天: conversationId={}, message={}", conversationId, request.getMessage());
        request.setConversationId(conversationId);
        return chatAppService.chatWithConversation(request.getAgentName(), request);
    }

    /** 追加会话流式聊天 */
    @PostMapping(value = "/{conversationId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@PathVariable String conversationId, @RequestBody StreamChatRequest request) {
        log.info("会话流式聊天: conversationId={}", conversationId);
        return chatAppService.chatStreamWithConversation(conversationId, request);
    }

    // ========== Agent 中断控制 ==========

    /** 中断 Agent 执行 */
    @PostMapping("/agent/{name}/interrupt")
    public Map<String, Object> interruptAgent(@PathVariable String name,
            @RequestParam(required = false) String message) {
        log.info("中断 Agent: name={}, message={}", name, message);
        AgentInterruptService.InterruptResult result = agentInterruptService.interrupt(name, message);
        return Map.of("success", result.isSuccess(), "message", result.getMessage());
    }

    /** 查询 Agent 执行状态 */
    @GetMapping("/agent/{name}/status")
    public Map<String, Object> getAgentStatus(@PathVariable String name) {
        log.info("查询 Agent 状态: name={}", name);
        var status = agentInterruptService.getAgentStatus(name);
        return Map.of("agentName", status.getAgentName(),
                "state", status.getState() != null ? status.getState().name() : "UNKNOWN",
                "message", status.getMessage() != null ? status.getMessage() : "",
                "timestamp", status.getTimestamp() != null ? status.getTimestamp() : 0);
    }

    /** 恢复 Agent（清除中断状态） */
    @PostMapping("/agent/{name}/resume")
    public Map<String, Object> resumeAgent(@PathVariable String name) {
        log.info("恢复 Agent: name={}", name);
        var result = agentInterruptService.resume(name);
        return Map.of("success", result.isSuccess(), "message", result.getMessage());
    }

    // ========== 工具组控制 ==========

    /** 获取可用工具组列表 */
    @GetMapping("/agent/toolgroups")
    public Map<String, Object> getToolGroups() {
        return Map.of("groups", List.of());
    }

    /** 更新 Agent 的工具组 */
    @PostMapping("/agent/{name}/toolgroups")
    public Map<String, Object> updateToolGroups(@PathVariable String name, @RequestBody List<String> activateIds) {
        log.info("更新 Agent 工具组: name={}, groups={}", name, activateIds);
        return Map.of("success", true, "message", "Tool groups updated successfully", "activatedGroups", activateIds);
    }
}
