package io.yunxi.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.spring.boot.agui.common.AguiProperties;
import io.yunxi.app.RuntimeConfiguration;
import io.yunxi.runtime.AgentConfigurer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.PropertiesPropertySource;

/**
 * builtin-export-dir 覆盖场景对齐的集成验收：验证 {@code yunxi.muse.builtin-export-dir} 经 {@code @Value} 注入
 * {@link AgentConfigurer} 后，{@code resolveWorkspaceRoot()} 正确地把框架默认工作区根重定向到覆盖目录的父级
 * （Layer 4 固定加载 ${root}/skills），从而让内建 / MUSE 写目录与框架装载目录对齐、无双加载。
 *
 * <p>默认配置（.agentscope/workspace/skills）框架已物理对齐，{@code resolveWorkspaceRoot()} 返回 null（零代码）；
 * 覆盖到 {@code <root>/skills} 时返回 {@code <root>}。本测试在真实 Spring 装配边界上验证该接线，无需真实大模型。</p>
 *
 * <p>说明：框架实际从该目录装载技能的「写→载」闭环验证（需真实 Agent + LLM）见 {@code FullStackWebE2eTest}
 * 的 {@code -Dreal.llm=true} 通道，本测试只验证 builtin-export-dir 覆盖场景的对齐决策接线，是该闭环的前置不变量。</p>
 */
class BuiltinExportDirAlignmentE2eTest {

    private static AnnotationConfigApplicationContext contextWith(String exportDir) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        java.util.Properties props = new java.util.Properties();
        // 最小模型 catalog：满足 auto 候选池与 defaults 校验（同 ReactorWiringTest）。
        props.setProperty("yunxi.models.catalog.test-llm.provider", "dashscope");
        props.setProperty("yunxi.models.catalog.test-llm.model", "test-llm");
        props.setProperty("yunxi.models.catalog.test-llm.type", "llm");
        props.setProperty("yunxi.models.catalog.test-llm.cost-tier", "cheap");
        props.setProperty("yunxi.models.defaults.llm", "test-llm");
        if (exportDir != null) {
            props.setProperty("yunxi.muse.builtin-export-dir", exportDir);
        }
        ctx.getEnvironment().getPropertySources().addFirst(new PropertiesPropertySource("testCatalog", props));
        ctx.register(RuntimeConfiguration.class);
        // 裸上下文无框架 AG-UI starter 自动装配，手工补 AguiProperties（默认值即可满足过滤器注册）。
        ctx.registerBean(AguiProperties.class);
        ctx.refresh();
        return ctx;
    }

    private static Path resolveWorkspaceRoot(AgentConfigurer configurer) throws Exception {
        Method m = AgentConfigurer.class.getDeclaredMethod("resolveWorkspaceRoot");
        m.setAccessible(true);
        return (Path) m.invoke(configurer);
    }

    @Test
    void defaultExportDir_noAlignment() throws Exception {
        try (AnnotationConfigApplicationContext ctx = contextWith(null)) {
            AgentConfigurer configurer = ctx.getBean(AgentConfigurer.class);
            assertNull(resolveWorkspaceRoot(configurer),
                    "默认配置框架 Layer 4 已物理对齐，resolveWorkspaceRoot 应返回 null（零代码）");
        }
    }

    @Test
    void overrideExportDirEndingWithSkills_redirectsToParent() throws Exception {
        Path parent = Files.createTempDirectory("yunxi-alt");
        Path skillsDir = parent.resolve("skills");
        try (AnnotationConfigApplicationContext ctx = contextWith(skillsDir.toString())) {
            AgentConfigurer configurer = ctx.getBean(AgentConfigurer.class);
            assertEquals(parent.toAbsolutePath(), resolveWorkspaceRoot(configurer),
                    "覆盖到 <root>/skills 时，工作区根应重定向到 <root>，使框架 Layer 4 与 MUSE 写目录对齐");
        }
    }
}
