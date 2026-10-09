package io.yunxi.platform.agent;

import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AgentConfigurer} 辅助方法单元测试。
 *
 * <p>测试 AgentConfigurer 中不依赖 Spring 容器/外部基建的纯逻辑辅助方法，
 * 包括编排模式判断、描述信息获取等。</p>
 *
 * <p>本测试直接调用真实方法，不复制其逻辑（单一真相源，禁止重复实现 / 复制逻辑）。</p>
 */
@DisplayName("AgentConfigurer 辅助方法单元测试")
class AgentConfigurerHelperTest {

    @Nested
    @DisplayName("isOrchestrated（运行期不再按 pattern 分支，Supervisor 由 experts 决定）")
    class IsOrchestrated {

        @Test
        @DisplayName("orchestration=null → false")
        void nullOrchestrationShouldBeSingle() {
            AgentDefinition def = new AgentDefinition();
            def.setOrchestration(null);
            assertThat(AgentConfigurer.isOrchestrated(def)).isFalse();
        }

        @Test
        @DisplayName("pattern='single' 且无 experts → false")
        void singlePatternWithoutExpertsShouldBeSingle() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("single");
            def.setOrchestration(orch);
            assertThat(AgentConfigurer.isOrchestrated(def)).isFalse();
        }

        @Test
        @DisplayName("pattern='supervisor' 且含 experts → true（运行期构建 Supervisor 实例）")
        void supervisorPatternWithExpertsShouldBeOrchestrated() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("supervisor");
            orch.setExperts(List.of(new ExpertConfig()));
            def.setOrchestration(orch);
            assertThat(AgentConfigurer.isOrchestrated(def)).isTrue();
        }

        @Test
        @DisplayName("pattern='supervisor' 但无 experts → false（按真相源 experts 判定，非 pattern 字符串）")
        void supervisorPatternWithoutExpertsShouldBeSingle() {
            AgentDefinition def = new AgentDefinition();
            OrchestrationConfig orch = new OrchestrationConfig();
            orch.setPattern("supervisor");
            def.setOrchestration(orch);
            assertThat(AgentConfigurer.isOrchestrated(def)).isFalse();
        }

        @Test
        @DisplayName("pipeline/routing 在加载期已被拒绝，运行期无 experts → false（不再按 pattern 识别为编排型）")
        void pipelineOrRoutingWithoutExpertsShouldBeSingle() {
            for (String pattern : List.of("pipeline", "routing", "handoffs")) {
                AgentDefinition def = new AgentDefinition();
                OrchestrationConfig orch = new OrchestrationConfig();
                orch.setPattern(pattern);
                def.setOrchestration(orch);
                assertThat(AgentConfigurer.isOrchestrated(def))
                        .as("pattern=%s 不应被运行期识别为编排型（真相源在 aistio 控制面）", pattern)
                        .isFalse();
            }
        }
    }
}
