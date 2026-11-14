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
 * <p>本类是「接入骨架」：决策模型经 {@link ModelFactory} 按逻辑 id 解析；当该模型在 classpath / 配置中
 * 不可用，或打分调用 / 输出解析失败时，一律回退到规则式结果（返回空列表），不影响自动选型主体可用。
 * tev1:0.8b 是否真能可靠给出能力画像，属运行期验证项，未通过前默认关闭本精排即可。
 */
public class Telev1FineRankingScorer implements FineRankingScorer {

    private static final Logger log = LoggerFactory.getLogger(Telev1FineRankingScorer.class);

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
        Model decision = modelResolver.resolve(decisionModelId);
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
        decision.stream(List.of(msg), null, GenerateOptions.builder().build())
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
        sb.append("只返回候选模型的逻辑名（每行一个，不要编号、不要解释）。\n\n任务信号：\n");
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
