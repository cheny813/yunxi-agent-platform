package io.yunxi.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ModelRegistry;
import io.yunxi.capability.ModelResolver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能平面。本地决策模型分类器：经 {@code ModelResolver} 解析决策模型（平台默认
 * {@code defaults.decision} → 本机 Ollama 决策模型），以闭集候选卡片做单标签分类。
 *
 * <p>输出容错：优先以 {@code responseFormat} 约束 JSON，失败则按首个平衡括号对象提取并本地校验；
 * 任何解析失败返回 {@code null}（引擎降级），绝不抛出到入口。调用方语义与精排器一致：
 * temperature=0、有限 token、超时降级。</p>
 */
public class LocalDecisionIntentClassifier implements IntentClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(LocalDecisionIntentClassifier.class);

    private final ModelResolver modelResolver;
    private final String modelId;
    private final long timeoutMillis;
    private final int maxTokens;
    private final int maxInputChars;
    private final boolean structuredOutput;
    private final ObjectMapper mapper;

    /** 构造分类器。 */
    public LocalDecisionIntentClassifier(ModelResolver modelResolver,
            String modelId,
            long timeoutMillis,
            int maxTokens,
            int maxInputChars,
            boolean structuredOutput,
            ObjectMapper mapper) {
        this.modelResolver = modelResolver;
        this.modelId = modelId;
        this.timeoutMillis = timeoutMillis;
        this.maxTokens = maxTokens;
        this.maxInputChars = maxInputChars;
        this.structuredOutput = structuredOutput;
        this.mapper = mapper;
    }

    @Override
    public IntentResolution classify(String normalizedText, Map<String, String> entities, List<AgentCandidate> candidates) {
        Model model = resolveModel();
        if (model == null) {
            LOG.debug("意图分类模型不可解析，modelId={}", modelId);
            return null;
        }
        String prompt = IntentPromptBuilder.build(truncate(normalizedText), entities, candidates);
        String output;
        long startedAt = System.nanoTime();
        if (structuredOutput) {
            try {
                output = invoke(model, prompt, structuredOptions());
            } catch (Exception e) {
                if (System.nanoTime() - startedAt >= timeoutMillis * 1_000_000L) {
                    // 结构化调用已耗尽本次分类预算，重试只会再把预算翻一倍，直接判定为本次不可用。
                    LOG.warn("意图分类模型超出预算未返回，放弃重试：{}", e.getMessage());
                    return null;
                }
                LOG.debug("结构化输出调用失败，改用普通调用重试：{}", e.getMessage());
                try {
                    output = invoke(model, prompt, plainOptions());
                } catch (Exception e2) {
                    LOG.warn("意图分类模型调用失败：{}", e2.getMessage());
                    return null;
                }
            }
        } else {
            try {
                output = invoke(model, prompt, plainOptions());
            } catch (Exception e) {
                LOG.warn("意图分类模型调用失败：{}", e.getMessage());
                return null;
            }
        }
        return parse(output);
    }

    private Model resolveModel() {
        try {
            return modelResolver.resolve(modelId);
        } catch (Exception e) {
            // 解析失败意味着 LLM 意图分类级将持续不可用（引擎恒走关键词降级），必须以 WARN 暴露而非静默。
            LOG.warn("意图分类模型解析失败（model-id={}，检查是否为带 provider 前缀的完整模型 id）：{}",
                    modelId, e.getMessage());
            return null;
        }
    }

    private String invoke(Model model, String prompt, GenerateOptions options) {
        StringBuilder sb = new StringBuilder(256);
        model.stream(
                        List.of(Msg.builder().textContent(prompt).role(io.agentscope.core.message.MsgRole.USER).build()),
                        null,
                        options)
                .doOnNext(resp -> {
                    if (resp.getContent() != null) {
                        resp.getContent().forEach(b -> {
                            if (b instanceof io.agentscope.core.message.TextBlock tb) {
                                sb.append(tb.getText());
                            }
                        });
                    }
                })
                .blockLast(java.time.Duration.ofMillis(timeoutMillis));
        return sb.toString();
    }

    private GenerateOptions structuredOptions() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agentId", Map.of("type", "string"));
        properties.put("label", Map.of("type", "string"));
        properties.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        properties.put("entities", Map.of("type", "object", "additionalProperties", Map.of("type", "string")));
        properties.put("rewrittenQuery", Map.of("type", "string"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("agentId", "label", "confidence"));
        schema.put("properties", properties);
        return GenerateOptions.builder()
                .temperature(0.0d)
                .maxTokens(maxTokens)
                .responseFormat(io.agentscope.core.formatter.ResponseFormat.jsonSchema(
                        io.agentscope.core.formatter.JsonSchema.builder()
                                .name("intent_resolution")
                                .schema(schema)
                                .strict(true)
                                .build()))
                .build();
    }

    private GenerateOptions plainOptions() {
        return GenerateOptions.builder().temperature(0.0d).maxTokens(maxTokens).build();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxInputChars ? text : text.substring(0, maxInputChars);
    }

    /** 解析模型输出；非法输出返回 null。 */
    IntentResolution parse(String output) {
        if (output == null) {
            return null;
        }
        String json = extractFirstJsonObject(output);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode agentIdNode = node.path("agentId");
            if (!agentIdNode.isTextual() || agentIdNode.asText().isBlank()) {
                return null;
            }
            String agentId = agentIdNode.asText();
            String label = node.path("label").asText("");
            double confidence = node.path("confidence").asDouble(0.0d);
            Map<String, String> entities = new LinkedHashMap<>();
            JsonNode entityNode = node.path("entities");
            if (entityNode.isObject()) {
                entityNode.fieldNames().forEachRemaining(name -> {
                    JsonNode v = entityNode.get(name);
                    if (v.isValueNode() && !v.isNull()) {
                        entities.put(name, v.asText());
                    }
                });
            }
            String rewritten = node.path("rewrittenQuery").asText(null);
            if (rewritten != null && rewritten.isBlank()) {
                rewritten = null;
            }
            return new IntentResolution(
                    label.isBlank() ? IntentResolution.UNKNOWN_LABEL : label,
                    confidence,
                    agentId,
                    entities,
                    rewritten,
                    IntentResolution.Source.LLM);
        } catch (Exception e) {
            LOG.debug("意图分类输出解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 提取首个平衡括号 JSON 对象（忽略字符串内的括号与转义）。 */
    static String extractFirstJsonObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                if (inString) {
                    escaped = true;
                }
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }
}
