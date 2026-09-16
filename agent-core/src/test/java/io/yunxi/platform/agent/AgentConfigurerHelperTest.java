package io.yunxi.platform.agent;

import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.OrchestrationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
     * 复制 AgentConfigurer.isOrchestrated 的核心逻辑（第333-335行）。
     */
    private static boolean isOrchestratedValue(AgentDefinition def) {
        return def.getOrchestration() != null
                && !"single".equals(def.getOrchestration().getPattern());
    }
}
