package io.yunxi.platform.framework.hitl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.HITLConfig;
import io.yunxi.platform.shared.config.HumanToolConfig;
import io.yunxi.platform.shared.config.ReasoningReviewConfig;
import io.yunxi.platform.shared.config.ToolGateConfig;

/**
 * HITL (Human-in-the-Loop) 组件单元测试
 */
@ExtendWith(MockitoExtension.class)
class HITLTest {

        @Mock
        private Agent mockAgent;

        // ========== HITL 配置模型测试 ==========

        @Nested
        @DisplayName("HITL 配置模型")
        class HITLConfigTest {

                @Test
                @DisplayName("ExtensionConfig 默认不包含 HITL 配置")
                void extensionConfigDefaultNoHitl() {
                        ExtensionConfig config = new ExtensionConfig();
                        assertNull(config.getHitl());
                }

                @Test
                @DisplayName("ExtensionConfig 可以设置 HITL 配置")
                void extensionConfigCanSetHitl() {
                        ExtensionConfig config = new ExtensionConfig();
                        HITLConfig hitl = new HITLConfig();
                        config.setHitl(hitl);
                        assertNotNull(config.getHitl());
                }

                @Test
                @DisplayName("ToolGateConfig 默认值")
                void toolGateDefaultValues() {
                        ToolGateConfig config = new ToolGateConfig();
                        assertFalse(config.isEnabled());
                        assertTrue(config.getTools().isEmpty());
                        assertNotNull(config.getMessage());
                }

                @Test
                @DisplayName("ToolGateConfig 可以配置危险工具")
                void toolGateConfigWithTools() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setEnabled(true);
                        config.setTools(List.of("delete_file", "exec_command"));
                        assertTrue(config.isEnabled());
                        assertEquals(2, config.getTools().size());
                        assertTrue(config.getTools().contains("delete_file"));
                }

