package io.yunxi.intelligence.muse.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.model.EvalReport;
import io.yunxi.intelligence.muse.sandbox.SandboxRunner;

/**
 * {@link DefaultSkillEvaluator} 单元测试：验证其把评估命令交给沙箱执行、
 * 解析 stdout 汇总，并在沙箱超时（timedOut）时按 {@code maxRetries} 重试。
 * 用 Mockito 替身隔离真实沙箱。
 */
@ExtendWith(MockitoExtension.class)
class DefaultSkillEvaluatorTest {

    @Mock
    private SandboxRunner sandbox;

    private static SandboxRunner.SandboxRunResult result(int exitCode, String stdout, boolean timedOut) {
        return new SandboxRunner.SandboxRunResult(exitCode, stdout, "", timedOut);
    }

    @Test
    void delegatesToSandboxAndParsesStdout() {
        EvolutionConfig config = new EvolutionConfig();
        when(sandbox.run(any(), any(), anyLong()))
                .thenReturn(result(0, "2 passed in 0.10s", false));

        DefaultSkillEvaluator evaluator = new DefaultSkillEvaluator(sandbox, config);
        EvalReport report = evaluator.evaluate("skill", "/dir");

        assertEquals(2, report.getPassed());
        assertTrue(report.isAllPassed());
        verify(sandbox, times(1)).run(any(), any(), anyLong());
    }

    @Test
    void retriesOnTimeoutThenSucceeds() {
        EvolutionConfig config = new EvolutionConfig();
        config.getEvaluator().setMaxRetries(2);
        when(sandbox.run(any(), any(), anyLong()))
                .thenReturn(result(-1, "", true))
                .thenReturn(result(0, "1 passed in 0.10s", false));

        DefaultSkillEvaluator evaluator = new DefaultSkillEvaluator(sandbox, config);
        EvalReport report = evaluator.evaluate("skill", "/dir");

        assertEquals(1, report.getPassed());
        verify(sandbox, times(2)).run(any(), any(), anyLong());
    }

    @Test
    void exhaustsRetriesOnPersistentTimeout() {
        EvolutionConfig config = new EvolutionConfig();
        config.getEvaluator().setMaxRetries(2);
        when(sandbox.run(any(), any(), anyLong())).thenReturn(result(-1, "", true));

        DefaultSkillEvaluator evaluator = new DefaultSkillEvaluator(sandbox, config);
        EvalReport report = evaluator.evaluate("skill", "/dir");

        // 尝试次数 = 0..maxRetries 共 3 次。
        verify(sandbox, times(3)).run(any(), any(), anyLong());
        assertFalse(report.isAllPassed());
    }
}
