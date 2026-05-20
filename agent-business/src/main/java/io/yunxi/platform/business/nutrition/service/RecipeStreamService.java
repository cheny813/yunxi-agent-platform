package io.yunxi.platform.business.nutrition.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.business.nutrition.controller.RecipeSseController.BalanceRequest;
import io.yunxi.platform.business.nutrition.controller.RecipeSseController.FormFillRequest;
import io.yunxi.platform.business.nutrition.controller.RecipeSseController.RecipeGenerateRequest;
import io.yunxi.platform.framework.agent.AgentDomainService;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import io.yunxi.platform.infra.sse.ProgressListener;
import io.yunxi.platform.infra.sse.SseProgressListenerAdapter;
import io.yunxi.platform.shared.dto.StreamChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 食谱流式生成服务
 *
 * <p>
 * 提供食谱生成和营养配平的流式/异步处理能力，支持 WebFlux SSE 和 SseEmitter 两种模式。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Service
public class RecipeStreamService {

    /** 营养领域 Agent 名称常量 */
    public static final String NUTRITION_AGENT_NAME = "nutrition-assistant";

    /** Agent 领域服务，用于获取 Agent 实例 */
    @Autowired
    private AgentDomainService agentDomainService;

    /** AgentScope 配置属性 */
    @Autowired
    private AgentscopeCoreProperties properties;

    /** SSE 通知发送器 */
    @Autowired
    private SseNotificationProvider emitterManager;

    /** SSE 进度监听适配器 */
    @Autowired
    private SseProgressListenerAdapter progressListenerAdapter;

    /** JSON 序列化/反序列化工具 */
    @Autowired
    private ObjectMapper objectMapper;

    /** REST 请求模板 */
    private final RestTemplate restTemplate = new RestTemplate();

    /** 本地食谱评估器（可选，用于内联评估替代MCP远程调用） */
    @Autowired(required = false)
    private RecipeEvaluator recipeEvaluator;

    /** WebSocket 表单回写 API 地址 */
    @Value("${recipe.websocket.api-url:}")
    private String websocketApiUrl;

