package io.yunxi.platform.intent.classify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.intent.Entity;
import io.yunxi.platform.intent.Intent;
import io.yunxi.platform.intent.config.IntentProperties.Classification.Llm;
import io.yunxi.platform.intent.domain.DomainRuntime;
import io.yunxi.platform.intent.snapshot.TreeSnapshot;
import io.yunxi.platform.intent.util.IntentKeywordMatcher;
import io.yunxi.platform.shared.config.AgentModelConfig;
import io.yunxi.platform.tracing.LlmMetrics;

/**
 * LLM 白名单意图分类器。
 *
 * <p>从意图树快照生成白名单（{@code id/label/description?}），携带用户查询与归一化实体
 * 构造结构化 JSON 请求，要求模型输出单一意图。响应后置校验三重：JSON 解析失败 /
 * intentId 不在白名单 / confidence 越界或低于 {@code min-confidence}，任一不过即降级
 * {@link Intent#unknown()}（不抛出）。</p>
 *
 * <p>白名单超过 {@code max-intents}（默认 30）时用查询对节点做关键词粗筛留 Top-N，
 * 控制 prompt 长度。LLM 调用经 resilience4j TimeLimiter（{@code timeout-ms} 生效）+
 * CircuitBreaker 双防护，超时/熔断/异常一律降级 unknown，由编排方（{@link HybridIntentClassifier}）
 * 以 RULE-FALLBACK 保底。</p>
 *
 * <p>本类非 Spring Bean（避免与 {@link RuleIntentClassifier} 产生注入歧义），
 * 由 {@link HybridIntentClassifier} 组合持有。</p>
 */
