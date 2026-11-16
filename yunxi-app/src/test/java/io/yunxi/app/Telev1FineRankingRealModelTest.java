package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.model.ModelRegistry;
import io.yunxi.capability.FineRankingScorer;
import io.yunxi.capability.TaskSignals;
import io.yunxi.capability.Telev1FineRankingScorer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 自动选型精排在真实本地决策模型（Ollama）上的端到端验证：解析（provider 前缀）、
 * 思考关闭（{@code think=false}，否则思考型模型回答不落在 content 内）、
 * 单名偏好输出经补位语义成为完整候选序列。
 *
 * <p>仅当本机 Ollama 可达时运行；显式开启：</p>
 * <pre>
 *   mvn -o -pl yunxi-app test -Dyunxi.tev1.it=true -Dtest=Telev1FineRankingRealModelTest \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * 决策模型 id 可用 {@code -Dyunxi.tev1.model-id=} 覆盖（默认 {@code ollama:tev1:0.8b}，需带 provider 前缀）。
 */
@EnabledIfSystemProperty(named = "yunxi.tev1.it", matches = "true")
class Telev1FineRankingRealModelTest {

    private static final String MODEL_ID = System.getProperty("yunxi.tev1.model-id", "ollama:tev1:0.8b");
    private static final long TIMEOUT_MS = 8_000L;

    @BeforeAll
    static void requireLocalOllama() {
        Assumptions.assumeTrue(ollamaReachable(), "local Ollama unreachable, skip");
    }

    @Test
    @DisplayName("真实决策模型下精排产出完整候选序列且在预算内返回")
    void fineRankingProducesFullOrderedCandidateList() {
        FineRankingScorer scorer = new Telev1FineRankingScorer(ModelRegistry::resolve, MODEL_ID, TIMEOUT_MS);
        List<String> ruleOrder = List.of("qwen3.8-flash", "qwen3.8-omni-flash", "qwen-mt-uni");
        TaskSignals signals =
                new TaskSignals(TaskSignals.MediaType.TEXT, 120, false, "把这句话翻译成英文：今天天气很好。");

        long startedAt = System.nanoTime();
        List<String> ranked = scorer.rank("auto-agent", signals, ruleOrder);
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse(ranked.isEmpty(), "决策模型可达且路径打通时，精排不得回退为空");
        assertEquals(ruleOrder.size(), ranked.size(), "重排不得丢失候选");
        assertTrue(new HashSet<>(ranked).containsAll(ruleOrder), "重排不得引入候选外模型");
        assertTrue(elapsedMillis < TIMEOUT_MS, "精排应在超时预算内返回，实际 " + elapsedMillis + "ms");
    }

    private static boolean ollamaReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 11434), 2_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