    /**
     * 流式生成食谱（WebFlux 方式）
     *
     * @param request 流式聊天请求，包含用户消息和上下文数据
     * @return SSE 事件流，包含生成内容、完成事件或错误事件
     */
    public Flux<ServerSentEvent<String>> streamGenerateRecipe(StreamChatRequest request) {
        return Flux.defer(() -> {
            try {
                ReActAgent agent = agentDomainService.getAgentInstance(NUTRITION_AGENT_NAME);
                String prompt = buildRecipePrompt(request);
                Msg userMsg = Msg.builder().textContent(prompt).build();

                Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
                StreamOptions streamOptions = StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .incremental(true)
                        .build();

                return agent.stream(List.of(userMsg), streamOptions)
                        .timeout(timeout)
                        .filter(event -> event.getType() == EventType.REASONING)
                        .map(event -> {
                            String content = event.getMessage().getTextContent();
                            return ServerSentEvent.<String>builder()
                                    .event("content")
                                    .data(content)
                                    .build();
                        })
                        .concatWith(Flux.just(
                                ServerSentEvent.<String>builder()
                                        .event("done")
                                        .data("{\"status\":\"complete\"}")
                                        .build()))
                        .onErrorResume(e -> {
                            log.error("流式生成失败", e);
                            return Flux.just(
                                    ServerSentEvent.<String>builder()
                                            .event("error")
                                            .data("{\"error\":\"" + e.getMessage() + "\"}")
                                            .build());
                        });

            } catch (Exception e) {
                log.error("流式生成失败", e);
                return Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"error\":\"" + e.getMessage() + "\"}")
                                .build());
            }
        });
    }

    /**
     * 异步生成食谱（SseEmitter 方式）
     *
     * @param sessionId 会话ID，用于推送 SSE 事件
     * @param request   食谱生成请求参数
     */
    @Async
    public void generateRecipeAsync(String sessionId, RecipeGenerateRequest request) {
        ProgressListener<Map<String, Object>> listener = progressListenerAdapter.createWithAutoComplete(sessionId);
        String taskId = UUID.randomUUID().toString();

        try {
            listener.onStart(taskId, "食谱生成任务");
            listener.onPhase(taskId, "数据准备", 1, 3);

            StreamChatRequest chatRequest = new StreamChatRequest();
            chatRequest.setMessage(buildPromptFromRequest(request));
            chatRequest.setContextData(buildContextData(request));

            listener.onPhase(taskId, "AI 生成中", 2, 3);

            ReActAgent agent = agentDomainService.getAgentInstance(NUTRITION_AGENT_NAME);
            Msg userMsg = Msg.builder().textContent(chatRequest.getMessage()).build();

            Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
            StreamOptions streamOptions = StreamOptions.builder()
                    .eventTypes(EventType.REASONING)
                    .incremental(true)
                    .build();

            StringBuilder fullContent = new StringBuilder();
            int[] progressCounter = { 0 };

            agent.stream(List.of(userMsg), streamOptions)
                    .timeout(timeout)
                    .filter(event -> event.getType() == EventType.REASONING)
                    .doOnNext(event -> {
                        String content = event.getMessage().getTextContent();
                        fullContent.append(content);
                        emitterManager.send(sessionId, "content", Map.of(
                                "chunk", content,
                                "accumulated", fullContent.length()));
                        progressCounter[0]++;
                        int progress = Math.min(90, progressCounter[0] * 2);
                        listener.onProgress(taskId, progress, 100, "AI 正在生成...");
                    })
                    .blockLast();

            listener.onPhase(taskId, "结果解析", 3, 3);
            Map<String, Object> result = parseRecipeResult(fullContent.toString());
            listener.onComplete(taskId, result);

            log.info("食谱生成完成: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("食谱生成失败: sessionId={}", sessionId, e);
            listener.onError(taskId, e);
        }
    }

    /**
     * 触发表单回写，优先通过 SSE 推送，备选通过 WebSocket API
     *
     * @param request 表单填写请求，包含会话ID、场景、表单数据和参数
     */
    public void triggerFormFill(FormFillRequest request) {
        if (request.getSessionId() != null && emitterManager.hasEmitter(request.getSessionId())) {
            Map<String, Object> fillEvent = new HashMap<>();
            fillEvent.put("type", "form_fill");
            fillEvent.put("scene", request.getScene());
            fillEvent.put("formData", request.getFormData());
            fillEvent.put("params", request.getParams());
            emitterManager.send(request.getSessionId(), "form_fill", fillEvent);
            return;
        }

        if (websocketApiUrl != null && !websocketApiUrl.isEmpty()) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                Map<String, Object> message = new HashMap<>();
                message.put("type", "fill");
                message.put("scene", request.getScene());
                message.put("formData", request.getFormData());
                message.put("params", request.getParams());

                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(message, headers);
                restTemplate.postForEntity(websocketApiUrl + "/formfill/trigger", entity, String.class);
            } catch (Exception e) {
                log.error("触发表单回写失败", e);
            }
        }
    }

    /**
     * 流式配平食谱（WebFlux 方式）
     *
     * @param request 配平请求参数
     * @return SSE 事件流，包含配平内容、配平结果或错误事件
     */
    public Flux<ServerSentEvent<String>> streamBalanceRecipe(BalanceRequest request) {
        return Flux.defer(() -> {
            try {
                ReActAgent agent = agentDomainService.getAgentInstance(NUTRITION_AGENT_NAME);
                String prompt = buildBalancePrompt(request);
                Msg userMsg = Msg.builder().textContent(prompt).build();

                Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
                StreamOptions streamOptions = StreamOptions.builder()
                        .eventTypes(EventType.REASONING)
                        .incremental(true)
                        .build();

                StringBuilder fullContent = new StringBuilder();

                return agent.stream(List.of(userMsg), streamOptions)
                        .timeout(timeout)
                        .filter(event -> event.getType() == EventType.REASONING)
                        .map(event -> {
                            String content = event.getMessage().getTextContent();
                            fullContent.append(content);
                            return ServerSentEvent.<String>builder()
                                    .event("balance_chunk")
                                    .data(Map.of("chunk", content).toString())
                                    .build();
                        })
                        .concatWith(Flux.defer(() -> {
                            Map<String, Object> balanceResult = parseBalanceResult(fullContent.toString());
                            return Flux.just(
                                    ServerSentEvent.<String>builder()
                                            .event("balance_result")
                                            .data(toJson(balanceResult))
                                            .build());
                        }))
                        .onErrorResume(e -> {
                            log.error("流式配平失败", e);
                            return Flux.just(
                                    ServerSentEvent.<String>builder()
                                            .event("error")
                                            .data("{\"error\":\"" + e.getMessage() + "\"}")
                                            .build());
                        });

            } catch (Exception e) {
                log.error("流式配平失败", e);
                return Flux.just(
                        ServerSentEvent.<String>builder()
                                .event("error")
                                .data("{\"error\":\"" + e.getMessage() + "\"}")
                                .build());
            }
        });
    }

    /**
     * 异步配平食谱（SseEmitter 方式）
     *
     * @param sessionId 会话ID，用于推送 SSE 事件
     * @param request   配平请求参数
     */
    @Async
    public void balanceRecipeAsync(String sessionId, BalanceRequest request) {
        ProgressListener<Map<String, Object>> listener = progressListenerAdapter.createWithAutoComplete(sessionId);
        String taskId = UUID.randomUUID().toString();

        try {
            listener.onStart(taskId, "食谱配平任务");
            listener.onPhase(taskId, "AI 配平", 1, 2);

            ReActAgent agent = agentDomainService.getAgentInstance(NUTRITION_AGENT_NAME);
            String prompt = buildBalancePrompt(request);
            Msg userMsg = Msg.builder().textContent(prompt).build();

            Duration timeout = Duration.ofSeconds(properties.getChatTimeoutSeconds());
            StreamOptions streamOptions = StreamOptions.builder()
                    .eventTypes(EventType.REASONING)
                    .incremental(true)
                    .build();

            StringBuilder fullContent = new StringBuilder();
            int[] progressCounter = { 0 };

            agent.stream(List.of(userMsg), streamOptions)
                    .timeout(timeout)
                    .filter(event -> event.getType() == EventType.REASONING)
                    .doOnNext(event -> {
                        String content = event.getMessage().getTextContent();
                        fullContent.append(content);
                        emitterManager.send(sessionId, "balance_content", Map.of(
                                "chunk", content,
                                "accumulated", fullContent.length()));
                        progressCounter[0] = Math.min(90, progressCounter[0] + 1);
                        listener.onProgress(taskId, progressCounter[0], 100, "AI 正在优化...");
                    })
                    .blockLast();

            listener.onPhase(taskId, "结果解析", 2, 2);
            Map<String, Object> balanceResult = parseBalanceResult(fullContent.toString());
            listener.onComplete(taskId, balanceResult);

            log.info("食谱配平完成: sessionId={}", sessionId);

        } catch (Exception e) {
            log.error("食谱配平失败: sessionId={}", sessionId, e);
            listener.onError(taskId, e);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 构建食谱生成提示词
     *
     * <p>
     * 根据用户请求和上下文数据动态构建AI提示词，
     * 支持学校ID和生成天数的个性化配置
     * </p>
     *
     * @param request 流式聊天请求，包含用户消息和学校上下文信息
     * @return 构建好的提示词字符串，格式化为AI可理解的食谱生成指令
     *
     *         <p>
     *         提示词结构包含：
     *         </p>
     *         <ul>
     *         <li>基本食谱生成指令</li>
     *         <li>学校ID信息（如果提供）</li>
     *         <li>生成天数设置（默认7天）</li>
     *         <li>用户的具体需求内容</li>
     *         </ul>
     */
    private String buildRecipePrompt(StreamChatRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请根据以下要求生成食谱：\n\n");

        Map<String, Object> context = request.getContextData();
        if (context != null) {
            if (context.containsKey("schoolId")) {
                prompt.append("学校ID：").append(context.get("schoolId")).append("\n");
            }
            if (context.containsKey("days")) {
                prompt.append("生成天数：").append(context.get("days")).append(" 天\n");
            }
        }

        prompt.append("\n用户需求：").append(request.getMessage());
        return prompt.toString();
    }

    /**
     * 从食谱生成请求构建完整的AI提示词
     *
     * <p>
     * 将业务层格式的食谱生成请求转换为AI可理解的详细提示词，
     * 包含学校环境配置和个性化需求信息
     * </p>
     *
     * @param request 食谱生成请求对象，包含学校信息、天数和特殊要求
     * @return 完整的AI提示词字符串，用于驱动食谱生成流程
     *
     *         <p>
     *         生成逻辑：
     *         </p>
     *         <ol>
     *         <li>基础指令：请求生成营养均衡的食谱</li>
     *         <li>环境配置：学校ID和生成天数</li>
     *         <li>个性化需求：用户提供的特殊要求（如有）</li>
     *         </ol>
     */
    private String buildPromptFromRequest(RecipeGenerateRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请生成一份营养均衡的食谱。\n\n");
        prompt.append("- 学校ID：").append(request.getSchoolId()).append("\n");
        prompt.append("- 生成天数：").append(request.getDays() != null ? request.getDays() : 7).append(" 天\n");

        if (request.getPrompt() != null && !request.getPrompt().isEmpty()) {
            prompt.append("\n特殊要求：").append(request.getPrompt()).append("\n");
        }

        return prompt.toString();
    }

    /**
     * 从食谱生成请求构建系统上下文数据
     *
     * <p>
     * 提取食谱生成请求中的关键业务参数，
     * 构建用于后续处理和权限验证的上下文信息
     * </p>
     *
     * @param request 食谱生成请求对象，包含完整的业务参数
     * @return 上下文数据Map，包含学校ID、营养标准ID、就餐人群ID和生成天数
     *
     *         <p>
     *         上下文数据结构：
     *         </p>
     *         <ul>
     *         <li>schoolId - 学校标识，用于环境隔离</li>
     *         <li>nsId - 营养标准ID，决定食谱生成标准</li>
     *         <li>nscId - 就餐人群ID，确定目标人群的饮食需求</li>
     *         <li>days - 生成天数，影响食谱的时间跨度</li>
     *         </ul>
     */
    private Map<String, Object> buildContextData(RecipeGenerateRequest request) {
        Map<String, Object> context = new HashMap<>();
        context.put("schoolId", request.getSchoolId());
        context.put("nsId", request.getNsId());
        context.put("nscId", request.getNscId());
        context.put("days", request.getDays());
        return context;
    }

    /**
     * 构建食谱营养配平提示词
     *
     * <p>
     * 为AI配平任务构建详细的提示词，包含营养标准、目标人群和当前食谱数据，
     * 确保AI能够基于完整信息进行营养优化推荐
     * </p>
     *
     * @param request 配平请求对象，包含营养标准、就餐人群和现有食谱数据
     * @return 配平专用的AI提示词字符串，指导AI进行营养优化
     *
     *         <p>
     *         提示词内容包含：
     *         </p>
     *         <ul>
     *         <li>配平任务说明：说明优化目标和要求</li>
     *         <li>营养标准配置：提供营养标准ID作为优化依据</li>
     *         <li>目标人群信息：就餐人群ID定义人群特征</li>
     *         <li>当前食谱数据：原始食谱数据作为优化基线</li>
     *         </ul>
     */
    private String buildBalancePrompt(BalanceRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("请对以下食谱进行营养配平优化。\n\n");
        prompt.append("营养标准ID: ").append(request.getNsId()).append("\n");
        prompt.append("就餐人群ID: ").append(request.getNscId()).append("\n");

        if (request.getRecipeData() != null && !request.getRecipeData().isEmpty()) {
            prompt.append("\n当前食谱数据:\n");
            prompt.append(toJson(request.getRecipeData()));
        }

        return prompt.toString();
    }

    /**
     * 解析食谱生成结果，尝试提取 JSON 内容
     *
     * @param content AI 生成的原始文本内容
     * @return 解析后的结果 Map，解析失败时包含 rawContent
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseRecipeResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String jsonContent = extractJson(content);
            if (jsonContent != null) {
                result = objectMapper.readValue(jsonContent, Map.class);
            } else {
                result.put("rawContent", content);
            }
        } catch (Exception e) {
            log.warn("解析食谱结果失败", e);
            result.put("rawContent", content);
        }
        return result;
    }

    /**
     * 解析配平结果，尝试提取 JSON 内容
     *
     * @param content AI 生成的原始文本内容
     * @return 解析后的结果 Map，解析失败时包含 rawContent
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBalanceResult(String content) {
        Map<String, Object> result = new HashMap<>();
        try {
            String jsonContent = extractJson(content);
            if (jsonContent != null) {
                result = objectMapper.readValue(jsonContent, Map.class);
            } else {
                result.put("rawContent", content);
            }
        } catch (Exception e) {
            log.warn("解析配平结果失败", e);
            result.put("rawContent", content);
        }
        return result;
    }

    /**
     * 从AI生成的文本内容中智能提取JSON字符串
     *
     * <p>
     * 由于AI返回的内容可能包含自然语言描述、代码块等多种格式，
     * 该方法采用多层解析策略以最大化提取有效JSON数据：
     * 1. 优先解析```json代码块中的JSON
     * 2. 其次尝试边界匹配的第一对花括号内容
     * 3. 确保JSON格式的有效性和完整性
     * </p>
     *
     * @param content AI生成的原生文本内容，可能包含JSON、说明文本和代码块
     * @return 成功提取的JSON字符串，未找到有效JSON时返回null
     *
     *         <p>
     *         <strong>提取策略优先级：</strong>
     *         </p>
     *         <ol>
     *         <li><strong>优先策略</strong>：搜索```json代码块标记</li>
     *         <li><strong>备选策略</strong>：使用括号匹配算法定位第一个完整JSON对象</li>
     *         <li><strong>失败处理</strong>：无法提取时返回null，由调用方决定后续处理</li>
     *         </ol>
     *
     *         <p>
     *         <strong>代码块格式示例：</strong>
     *         </p>
     * 
     *         <pre>{@code
     * 这是AI生成的内容。
     * 请使用以下JSON数据：
     * ```json
     * {"recipe": {"name": "红烧肉", "ingredients": [...]}}
     * ```
     * 希望对你有帮助。
     * }</pre>
     *
     * @see #parseRecipeResult(String)
     * @see #parseBalanceResult(String)
     * @since 2.0.0
     */
    private String extractJson(String content) {
        if (content == null)
            return null;

        int jsonStart = content.indexOf("```json");
        if (jsonStart != -1) {
            int jsonEnd = content.indexOf("```", jsonStart + 7);
            if (jsonEnd != -1) {
                return content.substring(jsonStart + 7, jsonEnd).trim();
            }
        }

        int braceStart = content.indexOf('{');
        if (braceStart != -1) {
            int braceCount = 0;
            for (int i = braceStart; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{')
                    braceCount++;
                else if (c == '}')
                    braceCount--;

                if (braceCount == 0) {
                    return content.substring(braceStart, i + 1);
                }
            }
        }

        return null;
    }

    /**
     * 将对象序列化为 JSON 字符串
     *
     * @param obj 待序列化的对象
     * @return JSON 字符串，序列化失败返回 "{}"
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }
}
