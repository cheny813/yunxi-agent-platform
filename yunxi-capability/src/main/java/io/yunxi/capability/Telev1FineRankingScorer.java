package io.yunxi.capability;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于本地决策模型（tev1，约 0.8B）的自动选型精排器骨架。
 *
 * <p>默认不装配（由 {@code RuntimeConfiguration} 上的 {@code @ConditionalOnProperty} 控制）。启用后，
 * 在规则式粗筛得到候选序列的基础上，把任务信号与候选交给本地决策模型打分，按其返回的偏好顺序对候选重排，
 * 从而在「能力满足 + 成本最低」之上叠加任务级能力画像。
 *
 * <p>本类是「接入骨架」：决策模型经 {@link ModelResolver} 解析（生产装配为框架 ModelRegistry 的
 * {@code provider:model} 形式 id）；当该模型不可解析 / 不可用，或打分调用 / 输出解析失败时，
 * 一律回退到规则式结果（返回空列表），不影响自动选型主体可用。
 * <p>两类已知行为约束：思考型决策模型必须显式关闭思考（请求带 {@code think=false}），
 * 否则回答不落在 content 内、解析恒为空；决策模型若只返回首个偏好（不产出完整排序），解析语义为「把该模型
 * 提到最前、其余保持规则式顺序」——完整排序能力随决策模型升级而增强，本类无需改动。
 */
public class Telev1FineRankingScorer implements FineRankingScorer {

    private static final Logger log = LoggerFactory.getLogger(Telev1FineRankingScorer.class);

    /** 精排输出只是若干行逻辑名，给足余量同时封顶，防止思考型决策模型把预算耗在推理文本上。 */
    private static final int MAX_OUTPUT_TOKENS = 256;

    private final ModelResolver modelResolver;
    private final String decisionModelId;
    private final long timeoutMillis;

    /** 以默认 5 秒超时构造精排器。 */
    public Telev1FineRankingScorer(ModelResolver modelResolver, String decisionModelId) {
        this(modelResolver, decisionModelId, 5_000L);
    }

    /** 注入决策模型解析器、决策模型逻辑 id 与调用超时（毫秒）。 */
    public Telev1FineRankingScorer(ModelResolver modelResolver, String decisionModelId, long timeoutMillis) {
        this.modelResolver = modelResolver;
        this.decisionModelId = decisionModelId;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<String> rank(String agentId, TaskSignals signals, List<String> ruleBasedCandidates) {
        if (ruleBasedCandidates == null || ruleBasedCandidates.size() <= 1) {
            // 0 或 1 个候选时无需重排。
            return ruleBasedCandidates;
        }
        Model decision;
        try {
            decision = modelResolver.resolve(decisionModelId);
        } catch (Exception e) {
            log.debug("本地决策模型[{}]解析失败，精排回退规则式（agent={}）：{}", decisionModelId, agentId, e.toString());
            return List.of();
        }
        if (decision == null) {
            log.debug("本地决策模型[{}]未配置，精排回退规则式（agent={}）", decisionModelId, agentId);
            return List.of();
        }
        String text;
        try {
            text = invoke(decision, buildPrompt(signals, ruleBasedCandidates));
        } catch (Exception e) {
            log.warn("本地决策模型精排调用失败，回退规则式（agent={}）：{}", agentId, e.toString());
            return List.of();
        }
        return parseRanking(text, ruleBasedCandidates);
    }

    private String invoke(Model decision, String prompt) {
        Msg msg = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
        StringBuilder sb = new StringBuilder();
        // thinking=false：决策模型多为思考型，思考内容不落在 content 内会让解析恒为空；
        // 该参数经提供方转换为 Ollama 的 "think": false 请求字段，不识别它的提供方会忽略，无副作用。
        // temperature=0 + 输出上限：精排只回几行逻辑名，防止长思考挤占输出预算。
        GenerateOptions options = GenerateOptions.builder()
                .temperature(0.0)
                .maxTokens(MAX_OUTPUT_TOKENS)
                .additionalBodyParam("think", false)
                .build();
        decision.stream(List.of(msg), null, options)
                .doOnNext(resp -> appendText(resp, sb))
                .blockLast(Duration.ofMillis(timeoutMillis));
        return sb.toString();
    }

    private void appendText(ChatResponse resp, StringBuilder sb) {
        if (resp == null || resp.getContent() == null) {
            return;
        }
        for (var block : resp.getContent()) {
            if (block instanceof TextBlock tb) {
                sb.append(tb.getText());
            }
        }
    }

    /** 把任务信号与候选组织成决策模型的打分提示词（候选有序给出，要求按偏好每行返回一个逻辑名）。 */
    private String buildPrompt(TaskSignals signals, List<String> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是模型选型精排器。给定任务信号与一个候选模型列表，请按「最适合该任务」到「最不适合」的顺序，");
        sb.append("只返回候选模型的逻辑名（每行一个，不要编号、不要解释）。");
        sb.append("若无法确定完整顺序，至少在第一行返回最适合的模型。\n\n任务信号：\n");
        sb.append("- 媒体类型：").append(signals.mediaType()).append("\n");
        sb.append("- 估算 token：").append(signals.estimatedTokens()).append("\n");
        sb.append("- 工具密集：").append(signals.toolHeavy()).append("\n");
        if (signals.userIntent() != null) {
            sb.append("- 用户意图：").append(signals.userIntent()).append("\n");
        }
        sb.append("\n候选模型（逻辑名）：\n");
        for (String c : candidates) {
            sb.append("- ").append(c).append("\n");
        }
        return sb.toString();
    }

    /** 解析决策模型输出：按出现顺序收集属于候选集的逻辑名，未被提及的候选按原顺序补到末尾，保证不丢候选。 */
    private List<String> parseRanking(String text, List<String> candidates) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> valid = Set.copyOf(candidates);
        List<String> ordered = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String name = line.replaceFirst("^\\s*[-\\d.\\）、)}\\s]+", "").trim();
            if (!name.isBlank() && valid.contains(name) && !ordered.contains(name)) {
                ordered.add(name);
            }
        }
        for (String c : candidates) {
            if (!ordered.contains(c)) {
                ordered.add(c);
            }
        }
        return ordered.isEmpty() ? List.of() : ordered;
    }
}
