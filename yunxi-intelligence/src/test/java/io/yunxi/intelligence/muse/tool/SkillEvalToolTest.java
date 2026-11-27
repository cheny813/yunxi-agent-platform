package io.yunxi.intelligence.muse.tool;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.eval.SkillEvaluator;
import io.yunxi.intelligence.muse.model.EvalReport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void blankSkillDirResolvesToSkillSubdirWhenItExists(@TempDir Path tempDir) throws IOException {
        // 缺陷修复：测试命令的相对路径（如 java tests/Xxx.java）以技能目录为 cwd 执行，
        // 目录必须解析到 <canonical>/<skill_name> 子目录；回退到 canonical 根时根下没有 tests/，
        // java 源文件模式找不到文件即按类名加载，报 ClassNotFoundException（实证事故）。
        Path canonical = tempDir.resolve("skills");
        Files.createDirectories(canonical.resolve("s1"));
        when(config.getBuiltinExportDir()).thenReturn(canonical.toString());
        EvalReport report = mock(EvalReport.class);
        when(report.getSummary()).thenReturn("ok");
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report);

        tool.eval("s1", "  ");

        verify(evaluator).evaluate(eq("s1"),
                eq(canonical.resolve("s1").toAbsolutePath().toString()));
    }

    @Test
    void pathTraversalSkillNameNeverEscapesCanonicalDir(@TempDir Path tempDir) throws IOException {
        // skill_name 来自 LLM 工具参数，含路径分隔符/穿越序列时不得参与目录解析（防逃逸），
        // 直接回退到 canonical 根（与目录不存在同一兜底行为）。
        Path canonical = tempDir.resolve("skills");
        Files.createDirectories(canonical);
        Files.createDirectories(tempDir.resolve("evil"));
        when(config.getBuiltinExportDir()).thenReturn(canonical.toString());
        EvalReport report = mock(EvalReport.class);
        when(report.getSummary()).thenReturn("ok");
        when(evaluator.evaluate(anyString(), anyString())).thenReturn(report);

        tool.eval("../evil", "  ");

        verify(evaluator).evaluate(eq("../evil"),
                eq(canonical.toAbsolutePath().toString()));
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
