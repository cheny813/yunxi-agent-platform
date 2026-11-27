package io.yunxi.a2ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.definition.AgentSpec;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * A2UI 装配贡献点契约：per-Agent 声明与全局开关二者 OR 生效——任一开启即注入协议说明
 * 中间件，均关闭时不注入（对 Agent 零影响）。
 */
class A2uiAgentBuildContributorTest {

    @Test
    void bothSwitchesOffContributesNothing() throws Exception {
        A2uiAgentBuildContributor contributor = new A2uiAgentBuildContributor();
        setGlobalEnabled(contributor, false);

        assertThat(contributor.buildTimeMiddleware(spec(null))).isNull();
    }

    @Test
    void globalSwitchOnEnablesEveryAgent() throws Exception {
        A2uiAgentBuildContributor contributor = new A2uiAgentBuildContributor();
        setGlobalEnabled(contributor, true);

        MiddlewareBase middleware = contributor.buildTimeMiddleware(spec(null));
        assertThat(middleware).isNotNull();
        assertThat(promptOf(middleware)).contains("<a2ui-json>");
    }

    @Test
    void perAgentDeclarationEnablesWithoutGlobalSwitch() throws Exception {
        A2uiAgentBuildContributor contributor = new A2uiAgentBuildContributor();
        setGlobalEnabled(contributor, false);

        MiddlewareBase middleware = contributor.buildTimeMiddleware(spec(new AgentSpec.A2uiConfig(true)));
        assertThat(middleware).isNotNull();
        assertThat(promptOf(middleware)).contains("<a2ui-json>");
    }

    @Test
    void systemPromptAppendKeepsExistingPrompt() throws Exception {
        A2uiAgentBuildContributor contributor = new A2uiAgentBuildContributor();
        setGlobalEnabled(contributor, true);
        Agent agent = mock(Agent.class);

        String result = contributor.buildTimeMiddleware(spec(null))
                .onSystemPrompt(agent, mock(io.agentscope.core.agent.RuntimeContext.class), "base prompt")
                .block();

        assertThat(result).startsWith("base prompt");
    }

    private static AgentSpec spec(AgentSpec.A2uiConfig a2ui) {
        return new AgentSpec(new AgentSpec.Identity("demo-agent", "演示助手", "你是演示助手。"),
                null, null, null, null, null, null, null, null, null, null, a2ui);
    }

    private static void setGlobalEnabled(A2uiAgentBuildContributor contributor, boolean value) throws Exception {
        Field f = A2uiAgentBuildContributor.class.getDeclaredField("globalEnabled");
        f.setAccessible(true);
        f.setBoolean(contributor, value);
    }

    private static String promptOf(MiddlewareBase middleware) throws Exception {
        Agent agent = mock(Agent.class);
        return middleware.onSystemPrompt(agent, mock(io.agentscope.core.agent.RuntimeContext.class), null).block();
    }
}
