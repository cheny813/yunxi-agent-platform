package io.yunxi.platform.framework.pageagent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.yunxi.platform.framework.embedding.ChatModelProvider;
import io.yunxi.platform.framework.embedding.ModelConfig;
import io.yunxi.platform.framework.embedding.ModelProviderFactory;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Page Agent 服务（框架层通用服务）
 * <p>
 * 封装 Page Agent 核心能力，提供给业务层简单调用的 API。
 * 不依赖具体业务逻辑，任何需要页面自动化的地方都可以使用。
 * </p>
 *
 * <h3>架构说明</h3>
 * <ul>
 * <li>前端：page-agent SDK 负责 UI 渲染、DOM 提取、ReAct 循环、操作执行</li>
 * <li>后端：PageAgentService 负责 LLM 代理转发（OpenAI 兼容格式），API Key 服务端保管</li>
 * <li>保护：API Key 等敏感信息仅在后端使用，不暴露给前端</li>
 * </ul>
 *
 * <h3>能力清单</h3>
 * <ul>
 * <li>DOM 操作：点击、输入、选择、滚动</li>
 * <li>页面理解：DOM 树提取、元素索引定位</li>
 * <li>JavaScript 执行：自定义脚本</li>
 * <li>自动修复：失败重试、错误处理</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * 
 * <pre>
 * // 业务层注入
 * {@code @Autowired}
 * private PageAgentService pageAgentService;
 *
 * // 简单调用
 * PageAgentResult result = pageAgentService.execute(new PageAgentRequest()
 *     .setTask("点击登录按钮")
 *     .setTargetUrl("https://example.com/login")
 * );
 * </pre>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class PageAgentService {

    /** 页面 Agent 配置缓存（从 page-agent-config.yml 加载） */
    private volatile Map<String, Map<String, Object>> pageAgentConfigs;

    /**
     * 页面分析 Prompt
     */
    private static final String PAGE_ANALYSIS_PROMPT = """
            你是一个网页自动化助手。用户要求你操作当前页面。

            用户指令: %s

            当前页面内容:
            %s

            请分析页面内容，找出需要操作的元素，然后给出操作步骤。

            支持的选择器格式:
            - CSS选择器: 如 "#btn-submit", ".n-button", "input[name='username']"
            - :has-text() 选择器: 如 "button:has-text('提交')", "div.menu-item:has-text('食谱管理')"
            - :text() 选择器: 如 "a:text('登录')"
            - text() 选择器: 如 "text('确定')" (按文本查找任意元素)

            请以 JSON 格式返回，格式如下:
            {
                "理解": "你对用户指令的理解",
                "操作": ["操作步骤1", "操作步骤2", ...],
                "actions": [
                    {"type": "fill", "selector": "CSS选择器", "value": "填写的值"},
                    {"type": "click", "selector": "CSS选择器"}
                ]
            }

            如果只是询问信息而不需要操作页面，返回:
            {"理解": "用户的问题", "操作": [], "actions": []}
            """;

    /**
     * 大模型提供商（通过配置自动创建）
     */
    private final ChatModelProvider chatModelProvider;

    /**
     * Page Agent 服务
     *
     * @param properties           AgentScope 配置
     * @param modelProviderFactory 模型提供商工厂
     */
    public PageAgentService(
            AgentscopeCoreProperties properties,
            ModelProviderFactory modelProviderFactory) {
        // 根据配置创建模型提供商
        if (properties != null && properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            ModelConfig config = new ModelConfig();
            config.setApiKey(properties.getApiKey());
            config.setProvider(properties.getProvider());
            config.setModelName(properties.getModelName());
            this.chatModelProvider = modelProviderFactory.createProvider(config);
            log.info("PageAgentService 已初始化 ChatModelProvider: {}", properties.getProvider());
        } else {
            this.chatModelProvider = null;
            log.warn("PageAgentService 未配置 ChatModelProvider，请检查 agentscope.api-key 配置");
        }
    }

    /**
     * Page Agent 会话管理
     */
    private final Map<String, PageAgentSession> sessions = new ConcurrentHashMap<>();

    /**
     * 创建新的 Page Agent 会话
     *
     * @param sessionId 会话ID（可自定义，默认自动生成）
     * @return 会话信息
     */
    public PageAgentSession createSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "session-" + System.currentTimeMillis();
        }

        PageAgentSession session = new PageAgentSession();
        session.setSessionId(sessionId);
        session.setStatus("created");
        session.setCreatedAt(System.currentTimeMillis());

        sessions.put(sessionId, session);
        log.info("创建 Page Agent 会话: {}", sessionId);

        return session;
    }

    /**
     * 执行 Page Agent 任务
     *
     * @param request 执行请求
     * @return 执行结果
     */
    public PageAgentResult execute(PageAgentRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("开始执行 Page Agent 任务: {}, 目标: {}", request.getTask(), request.getTargetUrl());

            // 获取或创建会话
            String sessionId = request.getSessionId();
            if (sessionId == null || !sessions.containsKey(sessionId)) {
                sessionId = createSession(request.getSessionId()).getSessionId();
            }

            PageAgentSession session = sessions.get(sessionId);
            session.setStatus("running");

            // 后端协调层：接收前端传来的用户指令 + 页面 HTML
            // 调用大模型解析用户意图，生成操作步骤
            // 返回操作指令给前端执行（保护 API Key 不暴露）

            String resultMessage = executeTask(request);

            session.setStatus("completed");
            session.setCompletedAt(System.currentTimeMillis());

            long duration = System.currentTimeMillis() - startTime;
            log.info("Page Agent 任务完成，耗时: {}ms", duration);

            PageAgentResult result = new PageAgentResult();
            result.setSuccess(true);
            result.setSessionId(sessionId);
            result.setMessage(resultMessage);
            result.setDuration(duration);
            result.setData(request.getData()); // 传递解析后的数据
            return result;

        } catch (Exception e) {
            log.error("Page Agent 任务执行失败: {}", e.getMessage(), e);
            PageAgentResult result = new PageAgentResult();
            result.setSuccess(false);
            result.setSessionId(request.getSessionId());
            result.setError(e.getMessage());
            result.setDuration(System.currentTimeMillis() - startTime);
            return result;
        }
    }

    /**
     * 执行具体任务
     * 解析用户指令，结合页面 HTML 生成操作步骤
     */
    @SuppressWarnings("unchecked")
    protected String executeTask(PageAgentRequest request) {
        String task = request.getTask();
        String targetUrl = request.getTargetUrl();

        // 提取页面 HTML
        String pageHtml = "";
        if (request.getData() != null && request.getData().get("pageHtml") != null) {
            pageHtml = (String) request.getData().get("pageHtml");
        }

        // 如果没有大模型，直接返回任务描述
        if (chatModelProvider == null) {
            log.warn("PageAgentService 未配置 ChatModelProvider，无法智能解析页面");
            StringBuilder sb = new StringBuilder();
            sb.append("收到任务: ").append(task).append("\n");
            if (targetUrl != null && !targetUrl.isBlank()) {
                sb.append("目标页面: ").append(targetUrl).append("\n");
            }
            sb.append("\n注意: 请在应用中集成 ChatModelProvider 以启用智能页面分析功能");
            return sb.toString();
        }

        try {
            // 构建 Prompt
            String prompt = String.format(PAGE_ANALYSIS_PROMPT, task,
                    pageHtml.length() > 15000 ? pageHtml.substring(0, 15000) : pageHtml);

            // 构建 AgentScope 消息格式
            List<Msg> messages = new ArrayList<>();
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(prompt)
                    .build();
            messages.add(userMsg);

            // 调用大模型
            Flux<ChatResponse> responseFlux = chatModelProvider.stream(messages, null, null);

            // 收集所有响应（有些模型会分块返回）
            List<ChatResponse> responses = responseFlux.collectList().block();

            // 调试日志
            log.info("PageAgent 大模型响应数量: {}", responses != null ? responses.size() : 0);

            // 解析响应
            String assistantMessage = "";
            if (responses != null && !responses.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (ChatResponse resp : responses) {
                    if (resp != null && resp.getContent() != null) {
                        for (var block : resp.getContent()) {
                            log.info("PageAgent block: type={}", block.getClass().getSimpleName());
                            if (block instanceof TextBlock) {
                                sb.append(((TextBlock) block).getText());
                            }
                        }
                    }
                }
                assistantMessage = sb.toString();
            }

            log.info("PageAgent 解析后的消息: {}", assistantMessage);

            // 尝试解析 JSON 响应
            String result = parseModelResponse(assistantMessage, task);
            request.setData(new HashMap<>(Optional.ofNullable(request.getData()).orElse(Map.of())));
            request.getData().put("parsedActions", result);

            return "已分析页面并生成操作步骤:\n" + assistantMessage;

        } catch (Exception e) {
            log.error("PageAgent 大模型调用失败: {}", e.getMessage(), e);
            return "任务已接收: " + task + "\n\n(大模型调用失败，请检查配置)";
        }
    }

    /**
     * 解析大模型响应，提取操作步骤
     */
    private String parseModelResponse(String response, String task) {
        // 简单尝试解析 JSON
        try {
            // 查找 JSON 块
            int jsonStart = response.indexOf("{");
            int jsonEnd = response.lastIndexOf("}");
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart, jsonEnd + 1);
                // 返回解析后的内容
                return jsonStr;
            }
        } catch (Exception e) {
            log.debug("解析 JSON 失败: {}", e.getMessage());
        }
        return response;
    }

    /**
     * 获取会话状态
     */
    public PageAgentSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 关闭会话
     */
    public void closeSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("关闭 Page Agent 会话: {}", sessionId);
    }

    /**
     * 批量操作：自动填写表单
     * <p>
     * 封装常用的表单填写操作
     * </p>
     *
     * @param sessionId 会话ID
     * @param targetUrl 目标页面
     * @param formData  表单数据（key: 字段名, value: 值）
     * @return 执行结果
     */
    public PageAgentResult autoFill(String sessionId, String targetUrl, Map<String, Object> formData) {
        PageAgentRequest request = new PageAgentRequest();
        request.setSessionId(sessionId);
        request.setTargetUrl(targetUrl);
        request.setTask("自动填写表单");
        request.setData(formData);

        return execute(request);
    }

    /**
     * 批量操作：导航 + 填写 + 提交
     *
     * @param targetUrl  目标页面
     * @param formData   表单数据
     * @param submitText 提交按钮文字（如"提交"、"保存"）
     * @return 执行结果
     */
    public PageAgentResult navigateAndSubmit(String targetUrl, Map<String, Object> formData, String submitText) {
        PageAgentRequest request = new PageAgentRequest();
        request.setTargetUrl(targetUrl);
        request.setTask("导航到页面，填写表单，点击" + submitText + "按钮");
        request.setData(formData);

        return execute(request);
    }

    // ==================== DTO ====================

    /**
     * Page Agent 请求
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageAgentRequest {
        /**
         * 会话ID（可选，默认自动创建）
         */
        private String sessionId;

        /**
         * 任务描述（自然语言指令）
         */
        private String task;

        /**
         * 目标页面 URL
         */
        private String targetUrl;

        /**
         * 额外数据（如表单数据）
         */
        private Map<String, Object> data;

        /**
         * 最大执行步数
         */
        private Integer maxSteps = 40;

        /**
         * 是否启用可视化遮罩
         */
        private Boolean enableMask = false;
    }

    /**
     * Page Agent 结果
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageAgentResult {
        /**
         * 是否成功
         */
        private boolean success;

        /**
         * 会话ID
         */
        private String sessionId;

        /**
         * 执行结果消息
         */
        private String message;

        /**
         * 错误信息（失败时）
         */
        private String error;

        /**
         * 执行耗时（毫秒）
         */
        private long duration;

        /**
         * 执行步数
         */
        private int steps;

        /**
         * 额外数据（如解析后的 actions）
         */
        private Map<String, Object> data;
    }

    /**
     * Page Agent 会话信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageAgentSession {
        /**
         * 会话ID
         */
        private String sessionId;

        /**
         * 状态：created/running/completed/error
         */
        private String status;

        /**
         * 创建时间
         */
        private long createdAt;

        /**
         * 完成时间
         */
        private long completedAt;

        /**
         * 当前页面 URL
         */
        private String currentUrl;

        /**
         * 执行历史
         */
        private String history;
    }

    /**
     * OpenAI 兼容代理：将 /v1/chat/completions 格式的请求转发到已配置的 LLM
     * <p>
     * 供 page-agent SDK 的 customFetch 调用，API Key 不暴露在前端
     * </p>
     *
     * @param request OpenAI 格式请求体（包含 model, messages, tools 等）
     * @return OpenAI 格式响应
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> proxyChatCompletion(Map<String, Object> request) {
        if (chatModelProvider == null) {
            return Map.of(
                    "error", Map.of("message", "LLM 服务未配置", "type", "server_error"),
                    "status", 500);
        }

        try {
            // 1. 从 OpenAI 格式的 messages 构建 AgentScope Msg 列表
            List<Map<String, String>> openaiMessages = (List<Map<String, String>>) request.get("messages");
            List<Msg> messages = new ArrayList<>();

            if (openaiMessages != null) {
                for (Map<String, String> msg : openaiMessages) {
                    String role = msg.getOrDefault("role", "user");
                    String content = msg.getOrDefault("content", "");

                    MsgRole msgRole = switch (role) {
                        case "system" -> MsgRole.SYSTEM;
                        case "assistant" -> MsgRole.ASSISTANT;
                        default -> MsgRole.USER;
                    };

                    Msg agentMsg = Msg.builder()
                            .role(msgRole)
                            .textContent(content)
                            .build();
                    messages.add(agentMsg);
                }
            }

            // 2. 调用 LLM
            Flux<ChatResponse> responseFlux = chatModelProvider.stream(messages, null, null);
            List<ChatResponse> responses = responseFlux.collectList()
                    .block(Duration.ofSeconds(120));

            // 3. 提取文本内容
            StringBuilder contentBuilder = new StringBuilder();
            if (responses != null) {
                for (ChatResponse resp : responses) {
                    if (resp.getContent() != null) {
                        resp.getContent().stream()
                                .filter(block -> block instanceof TextBlock)
                                .map(block -> ((TextBlock) block).getText())
                                .forEach(contentBuilder::append);
                    }
                }
            }

            String assistantContent = contentBuilder.toString();

            // 4. 构建 OpenAI 格式响应
            // 支持 tool_calls：如果 LLM 返回了 JSON 格式的工具调用，原样传递
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");

            // 尝试解析为 tool_calls 格式
            try {
                Object parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(assistantContent, Object.class);
                if (parsed instanceof Map) {
                    Map<String, Object> parsedMap = (Map<String, Object>) parsed;
                    // 如果包含 action 或 tool_call 相关字段，包装为 tool_calls
                    if (parsedMap.containsKey("action") || parsedMap.containsKey("actions") ||
                            parsedMap.containsKey("tool_calls")) {
                        message.put("content", null);
                        List<Map<String, Object>> toolCalls = new ArrayList<>();
                        Map<String, Object> toolCall = new LinkedHashMap<>();
                        toolCall.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                        toolCall.put("type", "function");
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("name", "AgentOutput");
                        function.put("arguments", assistantContent);
                        toolCall.put("function", function);
                        toolCalls.add(toolCall);
                        message.put("tool_calls", toolCalls);
                    } else {
                        message.put("content", assistantContent);
                    }
                } else {
                    message.put("content", assistantContent);
                }
            } catch (Exception e) {
                // 非 JSON，作为纯文本内容返回
                message.put("content", assistantContent);
            }

            Map<String, Object> choice = new LinkedHashMap<>();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", "stop");

            Map<String, Object> usage = Map.of(
                    "prompt_tokens", 0,
                    "completion_tokens", 0,
                    "total_tokens", 0);

            return Map.of(
                    "id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                    "object", "chat.completion",
                    "created", System.currentTimeMillis() / 1000,
                    "model", request.getOrDefault("model", "unknown"),
                    "choices", List.of(choice),
                    "usage", usage);

        } catch (Exception e) {
            log.error("OpenAI 代理请求失败", e);
            return Map.of(
                    "error", Map.of("message", e.getMessage(), "type", "server_error"),
                    "status", 500);
        }
    }

    /**
     * 获取 Agent 配置（tool schemas + system prompt）
     * <p>
     * 从 agents/page-agent-config.yml 读取配置，框架层零业务硬编码。
     * 修改提示词只需编辑 YAML 文件，无需重新编译部署。
     * </p>
     *
     * @param pageType 页面类型标识（如 "recipe-make"），对应 YAML 中的 pageType 字段
     * @return 包含 systemPrompt 和 tools 的配置 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAgentConfig(String pageType) {
        if (pageAgentConfigs == null) {
            loadPageAgentConfigs();
        }

        if (pageType != null && pageAgentConfigs.containsKey(pageType)) {
            return pageAgentConfigs.get(pageType);
        }

        // 返回默认配置
        return pageAgentConfigs.getOrDefault("default", Map.of(
                "systemPrompt",
                "You are an AI assistant with page automation tools. Help the user accomplish tasks on the current page.",
                "tools", Map.of()));
    }

    /**
     * 从 agents/page-agent-config.yml 加载页面 Agent 配置
     */
    @SuppressWarnings("unchecked")
    private void loadPageAgentConfigs() {
        pageAgentConfigs = new LinkedHashMap<>();
        try {
            var resource = new org.springframework.core.io.ClassPathResource("page-configs/page-agent-config.yml");
            if (!resource.exists()) {
                log.warn("page-configs/page-agent-config.yml not found, using defaults");
                return;
            }
            var yaml = new org.yaml.snakeyaml.Yaml();
            Map<String, Object> root = yaml.load(resource.getInputStream());
            if (root == null)
                return;

            var agents = (List<Map<String, Object>>) root.get("pageAgents");
            if (agents == null)
                return;

            for (var agent : agents) {
                String pt = (String) agent.get("pageType");
                if (pt == null)
                    continue;

                String sp = (String) agent.get("systemPrompt");
                Map<String, Object> tools = new LinkedHashMap<>();

                var toolsConfig = (Map<String, Map<String, Object>>) agent.get("tools");
                if (toolsConfig != null) {
                    for (var entry : toolsConfig.entrySet()) {
                        var td = entry.getValue();
                        tools.put(entry.getKey(), Map.of(
                                "description", td.getOrDefault("description", entry.getKey()),
                                "params", td.getOrDefault("params", Map.of())));
                    }
                }

                pageAgentConfigs.put(pt, Map.of(
                        "systemPrompt", sp != null ? sp : "",
                        "tools", tools));
            }
            log.info("Loaded {} page-agent configs: {}", pageAgentConfigs.size(), pageAgentConfigs.keySet());
        } catch (Exception e) {
            log.error("Failed to load page-agent-config.yml", e);
        }
    }
}