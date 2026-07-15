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
 * <p>验证 HITL 配置 → GA 权限上下文的映射逻辑（{@code build(HITLConfig, PermissionMode)}）。
 * yunxi 不封装权限模式，直接透传 GA 原生 {@link PermissionMode}，仅把 HITL 中需人工确认的工具
 * 注册为 ASK 规则（对应官方"配置 ASK 规则，标记需要人工确认的工具")。Agent 遇到 ASK 工具时挂起，
 * 返回 {@code PERMISSION_ASKING}，调用方提取 ASKING 状态的 ToolUseBlock、构建 ConfirmResult 恢复。</p>
 */
@DisplayName("PermissionConfig 单元测试")
class PermissionConfigTest {

    private final PermissionConfig permissionConfig = new PermissionConfig();

    @Nested
    @DisplayName("build(HITLConfig, PermissionMode) — 模式透传 + ASK 规则")
    class PassthroughScenarios {

        @Test
        @DisplayName("DONT_ASK → 模式透传，且绝不注入 ASK 规则（避免 GA checkAskRules 不看 mode 的无人值守死锁）")
        void dontAskNeverAddsAskRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DONT_ASK);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DONT_ASK);
            assertThat(state.getAskRules()).isEmpty();
            // 危险路径保护由 GA 框架层（ToolDangerousPathConstants）自动强制 ASK，无需 yunxi 处理
        }

        @Test
        @DisplayName("BYPASS → 模式透传；HITL 的 ASK 规则仍生效（与 GA 危险路径强制 ASK 一致）")
        void bypassKeepsAskRulesFromHitl() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.BYPASS);

            assertThat(state.getMode()).isEqualTo(PermissionMode.BYPASS);
            assertThat(state.getAskRules()).containsKey("delete_file");
        }

        @Test
        @DisplayName("DEFAULT → 模式透传 + HITL ASK 规则（最常用：被点名工具需确认）")
        void defaultKeepsAskRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DEFAULT);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).containsKey("delete_file");
        }

        @Test
        @DisplayName("ACCEPT_EDITS → 框架用户可用的特殊模式，yunxi 透传不裁剪")
        void acceptEditsPassthrough() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("edit_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.ACCEPT_EDITS);

            assertThat(state.getMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
            assertThat(state.getAskRules()).containsKey("edit_file");
        }

        @Test
        @DisplayName("EXPLORE → 框架用户可用的特殊模式，yunxi 透传不裁剪")
        void explorePassthrough() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("read_only"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.EXPLORE);

            assertThat(state.getMode()).isEqualTo(PermissionMode.EXPLORE);
            assertThat(state.getAskRules()).containsKey("read_only");
        }

        @Test
        @DisplayName("null 配置 + ANY 模式 → 模式透传，无规则")
        void nullHitlWithAnyModeHasNoRules() {
            PermissionContextState state = permissionConfig.build(null, PermissionMode.DEFAULT);

            assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
            assertThat(state.getAskRules()).isEmpty();
            assertThat(state.getDenyRules()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ASK 规则属性校验（HITL → GA 映射）")
    class AskRuleProperties {

        @Test
        @DisplayName("单工具 → 1 条 ASK 规则，behavior=ASK、source=yunxi-hitl")
        void singleToolRuleProperties() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DEFAULT);

            assertThat(state.getAskRules()).hasSize(1);
            List<io.agentscope.core.permission.PermissionRule> rules = state.getAskRules().get("delete_file");
            assertThat(rules).isNotNull().hasSizeGreaterThanOrEqualTo(1);
            assertThat(rules.get(0).behavior()).isEqualTo(PermissionBehavior.ASK);
            assertThat(rules.get(0).source()).isEqualTo("yunxi-hitl");
        }

        @Test
        @DisplayName("多工具 → 对应数量 ASK 规则，无 DENY 规则")
        void multipleToolsCreateMultipleRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command", "write_db"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DEFAULT);

            assertThat(state.getAskRules()).hasSize(3);
            assertThat(state.getAskRules()).containsKeys("delete_file", "exec_command", "write_db");
            assertThat(state.getDenyRules()).isEmpty();
        }

        @Test
        @DisplayName("ToolGate 禁用 → 不注入 ASK 规则")
        void disabledToolGateAddsNoRules() {
            HITLConfig hitl = new HITLConfig();
            hitl.getToolGate().setEnabled(false);
            hitl.getToolGate().setTools(List.of("delete_file"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DEFAULT);

            assertThat(state.getAskRules()).isEmpty();
        }

        @Test
        @DisplayName("ReasoningReview 启用复用 ToolGate 工具清单 → 仍只针对同一批工具注册 ASK 规则")
        void reasoningReviewReusesToolGateTools() {
            HITLConfig hitl = new HITLConfig();
            hitl.getReasoningReview().setEnabled(true);
            hitl.getToolGate().setTools(List.of("delete_file", "exec_command"));

            PermissionContextState state = permissionConfig.build(hitl, PermissionMode.DEFAULT);

            assertThat(state.getAskRules()).hasSize(2);
            assertThat(state.getAskRules()).containsKeys("delete_file", "exec_command");
        }
    }
}
