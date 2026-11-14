package io.yunxi.intelligence.muse.tool;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.eval.SkillEvaluator;
import io.yunxi.intelligence.muse.model.EvalReport;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能目录回退验证（评估路径）：skill_dir 留空时回退到平台 canonical 技能目录，显式传入时透传。
 */
@ExtendWith(MockitoExtension.class)
class SkillEvalToolTest {

    @Mock
    private SkillEvaluator evaluator;
    @Mock
    private EvolutionConfig config;

    @InjectMocks
    private SkillEvalTool tool;

    @Test
    void blankSkillDirFallsBackToConfiguredCanonicalDir() {
        when(config.getBuiltinExportDir()).thenReturn("my/skills");
        EvalReport report = mock(EvalReport.class);
        when(report.getSummary()).thenReturn("ok");
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report);

        tool.eval("s1", "  ");

        ArgumentCaptor<String> dirCaptor = ArgumentCaptor.forClass(String.class);
        verify(evaluator).evaluate(eq("s1"), dirCaptor.capture());
        assertEquals(Path.of("my/skills").toAbsolutePath().toString(), dirCaptor.getValue());
    }

    @Test
    void explicitSkillDirIsPassedThrough() {
        EvalReport report = mock(EvalReport.class);
        when(report.getSummary()).thenReturn("ok");
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report);

        tool.eval("s1", "/tmp/explicit-dir");

        verify(evaluator).evaluate(eq("s1"), eq("/tmp/explicit-dir"));
    }
}