public class LlmIntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    private static final String SYSTEM_PROMPT = """
            你是意图分类器。根据用户输入，从给定意图列表（intents）中选择最匹配的一个。
            只输出 JSON，不要输出其他内容，格式：
            {"intentId": "<意图ID>", "confidence": 0~1 的数值, "reason": "一句话简述"}
            硬性要求：
            1. intentId 必须来自给定 intents 列表，禁止输出列表之外的 ID；
            2. confidence 低于 0.5 或列表内都不匹配时，输出 {"intentId": "unknown", "confidence": 0, "reason": "..."}；
            3. 不要解释、不要 markdown 代码块。
            """;

    private final ModelFactory modelFactory;
    private final LlmMetrics llmMetrics;
    private final ObjectMapper objectMapper;
    private final AgentModelConfig modelConfig;
    private final String modelName;
    private final double minConfidence;
    private final int maxIntents;
    private final long timeoutMs;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;

    public LlmIntentClassifier(ModelFactory modelFactory, LlmMetrics llmMetrics,
            ObjectMapper objectMapper, Llm llm) {
        this.modelFactory = modelFactory;
        this.llmMetrics = llmMetrics;
        this.objectMapper = objectMapper;
        this.modelName = llm.getModel();
        this.modelConfig = new AgentModelConfig();
        this.modelConfig.setModelName(llm.getModel());
        this.minConfidence = llm.getMinConfidence();
        this.maxIntents = Math.max(1, llm.getMaxIntents());
        this.timeoutMs = Math.max(1, llm.getTimeoutMs());
        this.circuitBreaker = CircuitBreaker.ofDefaults("intentLlm");
        this.timeLimiter = TimeLimiter.of(Duration.ofMillis(this.timeoutMs));
    }

    /**
     * LLM 白名单分类。
     *
     * @param query     重写后的查询
     * @param entities  归一化实体（可空）
     * @param sceneName 场景名（可空；白名单请求上下文携带）
     * @param runtime   域运行时（意图树快照；null 时返回 unknown）
     * @return 分类意图；任一校验失败返回 {@link Intent#unknown()}
     */
    public Intent classify(String query, List<Entity> entities, String sceneName, DomainRuntime runtime) {
        if (runtime == null || runtime.tree() == null) {
            return Intent.unknown();
        }
        List<IntentTree.Node> whitelist = whitelist(runtime.tree(), query);
        if (whitelist.isEmpty()) {
            return Intent.unknown();
        }
        List<Msg> messages = buildMessages(query, entities, sceneName, whitelist);
        ChatResponse response = invoke(messages);
        if (response == null) {
            return Intent.unknown();
        }
        llmMetrics.recordAndLogUsage(modelName, "yunxi", response.getUsage());
        String text = extractText(response);
        return parseResult(text, whitelist);
    }

    /** 白名单：全量节点过滤出可分类叶子/节点，超过 max-intents 时按查询粗筛留 Top-N */
    private List<IntentTree.Node> whitelist(TreeSnapshot tree, String query) {
        List<IntentTree.Node> nodes = tree.allNodes().stream()
                .filter(n -> n.id != null && !n.id.isBlank() && n.label != null && !n.label.isBlank())
                .toList();
        if (nodes.size() <= maxIntents) {
            return nodes;
        }
        String lower = IntentKeywordMatcher.lower(query);
        return nodes.stream()
                .sorted(Comparator.comparingInt((IntentTree.Node n) -> IntentKeywordMatcher.keywordScore(n.match, lower)).reversed())
                .limit(maxIntents)
                .toList();
    }

    /** 构造 system + user 消息；请求体为结构化 JSON（白名单协议） */
    private List<Msg> buildMessages(String query, List<Entity> entities, String sceneName,
            List<IntentTree.Node> whitelist) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("query", query == null ? "" : query);
        if (entities != null && !entities.isEmpty()) {
            req.put("entities", entities.stream()
                    .map(e -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("type", e.type());
                        m.put("value", e.value());
                        return m;
                    })
                    .toList());
        }
        if (sceneName != null && !sceneName.isBlank()) {
            req.put("sceneName", sceneName);
        }
        List<Map<String, Object>> intents = new ArrayList<>(whitelist.size());
        for (IntentTree.Node n : whitelist) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.id);
            item.put("label", n.label);
            if (n.description != null && !n.description.isBlank()) {
                item.put("description", n.description);
            }
            intents.add(item);
        }
        req.put("intents", intents);
        try {
            Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent(SYSTEM_PROMPT).build();
            Msg user = Msg.builder().role(MsgRole.USER)
                    .textContent(objectMapper.writeValueAsString(req)).build();
            return List.of(system, user);
        } catch (Exception e) {
            log.warn("[INTENT] LLM 请求构造失败，降级 unknown: {}", e.getMessage());
            return List.of();
        }
    }

    /** TimeLimiter + CircuitBreaker 包裹 LLM 调用；任何失败返回 null（不抛出） */
    private ChatResponse invoke(List<Msg> messages) {
        if (messages.isEmpty()) {
            return null;
        }
        Callable<ChatResponse> timed = timeLimiter.decorateFutureSupplier(
                () -> CompletableFuture.supplyAsync(() -> {
                    try {
                        Model model = modelFactory.create(modelConfig);
                        return model.stream(messages, null, null).blockFirst();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }));
        Callable<ChatResponse> guarded = circuitBreaker.decorateCallable(timed);
        try {
            return guarded.call();
        } catch (CallNotPermittedException e) {
            log.warn("[INTENT] LLM 分类熔断打开，降级 unknown: {}", e.getMessage());
            return null;
        } catch (TimeoutException e) {
            log.warn("[INTENT] LLM 分类超时（{}ms），降级 unknown", timeoutMs);
            return null;
        } catch (Exception e) {
            log.warn("[INTENT] LLM 分类调用异常，降级 unknown: {}", e.getMessage());
            return null;
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return null;
        }
        return response.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .collect(java.util.stream.Collectors.joining());
    }

    /** 解析 JSON 响应并三重校验；任一不过返回 unknown */
    private Intent parseResult(String text, List<IntentTree.Node> whitelist) {
        if (text == null || text.isBlank()) {
            return Intent.unknown();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (Exception e) {
            log.warn("[INTENT] LLM 响应非 JSON，降级 unknown: {}", text);
            return Intent.unknown();
        }
        if (root == null || !root.isObject()) {
            return Intent.unknown();
        }
        String intentId = root.path("intentId").asText("");
        if (intentId.isBlank() || Intent.UNKNOWN_ID.equals(intentId)) {
            return Intent.unknown();
        }
        // 校验一：intentId 必须在白名单内
        IntentTree.Node matched = null;
        for (IntentTree.Node n : whitelist) {
            if (intentId.equals(n.id)) {
                matched = n;
                break;
            }
        }
        if (matched == null) {
            log.warn("[INTENT] LLM 输出白名单外意图，拒绝: intentId={}", intentId);
            return Intent.unknown();
        }
        // 校验二：confidence 必须为 [0,1] 数值
        if (!root.path("confidence").isNumber()) {
            log.warn("[INTENT] LLM 响应 confidence 非数值，降级 unknown: intentId={}", intentId);
            return Intent.unknown();
        }
        double confidence = root.path("confidence").asDouble(-1);
        if (confidence < 0 || confidence > 1) {
            log.warn("[INTENT] LLM 响应 confidence 越界，降级 unknown: confidence={}", confidence);
            return Intent.unknown();
        }
        // 校验三：confidence 低于 min-confidence 拒绝
        if (confidence < minConfidence) {
            log.debug("[INTENT] LLM 置信过低，降级 unknown: intentId={} confidence={} min={}",
                    intentId, confidence, minConfidence);
            return Intent.unknown();
        }
        return new Intent(matched.id, matched.label, confidence, Intent.MATCHED_LLM);
    }
}
