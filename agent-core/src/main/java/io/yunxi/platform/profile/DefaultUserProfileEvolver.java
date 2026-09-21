package io.yunxi.platform.profile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.yunxi.platform.agent.model.ModelFactory;
import io.yunxi.platform.profile.config.ProfileProperties;
import io.yunxi.platform.shared.config.AgentModelConfig;
import io.yunxi.platform.spi.UserProfileEvolver;
import io.yunxi.platform.spi.profile.UserProfileProvider.Identity;
import io.yunxi.platform.spi.profile.UserProfileProvider.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 基于 LLM 自由归纳的用户画像演化器，实现 {@link UserProfileEvolver} SPI。
 *
 * <p><b>通用性</b>：身份类别（category）完全由模型从对话中自行判定，代码与配置零硬编码、
 * 零行业词典，切行业只改提示词与模型，不改逻辑。所以「通用」不需要升格为完整的
 * ReActAgent——单次结构化抽取直接走 {@link ModelFactory} 单次调用即可，避免 Agent 推理循环
 * 带来的 token 暴涨。</p>
 *
 * <p><b>省 token（三招）</b>：①抽取走专用快模型（{@code profile.yml} 的
 * {@code yunxi.profile.llm.model}），与对话大模型解耦；②输入走滚动窗口（最近 N 条 /
 * 尾部限长），封住每轮 token 上限，避免每轮重发整段历史的 O(n²) 膨胀；③低温 + 输出 token
 * 硬上限，结果可复现且压住输出成本。</p>
 *
 * <p><b>降级安全网（不可省）</b>：画像是「尽力而为的增强」，超时 / 熔断 / 异常 / 响应不可解析
 * 时一律降级为不更新画像（沿用既有画像），绝不抛异常中断调用方。这一层保证模型抖动不会让
 * evolve 端点 500，是端到端安全的底线。</p>
 */
