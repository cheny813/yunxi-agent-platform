package io.yunxi.intelligence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证「内置技能零复制源」可行性：平台随包分发的 {@code builtin-skills/} 资源位于 classpath(jar)，
 * {@link ClasspathSkillRepository} 可直接从 classpath 解析全部内置技能，使 jar 成为内置技能权威真相源，
 * 无需经 {@code .agentscope/workspace/skills} 物理复制作为唯一加载来源。
 *
 * <p>这是内置技能全局零复制接线的基石验证：证明零复制源可用。全局接线已由 {@code AgentConfigurer}
 * 经 {@code builder.skillRepository(new ClasspathSkillRepository("builtin-skills"))} 完成（Layer2）；
 * 框架 {@code HarnessSkillMiddleware.mergeRepositories} 同名校验后者胜出，MUSE（Layer3）天然覆盖内置（Layer2），
 * 无需重指 MUSE 写路径。
 */
class ClasspathSkillRepositoryResolutionTest {

    @Test
    void builtinSkillsResolveFromClasspath() throws IOException {
        ClasspathSkillRepository repo = new ClasspathSkillRepository("builtin-skills");
        List<String> names = repo.getAllSkillNames();
        assertFalse(names.isEmpty(), "builtin-skills 应在 classpath 上解析出内置技能");
        assertTrue(names.contains("code-reviewer"), "应包含内置技能 code-reviewer，实际：" + names);
        assertTrue(names.contains("api-developer"), "应包含内置技能 api-developer，实际：" + names);
    }
}
