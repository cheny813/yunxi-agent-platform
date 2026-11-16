package io.yunxi.intelligence.muse.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.eval.SkillEvaluator;
import io.yunxi.intelligence.muse.model.EvalReport;
import io.yunxi.intelligence.muse.model.EvolveOutcome;
import io.yunxi.intelligence.muse.prune.SkillPruner;
import io.yunxi.intelligence.muse.refine.SkillRefiner;

/**
 * {@link SelfEvolutionEngine} 单元测试：用 Mockito 替身隔离评估器 / 精炼器 / 剪枝器，
 * 验证「首次即全过」「无进展早停」「达到最大迭代上限」三条主路径的迭代次数、
 * 评估/精炼调用次数与最终成败判定。
 */
@ExtendWith(MockitoExtension.class)
class SelfEvolutionEngineTest {

    @Mock
    private SkillEvaluator evaluator;

    @Mock
    private SkillRefiner refiner;

    @Mock
    private SkillPruner pruner;

    private static EvalReport report(int passed, int failed) {
        EvalReport r = new EvalReport();
        r.setTotal(passed + failed);
        r.setPassed(passed);
        r.setFailed(failed);
        r.setPassRate((passed + failed) == 0 ? 0.0d
                : (double) passed / (passed + failed));
        return r;
    }

    @Test
    void succeedsOnFirstIterationWithoutRefining() {
        EvolutionConfig config = new EvolutionConfig();
        config.getRefine().setMaxIterations(3);
        config.getRefine().setStopOnNoProgress(true);
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report(2, 0));

        SelfEvolutionEngine engine = new SelfEvolutionEngine(evaluator, refiner, pruner, config);
        EvolveOutcome outcome = engine.evolve("skill", "/dir", "initial");

        assertTrue(outcome.isSucceeded());
        assertEquals(1, outcome.getIterations());
        assertEquals("initial", outcome.getFinalContent());
        verify(evaluator, times(1)).evaluate(anyString(), anyString());
        verify(refiner, never()).refine(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void stopsEarlyWhenPassRateDoesNotImprove() {
        EvolutionConfig config = new EvolutionConfig();
        config.getRefine().setMaxIterations(5);
        config.getRefine().setStopOnNoProgress(true);
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report(1, 1));
        when(refiner.refine(anyString(), anyString(), any(), anyInt())).thenReturn("refined");
        when(refiner.persist(any(), any(), any())).thenReturn(true);

        SelfEvolutionEngine engine = new SelfEvolutionEngine(evaluator, refiner, pruner, config);
        EvolveOutcome outcome = engine.evolve("skill", "/dir", "initial");

        assertFalse(outcome.isSucceeded());
        // 第 1 轮 0.5 优于基线 -1 继续；第 2 轮 0.5 未优于 0.5 触发早停。
        assertEquals(2, outcome.getIterations());
        verify(evaluator, times(2)).evaluate(anyString(), anyString());
        verify(refiner, times(1)).refine(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void capsAtMaxIterationsWithoutProgressGate() {
        EvolutionConfig config = new EvolutionConfig();
        config.getRefine().setMaxIterations(2);
        config.getRefine().setStopOnNoProgress(false);
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report(1, 1));
        when(refiner.refine(anyString(), anyString(), any(), anyInt())).thenReturn("refined");
        when(refiner.persist(any(), any(), any())).thenReturn(true);

        SelfEvolutionEngine engine = new SelfEvolutionEngine(evaluator, refiner, pruner, config);
        EvolveOutcome outcome = engine.evolve("skill", "/dir", "initial");

        assertFalse(outcome.isSucceeded());
        assertEquals(2, outcome.getIterations());
        verify(evaluator, times(2)).evaluate(anyString(), anyString());
        verify(refiner, times(2)).refine(anyString(), anyString(), any(), anyInt());
    }
}
