package io.yunxi.platform.conversation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.yunxi.agent.rule.core.RuleContext;
import io.yunxi.agent.rule.core.RuleEngine;
import io.yunxi.agent.rule.exception.RuleViolationException;
import io.yunxi.agent.rule.model.RuleResult;
import io.yunxi.platform.agent.plan.PlanPreCreator;
import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.agent.workspace.UserWorkspaceService;
import io.yunxi.platform.file.FileUploadService;
import io.yunxi.platform.file.dto.FileSearchRequest;
import io.yunxi.platform.file.dto.FileSearchResult;
import io.yunxi.platform.prompt.SceneDetectionService;
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

    /** 场景检测服务 */
    private final SceneDetectionService sceneDetectionService;

    /** 文件上传服务 */
    private final FileUploadService fileUploadService;

    /** 安全上下文 */
    private final SecurityContext securityContext;

    /** 规则引擎 */
    private final RuleEngine ruleEngine;

    /** 规划预创建器 */
    private final PlanPreCreator planPreCreator;

    /** 用户工作区懒加载服务 */
    private final UserWorkspaceService userWorkspaceService;

    /**
     * 构造对话应用服务
     *
     * @param agentService              Agent 服务
     * @param properties                AgentScope 配置属性
     * @param conversationDomainService 会话领域服务
     * @param sseMessageBuilder         SSE 消息构建器
     * @param sceneDetectionService     场景检测服务
     * @param fileUploadService         文件上传服务
     * @param contextEnrichers          上下文增强器列表
     * @param securityContext           安全上下文
     * @param ruleEngine                规则引擎
     * @param metricsService            指标服务
     */
    public ChatAppService(AgentService agentService, AgentscopeCoreProperties properties,
            ConversationDomainService conversationDomainService,
            SseMessageBuilder sseMessageBuilder,
            SceneDetectionService sceneDetectionService,
            FileUploadService fileUploadService,
            ObjectProvider<RuleEngine> ruleEngineProvider,
            SecurityContext securityContext,
            ProfileRouter profileRouter,
            PlanPreCreator planPreCreator,
            UserWorkspaceService userWorkspaceService) {
        this.agentService = agentService;
        this.profileRouter = profileRouter;
        this.properties = properties;
        this.conversationDomainService = conversationDomainService;
        this.sseMessageBuilder = sseMessageBuilder;
        this.sceneDetectionService = sceneDetectionService;
        this.fileUploadService = fileUploadService;
        this.securityContext = securityContext;
        this.ruleEngine = ruleEngineProvider.getIfAvailable();
        this.planPreCreator = planPreCreator;
        this.userWorkspaceService = userWorkspaceService;
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
        // 优先：用户工作区隔离（yunxiClaw 个人端）
        if (userId != null && !userId.isBlank() && !"default-user".equals(userId)) {
            return userWorkspaceService.getOrCreateUserAgent(name, userId);
        }
        // 其次：Profile 路由
        if (profile != null && !profile.isBlank()) {
            return profileRouter.resolve(name, profile);
        }
        // 默认：全局 Agent
        return agentService.getAgentInstance(name);
    }

    /**
     * 格式化 Agent 调用异常为用户友好消息
     * <p>
     * 对已知的内部错误进行翻译，避免向用户暴露技术细节。
     * </p>
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
        try {
            // 获取真实 Agent 实例（支持用户工作区隔离）
            String userId = securityContext.getCurrentUserId();
            Agent agent = resolveAgent(name, null, userId);

            String message = request == null ? null : request.getMessage();
            if (message == null || message.isBlank()) {
                throw new BadRequestException("message 不能为空");
            }

            // ==================== 规则引擎集成：构建规则上下文 ====================
            // 从安全上下文获取用户ID（支持请求头 X-User-Id、ThreadLocal 等多种来源）
            // userId 已在上面 resolveAgent 时获取，此处直接复用
            RuleContext ruleContext = buildRuleContext(name, message, null, userId);

            try {
                // ==================== 规则引擎集成：前置规则检查 ====================
                checkPreRules(ruleContext);

                // 构建消息（注入安全规则）
                String messageWithSafety = injectSafetyRules(message);

                Msg userMsg = Msg.builder()
                        .textContent(messageWithSafety)
                        .build();

                // 调用 Agent，设置超时
                Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
                Msg responseMsg = agent.call(userMsg).block(timeout);

                if (responseMsg == null) {
                    throw new RuntimeException("Agent 响应为空");
                }

                String reply = responseMsg.getTextContent();

                // ==================== 规则引擎集成：后置规则验证 ====================
                checkPostRules(ruleContext, reply);

                return new ChatResponse(reply);

            } catch (RuleViolationException e) {
                log.error("规则违反: {}", e.getMessage());
                // 后置规则检查（即使异常也要记录审计日志）
                ruleContext.setErrorMessage(e.getMessage());
                checkPostRules(ruleContext, null);
                throw e;
            } catch (Exception e) {
                log.error("对话执行失败: {}", e.getMessage(), e);
                // 后置规则检查
                ruleContext.setErrorMessage(e.getMessage());
                checkPostRules(ruleContext, null);
                throw new RuntimeException("对话执行失败", e);
            }
        } catch (Exception e) {
            throw e;
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

        // ==================== 规则引擎集成：构建规则上下文 ====================
        // 从会话中获取真实用户ID
        String userId = conversation.getUserId() != null ? conversation.getUserId()
                : securityContext.getCurrentUserId();

        // 获取 Agent 实例（支持用户工作区隔离）
        Agent agent = resolveAgent(conversation.getAgentName(), null, userId);
        RuleContext ruleContext = buildRuleContext(name, request.getMessage(), request.getConversationId(), userId);

        try {
            // ==================== 规则引擎集成：前置规则检查 ====================
            checkPreRules(ruleContext);

            // 构建用户消息
            Msg userMsg = Msg.builder()
                    .textContent(request.getMessage())
                    .build();

            // 获取记忆配置
            var memoryConfig = request.getMemoryConfig();
            Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
            Msg responseMsg;

            // 检测场景
            String sceneName = sceneDetectionService.detectScene(request.getMessage()).sceneName();
            log.debug("检测到场景: {}", sceneName);

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
                responseMsg = agent.call(userMsg).block(timeout);
            } else {
                // 使用智能记忆系统
                try {
                    // 获取当前会话的所有历史消息
                    List<Msg> historyMessages = conversation.getMessages();
                    log.info("同步对话 - 历史消息数量: {}, conversationId={}",
                            historyMessages.size(), request.getConversationId());

                    // 添加用户消息到记忆（由 HarnessAgent 内部 MemoryFlushHook 自动处理）
                    // MemoryCoordinatorService.addMessages 已移除 — 改为直接使用 Msg 列表
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
                    responseMsg = agent.call(contextMessages).block(timeout);

                    // 添加助手回复到记忆（由 HarnessAgent 内部 MemoryFlushHook 自动处理）
                    // MemoryCoordinatorService.addMessages 已移除

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

                    responseMsg = agent.call(allMessages).block(timeout);
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

            // ==================== 规则引擎集成：后置规则验证 ====================
            checkPostRules(ruleContext, reply);

            ChatResponse response = new ChatResponse(reply);
            response.setConversationId(request.getConversationId());
            return response;

        } catch (RuleViolationException e) {
            log.error("规则违反: {}", e.getMessage());
            // 后置规则检查（即使异常也要记录审计日志）
            ruleContext.setErrorMessage(e.getMessage());
            checkPostRules(ruleContext, null);
            throw e;
        } catch (Exception e) {
            log.error("对话执行失败: {}", e.getMessage(), e);
            // 后置规则检查
            ruleContext.setErrorMessage(e.getMessage());
            checkPostRules(ruleContext, null);
            throw new RuntimeException("对话执行失败", e);
        }
    }

    public Flux<String> chatStream(String name, StreamChatRequest request) {
        log.info("开始流式对话: Agent={}, Message={}, responseMode={}", name, request.getMessage(),
                request.getResponseMode());

        // ==================== 规则引擎集成：构建规则上下文 ====================
        // 从安全上下文获取用户ID（支持请求头 X-User-Id、ThreadLocal 等多种来源）
        String userId = securityContext.getCurrentUserId();
        RuleContext ruleContext = buildRuleContext(name, request.getMessage(), null, userId);

        return Flux.defer(() -> {
            try {
                // ==================== 规则引擎集成：前置规则检查 ====================
                checkPreRules(ruleContext);

                // 获取 Agent 实例（支持用户工作区隔离和 Profile 路由）
                Agent agent = resolveAgent(name, request.getProfile(), userId);

                // 构建用户消息（含上下文数据）
                Msg userMsg = buildUserMessage(request.getMessage(), request.getContextData());

                // ==== 配置驱动的规划预创建 ====
                var planResult = planPreCreator.preCreateIfConfigured(
                        request.getMessage(), name, agent);
                if (planResult == PlanPreCreator.PreCreateResult.PRE_CREATED) {
                    return buildPlanConfirmationStream(agent, request.getMessage())
                            .doOnComplete(() -> log.info("规划待确认: agent={}", name));
                }

                // 构建响应式流（无会话，不使用记忆）
                return buildStreamResponse(agent, userMsg, request, null, null, null, null, null)
                        .doOnNext(chunk -> {
                            // 流式输出的每个 chunk 可以在这里处理
                            log.trace("流式输出chunk: {}", chunk);
                        })
                        .doOnComplete(() -> {
                            // ==================== 规则引擎集成：后置规则验证（流式完成） ====================
                            checkPostRules(ruleContext, "stream-completed");
                        })
                        .doOnError(error -> {
                            // ==================== 规则引擎集成：后置规则验证（异常情况） ====================
                            log.error("流式对话失败: {}", error.getMessage());
                            ruleContext.setErrorMessage(error.getMessage());
                            checkPostRules(ruleContext, null);
                        });

            } catch (RuleViolationException e) {
                log.error("规则违反: {}", e.getMessage());
                // 后置规则检查（即使异常也要记录审计日志）
                ruleContext.setErrorMessage(e.getMessage());
                checkPostRules(ruleContext, null);
                return Flux.just(sseMessageBuilder.buildErrorMessage("规则违反: " + e.getMessage()));
            } catch (Exception e) {
                log.error("流式对话失败: Agent={}", name, e);
                // 后置规则检查
                ruleContext.setErrorMessage(e.getMessage());
                checkPostRules(ruleContext, null);
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
     * 转义 JSON 字符串中的特殊字符
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 格式化上下文数据为可读文本
     *
     * <p>
     * 处理通用 key（configSummary、formData、pageType），
     * 业务上下文通过工作区 knowledge/ 文件自动注入。
     * </p>
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

                // ==== 配置驱动的规划预创建 ====
                var planResult = planPreCreator.preCreateIfConfigured(
                        request.getMessage(), conversation.getAgentName(), agent);
                if (planResult == PlanPreCreator.PreCreateResult.PRE_CREATED) {
                    return buildPlanConfirmationStream(agent, request.getMessage())
                            .doOnComplete(() -> log.info("规划待确认: conversationId={}", conversationId));
                }

                // ---- 快速模式：跳过 RAG、记忆、场景检测，直接调用 Agent ----
                if (request.isQuickMode()) {
                    log.info("快速模式: ConversationId={}, 跳过 RAG/记忆/场景检测", conversationId);
                    List<Msg> quickMessages = new ArrayList<>();
                    quickMessages.add(userMsg);
                    conversation.addMessage(userMsg);

                    return buildStreamResponse(agent, quickMessages, request, conversation, null,
                            conversationId, null, null)
                            .doOnComplete(() -> log.info("快速模式对话完成: ConversationId={}", conversationId));
                }

                // ---- 标准模式 / 深度模式 ----

                // 检测场景
                String sceneName = sceneDetectionService.detectScene(request.getMessage()).sceneName();
                log.debug("检测到场景: {}", sceneName);

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
                        List<Msg> userMessages = List.of(userMsg);

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

                // 思考事件文本（深度模式/A2A 模式显示更详细的提示）
                String thinkingText;
                boolean useA2A = request.isUseA2A() || request.isDeepMode();
                if (useA2A) {
                    thinkingText = String.format("A2A 协作模式 - 主智能体正在分析 %d 条消息，将协调专家智能体处理..", allMessages.size());
                } else if (request.isEnableThinking()) {
                    thinkingText = String.format("正在分析 %d 条消息..", allMessages.size());
                } else {
                    thinkingText = null;
                }

                // 构建响应式流并保存消息
                return buildStreamResponse(agent, allMessages, request, conversation, thinkingText,
                        conversationId, memoryConfig, sceneName)
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
     * 构建规划确认 SSE 事件流。
     * <p>
     * 当 PlanPreCreator 预创建了规划时，发送 type=plan 事件给前端，
     * 前端展示规划确认卡片，等待用户确认/修改/跳过。
     * 此时不调用 agent.call()，不启动 Agent 执行。
     * </p>
     */
    private Flux<String> buildPlanConfirmationStream(Agent agent, String goal) {
        // PlanNotebook 由 HarnessAgent 内部管理
        // Plan 确认流程通过 PlanInteractionController 处理
        log.debug("Plan confirmation for goal: {} (handled via PlanInteractionController)", goal);
        return Flux.just(sseMessageBuilder.buildMessage("plan_ready", "Plan created"));
    }

    private Flux<String> buildStreamResponse(Agent agent,
            Object inputMsg,
            StreamChatRequest request,
            ConversationEntity conversation,
            String thinkingText,
            String conversationId,
            MemoryConfig memoryConfig,
            String sceneName) {
        // 1. 开始事件（如果有会话ID，包含在开始消息中）
        Flux<String> startFlux = Flux.just(
                conversationId != null ? sseMessageBuilder.buildStartMessageWithConversationId(conversationId)
                        : sseMessageBuilder.buildStartMessage());

        // 2. 思考事件（如果启用）
        Flux<String> thinkingFlux = thinkingText != null
                ? Flux.just(sseMessageBuilder.buildThinkingMessage(thinkingText))
                : Flux.empty();

        // 3. 调用 Agent 获取响应（深度/A2A 模式使用更长超时）
        boolean useA2A = request.isUseA2A() || request.isDeepMode();
        int timeoutSeconds = useA2A
                ? properties.getChatTimeoutSeconds() * 3 // 深度/A2A 模式 3 倍超时
                : properties.getChatTimeoutSeconds();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);

        if (useA2A) {
            log.info("深度/A2A 协作模式已启用，超时时间: {}s", timeoutSeconds);
        }

        // 4. A2A 进度心跳流（让用户知道系统在工作）
        Flux<String> heartbeatFlux = useA2A
                ? Flux.interval(Duration.ofSeconds(8))
                        .map(seq -> sseMessageBuilder.buildThinkingMessage(
                                "协作处理中... 主智能体正在与专家智能体沟通 (" + (seq + 1) + ")"))
                        .take(20) // 最多 20 条心跳（160 秒）
                : Flux.empty();

        // 5. 使用 agent.streamEvents() 获取流式事件（替代已废弃的 stream()）
        // V2.0-RC3: HarnessAgent 自身提供 streamEvents()，不再继承 ReActAgent
        io.agentscope.harness.agent.HarnessAgent harnessAgent = (io.agentscope.harness.agent.HarnessAgent) agent;
        Flux<io.agentscope.core.event.AgentEvent> eventFlux;
        if (inputMsg instanceof List) {
            @SuppressWarnings("unchecked")
            List<Msg> messages = (List<Msg>) inputMsg;
            eventFlux = harnessAgent.streamEvents(messages);
        } else {
            eventFlux = harnessAgent.streamEvents((Msg) inputMsg);
        }

        // 积累推理文本，最终存入 Msg metadata
        StringBuilder thinkingAccumulator = new StringBuilder();

        // 6. 将 AgentEvent 流转换为 SSE 事件流
        Flux<String> contentFlux = eventFlux
                .timeout(timeout)
                .flatMapSequential(event -> {
                    if (event.getType() == io.agentscope.core.event.AgentEventType.THINKING_BLOCK_DELTA) {
                        String text = event instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent
                                ? ((io.agentscope.core.event.ThinkingBlockDeltaEvent) event).getDelta()
                                : null;
                        if (text != null && !text.isEmpty()) {
                            thinkingAccumulator.append(text);
                            return Flux.just(sseMessageBuilder.buildThinkingMessage(text));
                        }
                        return Flux.empty();
                    }
                    if (event.getType() == io.agentscope.core.event.AgentEventType.AGENT_RESULT) {
                        if (event instanceof io.agentscope.core.event.AgentResultEvent resultEvent) {
                            // 保存完整响应到会话（附带推理内容）
                            Msg resultMsg = resultEvent.getResult();
                            String reasoningText = thinkingAccumulator.toString();
                            if (!reasoningText.isEmpty()) {
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
                                // 切块发送（前端分块显示）
                                int chunkSize = 200;
                                return Flux.fromStream(
                                        splitTextIntoChunks(text, chunkSize).stream())
                                        .map(chunk -> sseMessageBuilder.buildContentMessage(chunk));
                            }
                        }
                        return Flux.empty();
                    }
                    if (event.getType() == io.agentscope.core.event.AgentEventType.TEXT_BLOCK_DELTA) {
                        String delta = event instanceof io.agentscope.core.event.TextBlockDeltaEvent
                                ? ((io.agentscope.core.event.TextBlockDeltaEvent) event).getDelta()
                                : null;
                        if (delta != null && !delta.isEmpty()) {
                            return Flux.just(sseMessageBuilder.buildContentMessage(delta));
                        }
                        return Flux.empty();
                    }
                    return Flux.empty();
                })
                .onErrorResume(e -> {
                    log.error("Agent 推理异常: {}", e.getMessage(), e);
                    return Flux.just(sseMessageBuilder.buildErrorMessage(formatAgentError(e)));
                });

        // 组合所有事件流（A2A 模式下心跳流和内容流并发，内容返回后心跳自动停止）
        if (useA2A) {
            // 使用 takeUntilOther：当 contentFlux 发出第一个元素时，heartbeatFlux 自动停止
            Flux<String> activeHeartbeat = heartbeatFlux.takeUntilOther(contentFlux.filter(s -> true).next());
            return Flux.concat(startFlux, thinkingFlux, activeHeartbeat, contentFlux);
        } else {
            return Flux.concat(startFlux, thinkingFlux, contentFlux);
        }
    }

    /** 将文本按指定大小切块 */
    private static List<String> splitTextIntoChunks(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
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

    // ==================== 规则引擎集成 ====================

    /**
     * 从安全上下文中提取用户ID
     *
     * <p>
     * <b>已废弃</b>：请使用 {@link SecurityContext#getCurrentUserId()} 代替。
     * </p>
     *
     * <p>
     * 用户信息来源优先级：
     * <ol>
     * <li>ThreadLocal（最高优先级，用于异步任务）</li>
     * <li>请求属性（由拦截器设置）</li>
     * <li>请求头 X-User-Id（当前主要方式）</li>
     * <li>默认值 "anonymous-user"</li>
     * </ol>
     * </p>
     *
     * /**
     * 构建规则上下文
     *
     * @param agentName      Agent 名称
     * @param message        用户消息
     * @param conversationId 会话ID（可选）
     * @param userId         用户ID（可选）
     * @return 规则上下文
     */
    private RuleContext buildRuleContext(String agentName, String message, String conversationId, String userId) {
        return RuleContext.builder()
                .agentId(agentName)
                .userInfo(RuleContext.UserInfo.builder()
                        .userId(userId != null ? userId : "anonymous-user")
                        .username(userId != null ? userId : "anonymous-user")
                        .roles(List.of("user"))
                        .permissions(List.of("chat"))
                        .build())
                .taskInfo(RuleContext.TaskInfo.builder()
                        .taskId(conversationId != null ? conversationId : "chat-" + System.currentTimeMillis())
                        .taskType("chat")
                        .skillName(agentName)
                        .inputs(Map.of("message", message))
                        .startTime(System.currentTimeMillis())
                        .timeout((long) properties.getChatTimeoutSeconds())
                        .build())
                .params(Map.of(
                        "agentName", agentName,
                        "conversationId", conversationId != null ? conversationId : ""))
                .build();
    }

    /**
     * 执行前置规则检查
     *
     * @param context 规则上下文
     * @throws RuleViolationException 如果规则检查失败
     */
    private void checkPreRules(RuleContext context) {
        if (ruleEngine == null) {
            log.debug("规则引擎未启用，跳过前置规则检查");
            return;
        }

        log.info("执行前置规则检查: agentId={}, taskId={}",
                context.getAgentId(), context.getTaskInfo().getTaskId());

        RuleResult result = ruleEngine.executeRules(io.yunxi.agent.rule.model.RuleType.PRE,
                new org.jeasy.rules.api.Facts(), context);
        if (!result.isPassed()) {
            log.warn("前置规则检查失败: {}", result.getErrorMessage());
            throw new RuleViolationException(result.getErrorMessage());
        }

        log.info("前置规则检查通过");
    }

    /**
     * 执行后置规则验证
     *
     * @param context 规则上下文
     * @param result  执行结果
     */
    private void checkPostRules(RuleContext context, Object result) {
        if (ruleEngine == null) {
            log.debug("规则引擎未启用，跳过后置规则验证");
            return;
        }

        log.info("执行后置规则验证: agentId={}, taskId={}",
                context.getAgentId(), context.getTaskInfo().getTaskId());

        context.setResult(result);
        RuleResult postResult = ruleEngine.checkPostRules(context);

        if (!postResult.isPassed()) {
            // 后置规则失败通常不影响结果，仅记录日志
            log.warn("后置规则检查失败: {}", postResult.getErrorMessage());
        } else {
            log.info("后置规则验证通过");
        }
    }

    /**
     * 注入用户安全规则到消息中
     *
     * <p>
     * 统一记忆管理器已在重构中移除，安全规则注入暂不提供。
     * </p>
     */
    private String injectSafetyRules(String message) {
        return message;
    }
}