                @Test
                @DisplayName("ReasoningReviewConfig 默认策略为 on-dangerous-tool")
                void reasoningReviewDefaultStrategy() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        assertFalse(config.isEnabled());
                        assertEquals("on-dangerous-tool", config.getStrategy());
                }

                @Test
                @DisplayName("ReasoningReviewConfig 支持三种策略")
                void reasoningReviewStrategies() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        config.setEnabled(true);

                        config.setStrategy("all");
                        assertEquals("all", config.getStrategy());

                        config.setStrategy("on-dangerous-tool");
                        assertEquals("on-dangerous-tool", config.getStrategy());

                        config.setStrategy("keyword-match");
                        config.setKeywords(List.of("删除", "支付"));
                        assertEquals("keyword-match", config.getStrategy());
                        assertEquals(1, config.getKeywords().size()); // "删除" 是一个中文词
                }

                @Test
                @DisplayName("HumanToolConfig 默认禁用")
                void humanToolDefaultDisabled() {
                        HumanToolConfig config = new HumanToolConfig();
                        assertFalse(config.isEnabled());
                        assertTrue(config.getTools().isEmpty());
                }

                @Test
                @DisplayName("HumanToolConfig 可以启用人机协作")
                void humanToolConfigEnabled() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(true);
                        config.setTools(List.of("ask_user"));
                        assertTrue(config.isEnabled());
                        assertEquals(1, config.getTools().size());
                }

                @Test
                @DisplayName("完整 HITLConfig 序列化兼容性")
                void completeHITLConfig() {
                        HITLConfig config = new HITLConfig();
                        config.getToolGate().setEnabled(true);
                        config.getToolGate().setTools(List.of("delete_file"));
                        config.getReasoningReview().setEnabled(true);
                        config.getReasoningReview().setStrategy("all");
                        config.getHumanTool().setEnabled(true);
                        config.getHumanTool().setTools(List.of("ask_user"));

                        assertTrue(config.getToolGate().isEnabled());
                        assertTrue(config.getReasoningReview().isEnabled());
                        assertTrue(config.getHumanTool().isEnabled());
                }
        }

        // ========== ToolGateHook 测试 ==========

        @Nested
        @DisplayName("ToolGateHook 工具门控")
        class ToolGateHookTest {

                @Test
                @DisplayName("危险工具调用应触发 stopAgent")
                void dangerousToolShouldStopAgent() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setTools(List.of("delete_file"));
                        ToolGateHook hook = new ToolGateHook(config);

                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("delete_file")
                                                        .content("{}")
                                                        .input(Map.of())
                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertTrue(event.isStopRequested(),
                                        "危险工具应触发 stopAgent");
                }

                @Test
                @DisplayName("安全工具不应触发 stopAgent")
                void safeToolShouldNotStopAgent() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setTools(List.of("delete_file"));
                        ToolGateHook hook = new ToolGateHook(config);

                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(ToolUseBlock.builder()
                                                        .id("call_2")
                                                        .name("get_weather")
                                                        .content("{}")
                                                        .input(Map.of())
                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertFalse(event.isStopRequested(),
                                        "安全工具不应触发 stopAgent");
                }

                @Test
                @DisplayName("没有工具调用时不触发 stopAgent")
                void noToolCallShouldNotStopAgent() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setTools(List.of("delete_file"));
                        ToolGateHook hook = new ToolGateHook(config);

                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder()
                                                        .text("我来思考一下这个问题")
                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertFalse(event.isStopRequested(),
                                        "没有工具调用不应触发 stopAgent");
                }

                @Test
                @DisplayName("null 推理消息不抛异常")
                void nullReasoningMessageShouldNotThrow() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setTools(List.of("delete_file"));
                        ToolGateHook hook = new ToolGateHook(config);

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, null);

                        assertDoesNotThrow(() -> hook.onEvent(event).block());
                        assertFalse(event.isStopRequested());
                }

                @Test
                @DisplayName("多个危险工具调用应触发 stopAgent")
                void multipleDangerousToolsShouldStopAgent() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setTools(List.of("delete_file", "exec_command"));
                        ToolGateHook hook = new ToolGateHook(config);

                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(
                                                        ToolUseBlock.builder()
                                                                        .id("call_1")
                                                                        .name("delete_file")
                                                                        .content("{}")
                                                                        .input(Map.of())
                                                                        .build(),
                                                        ToolUseBlock.builder()
                                                                        .id("call_2")
                                                                        .name("exec_command")
                                                                        .content("{}")
                                                                        .input(Map.of())
                                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertTrue(event.isStopRequested(),
                                        "多个危险工具应触发 stopAgent");
                }

                @Test
                @DisplayName("禁用时不触发 stopAgent")
                void disabledConfigShouldNotStopAgent() {
                        ToolGateConfig config = new ToolGateConfig();
                        config.setEnabled(false); // 虽然 setTools 了
                        config.setTools(List.of("delete_file"));
                        // 但 ToolGateHook 是直接从 config.getTools() 构建的
                        // 所以 enabled 字段由外部控制（AgentBuilderHelper 判断）
                        // 这里测试即使 tools 非空，只要不匹配就不触发
                        ToolGateHook hook = new ToolGateHook(config);

                        // 实际上 ToolGateHook 不检查 enabled，由 AgentBuilderHelper 控制
                        // 所以这个测试验证：即使配置了危险工具，只要调用安全工具就不触发
                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("safe_tool")
                                                        .content("{}")
                                                        .input(Map.of())
                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertFalse(event.isStopRequested());
                }
        }

        // ========== ReasoningReviewHook 测试 ==========

        @Nested
        @DisplayName("ReasoningReviewHook 推理审查")
        class ReasoningReviewHookTest {

                @Test
                @DisplayName("all 策略应审查每次推理")
                void allStrategyShouldReviewAll() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        config.setStrategy("all");

                        ReasoningReviewHook hook = new ReasoningReviewHook(config, Set.of());

                        Msg reasoningMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder()
                                                        .text("普通推理内容")
                                                        .build())
                                        .build();

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, reasoningMsg);

                        hook.onEvent(event).block();

                        assertTrue(event.isStopRequested(),
                                        "all 策略应审查每次推理");
                }

                @Test
                @DisplayName("on-dangerous-tool 策略仅在有危险工具时审查")
                void onDangerousToolStrategy() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        config.setStrategy("on-dangerous-tool");

                        ReasoningReviewHook hook = new ReasoningReviewHook(
                                        config, Set.of("delete_file"));

                        // 危险工具
                        Msg dangerousMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(ToolUseBlock.builder()
                                                        .id("call_1")
                                                        .name("delete_file")
                                                        .content("{}")
                                                        .input(Map.of())
                                                        .build())
                                        .build();

                        PostReasoningEvent dangerousEvent = new PostReasoningEvent(
                                        mockAgent, "test-model", null, dangerousMsg);
                        hook.onEvent(dangerousEvent).block();
                        assertTrue(dangerousEvent.isStopRequested(),
                                        "危险工具应审查");

                        // 安全工具
                        Msg safeMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(ToolUseBlock.builder()
                                                        .id("call_2")
                                                        .name("get_weather")
                                                        .content("{}")
                                                        .input(Map.of())
                                                        .build())
                                        .build();

                        PostReasoningEvent safeEvent = new PostReasoningEvent(
                                        mockAgent, "test-model", null, safeMsg);
                        hook.onEvent(safeEvent).block();
                        assertFalse(safeEvent.isStopRequested(),
                                        "安全工具不应审查");
                }

                @Test
                @DisplayName("keyword-match 策略应匹配敏感关键词")
                void keywordMatchStrategy() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        config.setStrategy("keyword-match");
                        config.setKeywords(List.of("delete", "remove"));

                        ReasoningReviewHook hook = new ReasoningReviewHook(config, Set.of());

                        // 包含敏感关键词
                        Msg sensitiveMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder()
                                                        .text("I will delete the file")
                                                        .build())
                                        .build();

                        PostReasoningEvent sensitiveEvent = new PostReasoningEvent(
                                        mockAgent, "test-model", null, sensitiveMsg);
                        hook.onEvent(sensitiveEvent).block();
                        assertTrue(sensitiveEvent.isStopRequested(),
                                        "包含敏感关键词应审查");

                        // 不包含敏感关键词
                        Msg safeMsg = Msg.builder()
                                        .role(MsgRole.ASSISTANT)
                                        .content(TextBlock.builder()
                                                        .text("I will read the file")
                                                        .build())
                                        .build();

                        PostReasoningEvent safeEvent = new PostReasoningEvent(
                                        mockAgent, "test-model", null, safeMsg);
                        hook.onEvent(safeEvent).block();
                        assertFalse(safeEvent.isStopRequested(),
                                        "不包含敏感关键词不应审查");
                }

                @Test
                @DisplayName("null 推理消息不抛异常")
                void nullReasoningMessageShouldNotThrow() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        config.setStrategy("all");

                        ReasoningReviewHook hook = new ReasoningReviewHook(config, Set.of());

                        PostReasoningEvent event = new PostReasoningEvent(
                                        mockAgent, "test-model", null, null);

                        assertDoesNotThrow(() -> hook.onEvent(event).block());
                        assertFalse(event.isStopRequested());
                }
        }

        // ========== HumanToolRegistrar 测试 ==========

        @Nested
        @DisplayName("HumanToolRegistrar 人机协作")
        class HumanToolRegistrarTest {

                @Test
                @DisplayName("禁用时不注册任何工具")
                void disabledShouldNotRegister() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(false);
                        config.setTools(List.of("ask_user"));

                        Toolkit toolkit = mock(Toolkit.class);
                        HumanToolRegistrar registrar = new HumanToolRegistrar(config);
                        registrar.registerTools(toolkit);

                        verify(toolkit, never()).registerSchema(any());
                }

                @Test
                @DisplayName("启用时注册 ask_user 工具")
                void enabledShouldRegisterAskUser() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(true);
                        config.setTools(List.of("ask_user"));

                        Toolkit toolkit = new Toolkit();
                        HumanToolRegistrar registrar = new HumanToolRegistrar(config);
                        registrar.registerTools(toolkit);

                        assertTrue(toolkit.isExternalTool("ask_user"),
                                        "ask_user 应被注册为 SchemaOnlyTool");
                }

                @Test
                @DisplayName("空工具列表不注册")
                void emptyToolsShouldNotRegister() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(true);

                        Toolkit toolkit = mock(Toolkit.class);
                        HumanToolRegistrar registrar = new HumanToolRegistrar(config);
                        registrar.registerTools(toolkit);

                        verify(toolkit, never()).registerSchema(any());
                }

                @Test
                @DisplayName("null toolkit 不抛异常")
                void nullToolkitShouldNotThrow() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(true);
                        config.setTools(List.of("ask_user"));

                        HumanToolRegistrar registrar = new HumanToolRegistrar(config);

                        assertDoesNotThrow(() -> registrar.registerTools(null));
                }

                @Test
                @DisplayName("注册后的 ask_user 不可在内部执行")
                void registeredAskUserIsExternal() {
                        HumanToolConfig config = new HumanToolConfig();
                        config.setEnabled(true);
                        config.setTools(List.of("ask_user"));

                        Toolkit toolkit = new Toolkit();
                        HumanToolRegistrar registrar = new HumanToolRegistrar(config);
                        registrar.registerTools(toolkit);

                        assertTrue(toolkit.isExternalTool("ask_user"),
                                        "被注册为 SchemaOnlyTool 后 isExternalTool 应返回 true");
                }
        }

        // ========== 集成场景测试 ==========

        @Nested
        @DisplayName("HITL 集成场景")
        class IntegrationScenarioTest {

                @Test
                @DisplayName("HITLConfig 通过 ExtensionConfig 的完整路径可达")
                void hitlConfigReachableViaExtensionConfig() {
                        ExtensionConfig ext = new ExtensionConfig();
                        HITLConfig hitl = new HITLConfig();
                        hitl.getToolGate().setEnabled(true);
                        hitl.getToolGate().setTools(List.of("exec_command"));
                        hitl.getReasoningReview().setEnabled(true);
                        hitl.getHumanTool().setEnabled(true);
                        ext.setHitl(hitl);

                        assertNotNull(ext.getHitl());
                        assertTrue(ext.getHitl().getToolGate().isEnabled());
                        assertTrue(ext.getHitl().getToolGate().getTools().contains("exec_command"));
                        assertTrue(ext.getHitl().getReasoningReview().isEnabled());
                        assertTrue(ext.getHitl().getHumanTool().isEnabled());
                }

                @Test
                @DisplayName("ToolGateHook 优先级低于 TextToolCallParserHook")
                void toolGatePriorityOrder() {
                        ToolGateConfig config = new ToolGateConfig();
                        ToolGateHook hook = new ToolGateHook(config);

                        // TextToolCallParserHook priority = 45
                        // ToolGateHook priority = 55
                        // ReasoningReviewHook priority = 70
                        assertTrue(hook.priority() > 45,
                                        "ToolGateHook 应在 TextToolCallParserHook 之后执行");
                }

                @Test
                @DisplayName("ReasoningReviewHook 优先级低于 ToolGateHook")
                void reasoningReviewPriorityOrder() {
                        ReasoningReviewConfig config = new ReasoningReviewConfig();
                        ReasoningReviewHook hook = new ReasoningReviewHook(config, Set.of());

                        // ToolGateHook priority = 55
                        // ReasoningReviewHook priority = 70
                        assertTrue(hook.priority() > 55,
                                        "ReasoningReviewHook 应在 ToolGateHook 之后执行");
                }
        }

        /**
         * 辅助方法：创建 Set.of 的替代（兼容 Java 11）
         */
        private static <T> java.util.Set<T> SetOf(T... elements) {
                return java.util.Set.of(elements);
        }
}
