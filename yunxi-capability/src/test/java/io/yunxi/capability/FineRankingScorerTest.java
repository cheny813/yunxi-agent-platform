package io.yunxi.capability;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.yunxi.capability.ModelResolver;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FineRankingScorerTest {

    private final TaskSignals signals = new TaskSignals(TaskSignals.MediaType.TEXT, 0, false, null);
    private final List<String> candidates = List.of("qwen3.8-flash", "qwen3.8-omni-flash", "qwen-mt-uni");

    @Test
    void ruleBasedReturnsCandidatesUnchanged() {
        FineRankingScorer scorer = new RuleBasedFineRankingScorer();
        assertEquals(candidates, scorer.rank("auto-agent", signals, candidates));
    }

    @Test
    void telev1DegradesToEmptyWhenDecisionModelAbsent() {
        ModelResolver missing = modelId -> null;
        FineRankingScorer scorer = new Telev1FineRankingScorer(missing, "tev1:0.8b");
        assertTrue(scorer.rank("auto-agent", signals, candidates).isEmpty());
    }

    @Test
    void telev1ReordersByDecisionModelOutputAndKeepsAllCandidates() {
        // 决策模型给出与规则式不同的偏好顺序，并要求末尾补回未被提及的候选，保证不丢候选。
        ModelResolver factory = modelId -> new StubDecisionModel("qwen-mt-uni\nqwen3.8-omni-flash");
        FineRankingScorer scorer = new Telev1FineRankingScorer(factory, "tev1:0.8b");
        List<String> ranked = scorer.rank("auto-agent", signals, candidates);
        assertEquals(List.of("qwen-mt-uni", "qwen3.8-omni-flash", "qwen3.8-flash"), ranked);
    }

    @Test
    void telev1DegradesToEmptyOnModelFailure() {
        ModelResolver failing = modelId -> new FailingDecisionModel();
        FineRankingScorer scorer = new Telev1FineRankingScorer(failing, "tev1:0.8b");
        assertTrue(scorer.rank("auto-agent", signals, candidates).isEmpty());
    }

    private static final class StubDecisionModel implements Model {
        private final String text;

        StubDecisionModel(String text) {
            this.text = text;
        }

        @Override
        public String getModelName() {
            return "tev1:0.8b";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            TextBlock tb = TextBlock.builder().text(text).build();
            return Flux.just(new ChatResponse("r1", List.of(tb), null, null, "stop"));
        }
    }

    private static final class FailingDecisionModel implements Model {
        @Override
        public String getModelName() {
            return "tev1:0.8b";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.error(new RuntimeException("decision model unreachable"));
        }
    }
}
