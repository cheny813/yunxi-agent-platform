package io.yunxi.platform.agent.capability;

import io.agentscope.core.tool.Tool;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentscopeCoreProperties;
import io.yunxi.platform.shared.config.PlanConfig;
import io.yunxi.platform.tool.impl.CalculatorTool;
import io.yunxi.platform.tool.impl.DatabaseTool;
import io.yunxi.platform.tool.impl.HttpTool;
import io.yunxi.platform.tool.impl.NodeTool;
import io.yunxi.platform.tool.impl.SessionSearchTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link PlanModeCapability} 单元测试。
 *
 * <p>覆盖两类回归风险：</p>
 * <ol>
 *   <li>计划模式必须走框架原生 {@code HarnessAgent.Builder} 开关，而不是手工拼装中间件。
 *       手工拼装不会注册 {@code plan_enter} / {@code plan_write} / {@code plan_exit} 三个控制
 *       工具，模型因此无法进入或退出计划模式 —— 这里用 Builder 交互验证把该约束钉住。</li>
 *   <li>只读阶段的放行依赖工具自身的 {@code readOnly} 元数据，而不是按工具名关键字猜测。
 *       这里断言只读工具类确实带上了 {@code @Tool(readOnly = true)}，写类工具没有。</li>
 * </ol>
 */
@DisplayName("PlanModeCapability 单元测试")
class PlanModeCapabilityTest {

    private AgentscopeCoreProperties properties;
    private AgentscopeCoreProperties.PlanProperties plan;

    @BeforeEach
    void setUp() {
        properties = new AgentscopeCoreProperties();
        plan = new AgentscopeCoreProperties.PlanProperties();
        properties.setPlan(plan);
    }

    @Nested
    @DisplayName("supports：定义级开关与全局开关二者之一生效")
    class Supports {

        @Test
        @DisplayName("YAML 级 enabled=true → true")
        void yamlEnabledShouldBeSupported() {
            AgentDefinition def = definitionWithPlan(true, false);

            assertThat(new PlanModeCapability(properties).supports(def)).isTrue();
        }

        @Test
        @DisplayName("YAML 级 enabled=false 但全局 enabled=true → true")
        void globalEnabledShouldBeSupported() {
            plan.setEnabled(true);
            AgentDefinition def = definitionWithPlan(false, false);

            assertThat(new PlanModeCapability(properties).supports(def)).isTrue();
        }

        @Test
        @DisplayName("两处皆 false → false")
        void bothDisabledShouldNotBeSupported() {
            AgentDefinition def = definitionWithPlan(false, false);

            assertThat(new PlanModeCapability(properties).supports(def)).isFalse();
        }

        @Test
        @DisplayName("definition 为 null 时回落到全局开关")
        void nullDefinitionShouldFallBackToGlobal() {
            plan.setEnabled(true);

            assertThat(new PlanModeCapability(properties).supports(null))
                    .isEqualTo(plan.isEnabled());
        }
    }

    @Nested
    @DisplayName("configure：必须走 Builder 原生开关")
    class Configure {

        @Test
        @DisplayName("启用后应调用 enablePlanMode / planFileDirectory / allowShellInPlanMode")
        void shouldUseNativeBuilderSwitches() {
            plan.setPlanDir("custom-plan-dir");
            HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
            AgentDefinition def = definitionWithPlan(true, false);

            new PlanModeCapability(properties).configure(builder, def);

            verify(builder).enablePlanMode(true);
            verify(builder).planFileDirectory("custom-plan-dir");
            verify(builder).allowShellInPlanMode(false);
        }

        @Test
        @DisplayName("YAML 级 allowShellInPlanMode=true 应透传给 Builder")
        void yamlAllowShellShouldPassThrough() {
            HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
            AgentDefinition def = definitionWithPlan(true, true);

            new PlanModeCapability(properties).configure(builder, def);

            verify(builder).allowShellInPlanMode(true);
        }

        @Test
        @DisplayName("全局 allowShellInPlanMode=true 应透传给 Builder")
        void globalAllowShellShouldPassThrough() {
            plan.setAllowShellInPlanMode(true);
            HarnessAgent.Builder builder = mock(HarnessAgent.Builder.class);
            AgentDefinition def = definitionWithPlan(true, false);

            new PlanModeCapability(properties).configure(builder, def);

            verify(builder).allowShellInPlanMode(true);
        }
    }

    @Nested
    @DisplayName("工具只读元数据：计划模式的放行依据")
    class ReadOnlyMetadata {

        @Test
        @DisplayName("会话检索工具应为只读")
        void sessionSearchShouldBeReadOnly() {
            assertThat(readOnlyOf(SessionSearchTool.class))
                    .as("session_history_search 只读访问会话历史，计划模式下应放行")
                    .isTrue();
        }

        @Test
        @DisplayName("数据库查询工具应为只读")
        void databaseQueryShouldBeReadOnly() {
            assertThat(readOnlyOf(DatabaseTool.class))
                    .as("database_query 仅支持 SELECT，计划模式下应放行")
                    .isTrue();
        }

        @Test
        @DisplayName("计算器工具应为只读")
        void calculatorShouldBeReadOnly() {
            assertThat(readOnlyOf(CalculatorTool.class))
                    .as("calculator 无副作用，计划模式下应放行")
                    .isTrue();
        }

        @Test
        @DisplayName("HTTP 请求工具不应为只读")
        void httpRequestShouldNotBeReadOnly() {
            assertThat(readOnlyOf(HttpTool.class))
                    .as("http_request 可触发副作用 POST/DELETE，计划模式下应拒绝")
                    .isFalse();
        }

        @Test
        @DisplayName("节点命令工具不应为只读")
        void nodeCommandShouldNotBeReadOnly() {
            assertThat(readOnlyOf(NodeTool.class))
                    .as("node_command 为执行类操作，计划模式下应拒绝")
                    .isFalse();
        }
    }

    private static AgentDefinition definitionWithPlan(boolean enabled, boolean allowShell) {
        AgentDefinition def = new AgentDefinition();
        PlanConfig cfg = new PlanConfig();
        cfg.setEnabled(enabled);
        cfg.setAllowShellInPlanMode(allowShell);
        def.setPlan(cfg);
        return def;
    }

    private static boolean readOnlyOf(Class<?> toolClass) {
        for (Method method : toolClass.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null) {
                return tool.readOnly();
            }
        }
        throw new AssertionError("类型上未找到 @Tool 注解：" + toolClass.getName());
    }
}
