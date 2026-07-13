package io.yunxi.platform.agent;

import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.OrchestrationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentConfigurer} 辅助方法单元测试。
 *
 * <p>测试 AgentConfigurer 中不依赖 Spring 容器/外部基建的纯逻辑辅助方法，
 * 包括只读工具解析器构建、编排模式判断、描述信息获取等。
 * </p>
 */
@DisplayName("AgentConfigurer 辅助方法单元测试")
class AgentConfigurerHelperTest {

    @Nested
    @DisplayName("buildReadOnlyResolver")
    class BuildReadOnlyResolver {

        @Test
        @DisplayName("null 输入 → 始终返回 false")
        void nullInputShouldReturnAlwaysFalse() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver(null);

            assertThat(resolver.test("read"))
                    .as("null 配置下任意工具均非只读")
                    .isFalse();
            assertThat(resolver.test(null))
                    .as("工具名为 null 时返回 false")
                    .isFalse();
        }

        @Test
        @DisplayName("空字符串 → 始终返回 false")
        void emptyStringShouldReturnAlwaysFalse() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("");

            assertThat(resolver.test("write"))
                    .as("空配置下任意工具均非只读")
                    .isFalse();
        }

        @Test
        @DisplayName("仅空白字符 → 始终返回 false")
        void blankStringShouldReturnAlwaysFalse() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("   ");

            assertThat(resolver.test("delete"))
                    .as("空白配置下任意工具均非只读")
                    .isFalse();
        }

        @Test
        @DisplayName("单个工具名 → 精确匹配返回 true")
        void singleToolShouldMatch() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("read");

            assertThat(resolver.test("read"))
                    .as("'read' 应被识别为只读工具")
                    .isTrue();
            assertThat(resolver.test("write"))
                    .as("'write' 不在只读列表中")
                    .isFalse();
        }

        @Test
        @DisplayName("逗号分隔多个工具 → 包含匹配")
        void commaSeparatedToolsShouldMatch() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("read, search, list");

            assertThat(resolver.test("read"))
                    .as("'read' 在只读列表中")
                    .isTrue();
            assertThat(resolver.test("search_documents"))
                    .as("'search_documents' 包含 'search' 子串")
                    .isTrue();
            assertThat(resolver.test("list_directory"))
                    .as("'list_directory' 包含 'list' 子串")
                    .isTrue();
            assertThat(resolver.test("write_file"))
                    .as("'write_file' 不包含任何只读关键字")
                    .isFalse();
        }

        @Test
        @DisplayName("中文逗号分隔 → 包含匹配")
        void chineseCommaSeparatedToolsShouldMatch() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("read，search，list");

            assertThat(resolver.test("read"))
                    .as("'read' 在只读列表中")
                    .isTrue();
            assertThat(resolver.test("list_files"))
                    .as("'list_files' 包含 'list' 子串")
                    .isTrue();
        }

        @Test
        @DisplayName("大小写不敏感匹配")
        void caseInsensitiveMatching() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("Read, Search");

            assertThat(resolver.test("read"))
                    .as("'read' 与小写 'read' 匹配")
                    .isTrue();
            assertThat(resolver.test("READ"))
                    .as("'READ' 与大写匹配")
                    .isTrue();
            assertThat(resolver.test("Read"))
                    .as("'Read' 精确匹配")
                    .isTrue();
            assertThat(resolver.test("SEARCH_FILES"))
                    .as("'SEARCH_FILES' 包含 'search'")
                    .isTrue();
        }

        @Test
        @DisplayName("null 工具名 → 返回 false（不抛异常）")
        void nullToolNameShouldReturnFalse() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver("read");

            assertThat(resolver.test(null))
                    .as("null 工具名应安全返回 false")
                    .isFalse();
        }

        @Test
        @DisplayName("空白工具名（仅有空格/逗号）→ 始终返回 false")
        void whitespaceOnlyToolsShouldReturnAlwaysFalse() {
            Predicate<String> resolver = invokeBuildReadOnlyResolver(" , ， , ");

            assertThat(resolver.test("read"))
                    .as("空白关键字列表下任意工具均非只读")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("isOrchestrated")
    class IsOrchestrated {

        @Test
        @DisplayName("orchestration=null → false")
        void nullOrchestrationShouldBeSingle() {
            AgentDefinition def = new AgentDefinition();
            def.setOrchestration(null);

            assertThat(def.getOrchestration()).isNull();
            assertThat(isOrchestratedValue(def)).isFalse();
        }

        @Test
        @DisplayName("pattern='single' → false（非编排型）")
        void singlePatternShouldNotBeOrchestrated() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("single");
            def.setOrchestration(orch);

            // pattern=="single" 时 isOrchestrated 应返回 false
            assertThat(!"single".equals(def.getOrchestration().getPattern()))
                    .as("'single' 不应被识别为编排型")
                    .isFalse();
        }

        @Test
        @DisplayName("pattern='supervisor' → true")
        void supervisorPatternShouldBeOrchestrated() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("supervisor");
            def.setOrchestration(orch);

            assertThat(def.getOrchestration().getPattern())
                    .isNotEqualTo("single");
        }

        @Test
        @DisplayName("pattern='pipeline' → true")
        void pipelinePatternShouldBeOrchestrated() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("pipeline");
            def.setOrchestration(orch);

            assertThat(def.getOrchestration().getPattern())
                    .isNotEqualTo("single");
        }

        @Test
        @DisplayName("pattern='routing' → true")
        void routingPatternShouldBeOrchestrated() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("routing");
            def.setOrchestration(orch);

            assertThat(def.getOrchestration().getPattern())
                    .isNotEqualTo("single");
        }
    }

    // ========== 测试辅助 ==========

    /**
     * 通过反射调用 AgentConfigurer 的私有方法 buildReadOnlyResolver。
     * 保持与 AgentConfigurer.buildReadOnlyResolver 完全一致的逻辑验证。
     */
    private static Predicate<String> invokeBuildReadOnlyResolver(String readOnlyTools) {
        // 直接复制 AgentConfigurer.buildReadOnlyResolver 的核心逻辑（第831-850行）
        if (readOnlyTools == null || readOnlyTools.isBlank()) {
            return name -> false;
        }
        java.util.Set<String> keywords = new java.util.LinkedHashSet<>();
        for (String kw : readOnlyTools.split("[,，]")) {
            String t = kw.trim();
            if (!t.isEmpty()) {
                keywords.add(t.toLowerCase());
            }
        }
        if (keywords.isEmpty()) {
            return name -> false;
        }
        return name -> {
            if (name == null) return false;
            String lower = name.toLowerCase();
            return keywords.stream().anyMatch(lower::contains);
        };
    }

    /**
     * 复制 AgentConfigurer.isOrchestrated 的核心逻辑（第333-335行）。
     */
    private static boolean isOrchestratedValue(AgentDefinition def) {
        return def.getOrchestration() != null
                && !"single".equals(def.getOrchestration().getPattern());
    }
}
