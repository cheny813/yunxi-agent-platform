package io.yunxi.intelligence.muse.tool;

import io.yunxi.intelligence.muse.config.EvolutionConfig;
import io.yunxi.intelligence.muse.engine.SelfEvolutionEngine;
import io.yunxi.intelligence.muse.model.EvolveOutcome;
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
 * 技能目录回退验证：skill_dir 留空（或全空白）时回退到平台 canonical 技能目录（builtin-export-dir 绝对路径），
 * 显式传入时透传。这是「消除 MUSE 写回假生效」的关键兜底——LLM 不必正确传参也能写回框架可自动加载的目录。
 */
@ExtendWith(MockitoExtension.class)
class SkillRefineToolTest {

    @Mock
    private SelfEvolutionEngine engine;
    @Mock
    private EvolutionConfig config;

    @org.mockito.InjectMocks
    private SkillRefineTool tool;

    @Test
    void blankSkillDirFallsBackToConfiguredCanonicalDir() {
        when(config.getBuiltinExportDir()).thenReturn("my/skills");
        EvolveOutcome outcome = mock(EvolveOutcome.class);
        when(outcome.getIterations()).thenReturn(1);
        when(outcome.isSucceeded()).thenReturn(true);
        when(engine.evolve(anyString(), anyString(), anyString())).thenReturn(outcome);

        tool.evolve("s1", "   ", "content");

        ArgumentCaptor<String> dirCaptor = ArgumentCaptor.forClass(String.class);
        verify(engine).evolve(eq("s1"), dirCaptor.capture(), eq("content"));
        assertEquals(Path.of("my/skills").toAbsolutePath().toString(), dirCaptor.getValue(),
                "留空 skill_dir 必须回退到 builtin-export-dir 的绝对路径");
    }

    @Test
    void nullSkillDirFallsBackToConfiguredCanonicalDir() {
        when(config.getBuiltinExportDir()).thenReturn("my/skills");
        EvolveOutcome outcome = mock(EvolveOutcome.class);
        when(outcome.getIterations()).thenReturn(0);
        when(outcome.isSucceeded()).thenReturn(false);
        when(engine.evolve(anyString(), anyString(), anyString())).thenReturn(outcome);

        tool.evolve("s1", null, "content");

        ArgumentCaptor<String> dirCaptor = ArgumentCaptor.forClass(String.class);
        verify(engine).evolve(eq("s1"), dirCaptor.capture(), eq("content"));
        assertEquals(Path.of("my/skills").toAbsolutePath().toString(), dirCaptor.getValue());
    }

    @Test
    void explicitSkillDirIsPassedThrough() {
        EvolveOutcome outcome = mock(EvolveOutcome.class);
        when(outcome.getIterations()).thenReturn(0);
        when(outcome.isSucceeded()).thenReturn(false);
        when(engine.evolve(anyString(), anyString(), anyString())).thenReturn(outcome);

        tool.evolve("s1", "/tmp/explicit-dir", "content");

        verify(engine).evolve(eq("s1"), eq("/tmp/explicit-dir"), eq("content"));
    }
}
