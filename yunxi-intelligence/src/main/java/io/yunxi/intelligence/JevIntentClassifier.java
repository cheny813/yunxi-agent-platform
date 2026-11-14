package io.yunxi.intelligence;

import io.agentscope.extensions.judge.jev.Answer;
import io.agentscope.extensions.judge.jev.ChoiceAnswer;
import io.agentscope.extensions.judge.jev.ChoiceQuestion;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.SystemOneRequest;
import io.agentscope.extensions.judge.jev.SystemOneResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 智能平面。JEV 云端判定分类器（可选增强）：复用框架 JEV 契约（{@link ChoiceQuestion}
 * 闭集单选 + 概率分布），以候选 Agent 卡片为选项做类型化语义判定。
 *
 * <p>仅在 {@code classifier.impl=jev} 且云端 API Key（TYPESAFE_API_KEY / JEV_API_KEY）可用时装配；
 * 任何失败（网络 / 超时 / 输出越出候选闭集）都委托本地决策模型分类器，再由引擎按
 * 「关键词 → 默认」继续降级，绝不抛出到入口。</p>
 */
public class JevIntentClassifier implements IntentClassifier {

    private static final Logger LOG = LoggerFactory.getLogger(JevIntentClassifier.class);

    private static final String QUESTION_ID = "intent";
    private static final String UNKNOWN_OPTION = "unknown";

    private final JevClient client;
    private final IntentClassifier fallback;

    /** 构造分类器；client 为 null 时恒走 fallback（如云端 Key 缺失的降级装配）。 */
    public JevIntentClassifier(JevClient client, IntentClassifier fallback) {
        this.client = client;
        this.fallback = fallback;
    }

    @Override
    public IntentResolution classify(String normalizedText, Map<String, String> entities,
            List<AgentCandidate> candidates) {
        if (client == null || fallback == null || candidates == null || candidates.isEmpty()) {
            return fallback == null ? null : fallback.classify(normalizedText, entities, candidates);
        }
        try {
            Map<String, Object> criteria = new LinkedHashMap<>();
            for (AgentCandidate candidate : candidates) {
                criteria.put(candidate.agentId(), candidate.card());
            }
            criteria.put(UNKNOWN_OPTION, "以上候选都不能承接该请求");
            SystemOneRequest request = SystemOneRequest.builder()
                    .question(QUESTION_ID, new ChoiceQuestion(instructions(normalizedText, entities), criteria))
                    .build();
            SystemOneResult result = client.systemOneBlocking(request);
            Answer answer = result == null ? null : result.answers().get(QUESTION_ID);
            if (!(answer instanceof ChoiceAnswer choice) || choice.choice() == null || choice.choice().isBlank()) {
                LOG.debug("JEV 返回缺少意图问题的有效选择答案，回落本地决策模型");
                return fallback.classify(normalizedText, entities, candidates);
            }
            String picked = choice.choice();
            double confidence = confidence(choice, picked);
            if (UNKNOWN_OPTION.equals(picked)) {
                return new IntentResolution(IntentResolution.UNKNOWN_LABEL, confidence,
                        IntentResolution.UNKNOWN_AGENT_ID, Map.of(), null, IntentResolution.Source.LLM);
            }
            AgentCandidate pickedCandidate = candidates.stream()
                    .filter(candidate -> candidate.agentId().equals(picked))
                    .findFirst()
                    .orElse(null);
            if (pickedCandidate == null) {
                LOG.debug("JEV 选择越出候选闭集：{}", picked);
                return fallback.classify(normalizedText, entities, candidates);
            }
            String label = pickedCandidate.name() == null || pickedCandidate.name().isBlank()
                    ? IntentResolution.UNKNOWN_LABEL
                    : pickedCandidate.name();
            return new IntentResolution(label, confidence, pickedCandidate.agentId(),
                    Map.of(), null, IntentResolution.Source.LLM);
        } catch (Exception e) {
            LOG.warn("JEV 云端判定失败，回落本地决策模型：{}", e.getMessage());
            return fallback.classify(normalizedText, entities, candidates);
        }
    }

    private static String instructions(String normalizedText, Map<String, String> entities) {
        StringBuilder instructions = new StringBuilder(
                "从候选闭集中选出最能承接用户请求的 Agent；所有候选都不合适时选择 unknown。用户请求：");
        instructions.append(normalizedText == null ? "" : normalizedText);
        if (entities != null && !entities.isEmpty()) {
            instructions.append("。已抽取实体：").append(entities);
        }
        return instructions.toString();
    }

    private static double confidence(ChoiceAnswer choice, String picked) {
        if (choice.confidence() != null) {
            return choice.confidence();
        }
        Map<String, Double> probabilities = choice.probabilities();
        return probabilities == null ? 0.0d : probabilities.getOrDefault(picked, 0.0d);
    }
}
