package io.yunxi.a2ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2ui4j.catalog.CatalogApi;
import io.a2ui4j.catalog.JsonCatalog;
import io.a2ui4j.catalog.CatalogPromptBuilder;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A2UI prompt 注入中间件契约：显式启用时把 catalog 驱动的 system prompt 片段追加到
 * Agent 现有 system prompt；未声明 A2UI 能力（未构造该中间件）时框架行为不变。
 */
class A2uiPromptMiddlewareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CatalogApi basicCatalog() throws Exception {
        try (var in = A2uiPromptMiddlewareTest.class.getResourceAsStream(
                "/spec/v1_0/catalogs/basic/catalog.json")) {
            assertThat(in).as("basic catalog resource from a2ui4j-core").isNotNull();
            return JsonCatalog.load(MAPPER.readTree(in));
        }
    }

    @Test
    void appendsA2uiPromptToExistingSystemPrompt() throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("demo-agent");
        CatalogApi catalog = basicCatalog();
        String a2uiPrompt = CatalogPromptBuilder.systemPrompt(catalog, Set.of("Text", "Card"));
        A2uiPromptMiddleware middleware = new A2uiPromptMiddleware(a2uiPrompt);

        String result = middleware.onSystemPrompt(agent, mock(RuntimeContext.class),
                "You are a helpful assistant.").block();

        assertThat(result).startsWith("You are a helpful assistant.");
        assertThat(result).contains(catalog.id()).contains("<a2ui-json>");
        assertThat(result).contains("Text(").contains("Card(");
    }

    @Test
    void handlesNullCurrentPrompt() throws Exception {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("demo-agent");
        CatalogApi catalog = basicCatalog();
        String a2uiPrompt = CatalogPromptBuilder.systemPrompt(catalog, null);
        A2uiPromptMiddleware middleware = new A2uiPromptMiddleware(a2uiPrompt);

        String result = middleware.onSystemPrompt(agent, mock(RuntimeContext.class), null).block();

        assertThat(result).contains(catalog.id()).contains("<a2ui-json>").contains("Text(");
    }

    @Test
    void narrowActivePointsDeclaresSystemPromptOnly() throws Exception {
        CatalogApi catalog = basicCatalog();
        String a2uiPrompt = CatalogPromptBuilder.systemPrompt(catalog, Set.of("Text"));
        A2uiPromptMiddleware middleware = new A2uiPromptMiddleware(a2uiPrompt);

        assertThat(middleware.activePoints()).containsExactly(
                io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint.ON_SYSTEM_PROMPT);
    }
}