@Service
public class DefaultUserProfileEvolver implements UserProfileEvolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserProfileEvolver.class);

    private static final String SYSTEM_PROMPT = """
            你是用户画像分析师。阅读用户与助手的对话，从中提取明确的身份信号，增量更新用户画像。
            严格遵守：
            1. 只提取对话中明确陈述的信息，不要推测、不要臆造任何未提及的内容；
            2. 身份类别（category）由你自由判定，例如 姓名 / 职业 / 角色 / 兴趣 / 所在地 / 关系 等，不要局限于固定枚举；
            3. 每条身份给出 name（规范化标识）、displayName（展示名）、category、confidence(0~1 数值)、
               keywords（标签列表）、attributes（任意键值）；
            4. 个人上下文 personalContext 与社会关系 socialRelations 仅记录明确提及的键值；
            5. 对话中没有任何身份信息时，identities 为空数组；
            6. 只输出一个 JSON 对象，不要 markdown 代码块，不要任何解释文字。
            输出格式：
            {"identities":[{"name":"","displayName":"","category":"","confidence":0.0,"keywords":[],"attributes":{}}],
             "personalContext":{},"socialRelations":{}}
            """;

    private final ModelFactory modelFactory;
    private final ObjectMapper objectMapper;
    private final ProfileProperties profile;
    private final AgentModelConfig modelConfig;
    private final long timeoutMs;
    private final CircuitBreaker circuitBreaker;
    private final TimeLimiter timeLimiter;

    public DefaultUserProfileEvolver(ModelFactory modelFactory, ObjectMapper objectMapper, ProfileProperties profile) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
        this.profile = profile;
        this.timeoutMs = Math.max(1L, profile.getLlm().getTimeoutMs());
        // 抽取专用模型 + 低温 + 输出限长，与对话大模型解耦
        this.modelConfig = new AgentModelConfig();
        this.modelConfig.setModelName(profile.getLlm().getModel());
        this.modelConfig.setTemperature(profile.getLlm().getTemperature());
        this.modelConfig.setMaxTokens(profile.getLlm().getMaxTokens());
        this.circuitBreaker = CircuitBreaker.ofDefaults("profileLlm");
        this.timeLimiter = TimeLimiter.of(Duration.ofMillis(timeoutMs));
        log.info("[PROFILE] 画像抽取器就绪：专用模型={}, 温度={}, 输出上限={} tokens, 输入窗口=最近{}条/{}字符, 降级阈值confidence>={}",
                profile.getLlm().getModel(), profile.getLlm().getTemperature(), profile.getLlm().getMaxTokens(),
                profile.getLlm().getMaxWindowMessages(), profile.getLlm().getMaxWindowChars(),
                profile.getLlm().getMinConfidence());
    }

    @Override
    public UserProfile evolve(UserProfile current, List<Msg> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return current;
        }
        String dialogue = formatConversation(conversation);
        if (dialogue.isBlank()) {
            return current;
        }
        ChatResponse response = invokeLlm(dialogue);
        if (response == null) {
            return current;
        }
        String text = extractText(response);
        Extracted extracted = parse(text);
        if (extracted == null) {
            return current;
        }
        return merge(current, extracted);
    }

    /**
     * 调用 LLM 归纳画像，返回收口后的完整响应；任何失败（超时 / 熔断 / 异常）返回 null 降级。
     *
     * <p>走专用抽取模型（{@link #modelConfig}，与对话大模型解耦）；TimeLimiter +
     * CircuitBreaker 双防护，避免模型慢或挂掉拖垮 evolve 端点。</p>
     */
    protected ChatResponse invokeLlm(String dialogue) {
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(SYSTEM_PROMPT).build(),
                Msg.builder().role(MsgRole.USER).textContent(dialogue).build());
        try {
            Callable<ChatResponse> timed = timeLimiter.decorateFutureSupplier(() ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            Model model = modelFactory.create(modelConfig);
                            return accumulate(model.stream(messages, null, null));
                        } catch (Exception e) {
                            throw new CompletionException(e);
                        }
                    }));
            Callable<ChatResponse> guarded = circuitBreaker.decorateCallable(timed);
            return guarded.call();
        } catch (CallNotPermittedException e) {
            log.warn("[PROFILE] LLM 画像抽取熔断打开，跳过本次更新");
            return null;
        } catch (TimeoutException e) {
            log.warn("[PROFILE] LLM 画像抽取超时（{}ms），跳过本次更新", timeoutMs);
            return null;
        } catch (Exception e) {
            log.warn("[PROFILE] LLM 画像抽取异常，跳过本次更新: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将对话消息拼为纯文本，供模型阅读理解。
     *
     * <p>滚动窗口：只取最近 {@code max-window-messages} 条、拼接文本超过
     * {@code max-window-chars} 只保留尾部，封住每次抽取的输入 token 上限，
     * 避免每轮重发整段历史的 O(n²) 膨胀。</p>
     */
    private String formatConversation(List<Msg> conversation) {
        int window = Math.max(1, profile.getLlm().getMaxWindowMessages());
        List<Msg> slice = conversation;
        if (slice.size() > window) {
            slice = slice.subList(slice.size() - window, slice.size());
        }
        StringBuilder sb = new StringBuilder();
        for (Msg msg : slice) {
            String text = msg.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            sb.append(text).append("\n");
        }
        String result = sb.toString().trim();
        int maxChars = Math.max(1, profile.getLlm().getMaxWindowChars());
        if (result.length() > maxChars) {
            result = result.substring(result.length() - maxChars);
        }
        return result;
    }

    /** 将流式增量 ChatResponse 收口为完整响应（与意图分类链路一致的聚合语义）。 */
    private ChatResponse accumulate(Flux<ChatResponse> flux) {
        List<ChatResponse> parts = flux.collectList().block();
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        String id = null;
        ChatUsage usage = null;
        String finishReason = null;
        for (ChatResponse p : parts) {
            if (p.getId() != null) {
                id = p.getId();
            }
            if (p.getUsage() != null) {
                usage = p.getUsage();
            }
            if (p.getFinishReason() != null) {
                finishReason = p.getFinishReason();
            }
            if (p.getContent() != null) {
                for (ContentBlock b : p.getContent()) {
                    if (b instanceof TextBlock tb && tb.getText() != null) {
                        sb.append(tb.getText());
                    }
                }
            }
        }
        List<ContentBlock> content = List.of(TextBlock.builder().text(sb.toString()).build());
        return new ChatResponse(id, content, usage, null, finishReason);
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

    /** 清理 LLM 输出噪声：去思维链标签、去 markdown 围栏、截取首个 { 到末个 } 的 JSON 子串。 */
    private String cleanJson(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        t = t.replaceAll("(?s)<think>.*?</think>", "");
        t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        t = t.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            t = t.substring(start, end + 1);
        }
        return t;
    }

    /** 解析 LLM JSON 响应为结构化抽取结果；任一环节失败返回 null（降级）。 */
    private Extracted parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = cleanJson(raw);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return null;
            }
            List<Identity> identities = new ArrayList<>();
            JsonNode idArr = root.path("identities");
            if (idArr.isArray()) {
                for (JsonNode n : idArr) {
                    String name = n.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    String displayName = n.path("displayName").asText("");
                    if (displayName.isBlank()) {
                        displayName = name;
                    }
                    String category = n.path("category").asText("");
                    if (category.isBlank()) {
                        category = "IDENTITY";
                    }
                    double confidence = n.path("confidence").asDouble(0.6);
                    if (confidence < 0 || confidence > 1) {
                        confidence = 0.6;
                    }
                    // 低于配置阈值的身份不纳入画像（值级兜底，非降级判定）
                    if (confidence < profile.getLlm().getMinConfidence()) {
                        continue;
                    }
                    List<String> keywords = new ArrayList<>();
                    JsonNode kw = n.path("keywords");
                    if (kw.isArray()) {
                        for (JsonNode k : kw) {
                            keywords.add(k.asText());
                        }
                    }
                    Map<String, String> attrs = new HashMap<>();
                    JsonNode at = n.path("attributes");
                    if (at.isObject()) {
                        at.fields().forEachRemaining(e -> attrs.put(e.getKey(), e.getValue().asText()));
                    }
                    identities.add(new Identity(name, displayName, category, confidence, keywords, attrs));
                }
            }
            Map<String, String> personalContext = new HashMap<>();
            JsonNode pc = root.path("personalContext");
            if (pc.isObject()) {
                pc.fields().forEachRemaining(e -> personalContext.put(e.getKey(), e.getValue().asText()));
            }
            Map<String, String> socialRelations = new HashMap<>();
            JsonNode sr = root.path("socialRelations");
            if (sr.isObject()) {
                sr.fields().forEachRemaining(e -> socialRelations.put(e.getKey(), e.getValue().asText()));
            }
            return new Extracted(identities, personalContext, socialRelations);
        } catch (Exception e) {
            log.warn("[PROFILE] LLM 响应解析失败，跳过本次更新: {}", e.getMessage());
            return null;
        }
    }

    /** 将 LLM 抽取结果增量合并进既有画像；同名同类别身份置信度取高者并微抬，否则追加。 */
    private UserProfile merge(UserProfile current, Extracted extracted) {
        String userId = current != null ? current.getUserId() : null;
        List<Identity> identities = new ArrayList<>(
                current != null && current.getIdentities() != null ? current.getIdentities() : List.of());
        Map<String, String> personalContext = new HashMap<>(
                current != null && current.getPersonalContext() != null ? current.getPersonalContext() : Map.of());
        Map<String, String> socialRelations = new HashMap<>(
                current != null && current.getSocialRelations() != null ? current.getSocialRelations() : Map.of());
        for (Identity id : extracted.identities) {
            addOrBump(identities, id);
        }
        personalContext.putAll(extracted.personalContext);
        socialRelations.putAll(extracted.socialRelations);
        return new UserProfile(userId, identities, personalContext, socialRelations, System.currentTimeMillis());
    }

    private void addOrBump(List<Identity> identities, Identity candidate) {
        for (int i = 0; i < identities.size(); i++) {
            Identity ex = identities.get(i);
            if (ex.getName().equals(candidate.getName()) && ex.getCategory().equals(candidate.getCategory())) {
                double conf = Math.min(1.0, Math.max(ex.getConfidence(), candidate.getConfidence()) + 0.1);
                identities.set(i, new Identity(ex.getName(), ex.getDisplayName(), ex.getCategory(),
                        conf, ex.getKeywords(), ex.getAttributes()));
                return;
            }
        }
        identities.add(candidate);
    }

    @Override
    public UserProfile consolidate(UserProfile current) {
        if (current == null) {
            return null;
        }
        List<Identity> kept = new ArrayList<>();
        if (current.getIdentities() != null) {
            for (Identity id : current.getIdentities()) {
                if (id.getConfidence() >= 0.1) {
                    kept.add(id);
                }
            }
        }
        return new UserProfile(current.getUserId(), kept, current.getPersonalContext(),
                current.getSocialRelations(), current.getUpdatedAt());
    }

    /** LLM 抽取的结构化结果载体。 */
    private static final class Extracted {
        final List<Identity> identities;
        final Map<String, String> personalContext;
        final Map<String, String> socialRelations;

        Extracted(List<Identity> identities, Map<String, String> personalContext, Map<String, String> socialRelations) {
            this.identities = identities;
            this.personalContext = personalContext;
            this.socialRelations = socialRelations;
        }
    }
}
