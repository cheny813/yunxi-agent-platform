package io.yunxi.platform.config;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.yunxi.platform.shared.config.HITLConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionConfig} 单元测试。
 *
 * <p>验证 HITL 配置→GA 权限上下文的映射逻辑，覆盖所有组合路径：
 * <ul>
 *   <li>null/空配置 → BYPASS 模式（完全放开）</li>
 *   <li>仅 ToolGate → ASK 规则（工具执行前需确认）</li>
 *   <li>仅 ReasoningReview → ASK 规则（复用 ToolGate 工具清单）</li>
 *   <li>两者同时启用 → 合并 ASK 规则</li>
 * </ul>
 * </p>
 */
@DisplayName("PermissionConfig 单元测试")
class PermissionConfigTest {

    private final PermissionConfig permissionConfig = new PermissionConfig();

    @Nested
    @DisplayName("build(null)")
    class NullHitlConfig {

        @Test
        @DisplayName("hitl=null → BYPASS 模式，无规则")
        void nullHitlShouldReturnBypassWithNoRules() {
            PermissionContextState state = permissionConfig.build(null);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
            assertThat(state.getDenyRules()).isEmpty();
        }
    }

    @Nested
    @DisplayName("build(HITLConfig) — ToolGate 场景")
    class ToolGateScenarios {

        @Test
        @DisplayName("ToolGate 禁用 → BYPASS 模式，无规则")
        void disabledToolGateShouldReturnBypass() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(false);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command"));

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ToolGate 启用但工具列表为空 → BYPASS 模式")
        void enabledToolGateWithEmptyToolsShouldBypass() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of());

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ToolGate 启用但工具列表为 null → BYPASS 模式")
        void enabledToolGateWithNullToolsShouldBypass() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(null);

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ToolGate 启用 + 单个工具 → DEFAULT 模式 + 1 条 ASK 规则")
        void singleToolShouldCreateOneAskRule() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).hasSize(1);
            assertThat(state.getAskRules()).containsKey("delete_file");
            // 验证规则属性：每条工具对应一个规则列表，列表中每个规则的 behavior=ASK, source=yunxi-hitl
            List<io.agentscope.core.permission.PermissionRule> rules = state.getAskRules().get("delete_file");
            assertThat(rules).isNotNull().hasSizeGreaterThanOrEqualTo(1);
            assertThat(rules.get(0).behavior()).isEqualTo(PermissionBehavior.ASK);
            assertThat(rules.get(0).source()).isEqualTo("yunxi-hitl");
        }

        @Test
        @DisplayName("ToolGate 启用 + 多个工具 → DEFAULT 模式 + 对应数量 ASK 规则")
        void multipleToolsShouldCreateMultipleAskRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command", "write_db"));

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).hasSize(3);
            assertThat(state.getAskRules()).containsKeys("delete_file", "exec_command", "write_db");
            assertThat(state.getDenyRules()).isEmpty();
        }
    }

    @Nested
    @DisplayName("build(HITLConfig) — ReasoningReview 场景")
    class ReasoningReviewScenarios {

        @Test
        @DisplayName("ReasoningReview 禁用 → 不产生规则（即使 ToolGate 有工具）")
        void disabledReasoningReviewShouldAddNoRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getReasoningReview().setEnabled(false);
            hitl.getToolGate().setEnabled(false);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ReasoningReview 启用 + ToolGate null → 不产生规则")
        void reasoningReviewWithNullToolGateShouldAddNoRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getReasoningReview().setEnabled(true);
            hitl.setToolGate(null);

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ReasoningReview 启用 + ToolGate 无工具 → 不产生规则")
        void reasoningReviewWithNoToolsShouldAddNoRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getReasoningReview().setEnabled(true);
            hitl.getToolGate().setTools(null);

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ReasoningReview 启用 + ToolGate 有工具 → DEFAULT 模式 + ASK 规则")
        void reasoningReviewWithToolsShouldCreateAskRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getReasoningReview().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command"));

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).hasSize(2);
            assertThat(state.getAskRules()).containsKeys("delete_file", "exec_command");
        }
    }

    @Nested
    @DisplayName("build(HITLConfig) — ToolGate + ReasoningReview 同时启用")
    class CombinedScenarios {

        @Test
        @DisplayName("两者均启用 + 同一工具列表 → 各工具注册一条 ASK 规则（不重复）")
        void bothEnabledShouldNotDuplicateRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command"));
            hitl.getReasoningReview().setEnabled(true);

            PermissionContextState state = permissionConfig.build(hitl);

            // ToolGate 注册 2 条 → ReasoningReview 同样用 Set.copyOf 注册 2 条
            // GA PermissionContextState.Builder 内部覆盖同 key 规则，故最终 2 条
            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).hasSize(2);
            assertThat(state.getAskRules()).containsKeys("delete_file", "exec_command");
        }

        @Test
        @DisplayName("两者均启用 + 不同的工具列表 → 合并后的 ASK 规则")
        void bothEnabledWithDifferentTools() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));
            hitl.getReasoningReview().setEnabled(true);

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).containsKey("delete_file");
        }
    }

    @Nested
    @DisplayName("build(HITLConfig) — 边界情况")
    class EdgeCases {

        @Test
        @DisplayName("默认构造的 HITLConfig（所有子配置均为默认值）→ BYPASS")
        void defaultHitlConfigShouldBypass() {
            HITLConfig hitl = new HITLConfig();

            PermissionContextState state = permissionConfig.build(hitl);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("多次调用 build 应返回不同实例（无状态共享）")
        void consecutiveCallsShouldReturnIndependentInstances() {
            HITLConfig hitl1 = new HITLConfig();
            hitl1.getToolGate().setEnabled(true);
            hitl1.getToolGate().setTools(List.of("tool_a"));

            HITLConfig hitl2 = new HITLConfig();
            hitl2.getToolGate().setEnabled(true);
            hitl2.getToolGate().setTools(List.of("tool_b"));

            PermissionContextState state1 = permissionConfig.build(hitl1);
            PermissionContextState state2 = permissionConfig.build(hitl2);

            assertThat(state1.getAskRules()).containsKey("tool_a");
            assertThat(state1.getAskRules()).doesNotContainKey("tool_b");
            assertThat(state2.getAskRules()).containsKey("tool_b");
            assertThat(state2.getAskRules()).doesNotContainKey("tool_a");
        }
    }
}
