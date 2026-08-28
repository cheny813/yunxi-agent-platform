package io.yunxi.platform.controller;

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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.conversation.ChatAppService;
import io.yunxi.platform.conversation.ConversationDomainService;
import io.yunxi.platform.conversation.DistributedRequestManager;
import io.yunxi.platform.execution.AgentPhase;
import io.yunxi.platform.execution.operator.PhaseTracker;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.dto.ConversationChatRequest;
import io.yunxi.platform.shared.dto.ConversationInfoDto;
import io.yunxi.platform.shared.dto.CreateConversationRequest;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import io.yunxi.platform.shared.dto.UnifiedChatRequest;
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

    /** Agent 领域服务 - 获取 Agent 实例 */
    private final AgentService agentDomainService;

    /** 分布式请求管理器 - 根据请求令牌管理请求 */
    private final DistributedRequestManager requestManager;

    /** 阶段追踪算子 - 提供最近一次执行的阶段轨迹（agent_status 状态视图） */
    private final PhaseTracker phaseTracker;

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
        // 差异化输出处理（经执行引擎的拦截器链 + StructuredBlockingStrategy）
        if (request.isStructuredOutput()) {
            return chatAppService.chatStructured(agentName, request);
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
        // 流式差异化输出处理（门面：双模式/取消令牌的 SSE 专属编排）
        if (request.isStructuredOutput()) {
            return chatAppService.chatStreamStructured(agentName, request);
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
        // 中断执行：有伴随消息则注入用户消息，否则仅发送中断信号。
        // 框架在本次迭代结束后自动消费中断，无需显式恢复（详见 resumeAgent）。
        Agent agent = agentDomainService.getAgentInstance(name);
        if (message != null && !message.isBlank()) {
            agent.interrupt(Msg.builder().textContent(message).build());
        } else {
            agent.interrupt();
        }
        return Map.of("success", true, "message", "Agent 已中断");
    }

    /** 查询 Agent 执行状态（含最近一次执行的阶段轨迹） */
    @GetMapping("/agent/{name}/status")
    public Map<String, Object> getAgentStatus(@PathVariable String name) {
        log.info("查询 Agent 状态: name={}", name);
        // interrupt 为一次性信号，由框架在本次迭代结束后自动消费，无独立的持久化状态机。
        // 状态由 PhaseTracker 事件推导（IDLE→THINKING→TOOL_CALL→ANSWER→DONE）提供，
        // 返回最近一次执行的阶段轨迹供前端状态视图渲染。
        Agent agent = agentDomainService.findAgent(name);
        PhaseTracker.PhaseTrace lastTrace = phaseTracker.getLastPhaseTrace(name);
        String state;
        if (agent == null) {
            state = "NOT_FOUND";
        } else if (lastTrace != null && !lastTrace.trace().isEmpty()) {
            state = lastTrace.trace().get(lastTrace.trace().size() - 1);
        } else {
            state = AgentPhase.IDLE.name();
        }
        return Map.of("agentName", name, "state", state,
                "message", agent != null ? "" : "Agent 未找到",
                "timestamp", lastTrace != null ? lastTrace.timestamp() : 0L,
                "phaseTrace", lastTrace != null ? lastTrace.trace() : List.of());
    }

    /** 恢复 Agent（清除中断状态） */
    @PostMapping("/agent/{name}/resume")
    public Map<String, Object> resumeAgent(@PathVariable String name) {
        log.info("恢复 Agent: name={}", name);
        // interrupt 为一次性暂停信号，下一次调用即自动恢复，
        // 框架无独立的 resume API，故此处直接返回成功。
        return Map.of("success", true, "message", "中断信号将在下次调用时自动消费，无需显式恢复");
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
