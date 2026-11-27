package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2ui4j.catalog.CatalogApi;
import io.a2ui4j.catalog.CatalogPromptBuilder;
import io.a2ui4j.catalog.JsonCatalog;
import io.a2ui4j.core.codec.A2uiCodec;
import io.a2ui4j.core.dispatch.RendererToAgentHandler;
import io.a2ui4j.core.message.Action;
import io.a2ui4j.core.message.CallAgentFunction;
import io.a2ui4j.core.message.ErrorMessage;
import io.a2ui4j.core.message.RendererFunctionResponseMessage;
import io.a2ui4j.core.spec.SpecSchemaValidator;
import io.yunxi.a2ui.A2uiParseResult;
import io.yunxi.a2ui.A2uiResponseParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 真实 LLM 端到端：prompt（由 a2ui4j catalog 驱动）→ 真实 DashScope Chat Completions
 * → 真实响应文本 → A2uiResponseParser 解析校验分发。默认不执行，仅 -Dreal.llm=true
 * 且 DASHSCOPE_API_KEY 存在时激活。模型本次未产出 A2UI 块为软通过（不伪造结果）。
 */
@EnabledIfSystemProperty(named = "real.llm", matches = "true")
class A2uiRealLlmE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void requireKey() {
        assumeTrue(System.getenv("DASHSCOPE_API_KEY") != null && !System.getenv("DASHSCOPE_API_KEY").isBlank(),
                "未设置 DASHSCOPE_API_KEY，跳过真实 LLM 端到端测试");
    }

    @Test
    void realModelResponseThroughA2uiParser() throws Exception {
        CatalogApi catalog;
        try (var in = A2uiRealLlmE2eTest.class.getResourceAsStream(
                "/spec/v1_0/catalogs/basic/catalog.json")) {
            assumeTrue(in != null, "basic catalog 资源不可用");
            catalog = JsonCatalog.load(MAPPER.readTree(in));
        }
        String a2uiPrompt = CatalogPromptBuilder.systemPrompt(catalog, Set.of("Text"));
        assertThat(a2uiPrompt).contains("<a2ui-json>").contains(catalog.id());

        String systemPrompt = "你是一个 A2UI 界面生成助手。" + a2uiPrompt;
        String requestBody = MAPPER.writeValueAsString(java.util.Map.of(
                "model", "qwen-plus",
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", systemPrompt),
                        java.util.Map.of("role", "user", "content", "请用一个 Text 组件显示 'Hello A2UI'，输出 A2UI JSON 块。")),
                "stream", false));

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                .header("Authorization", "Bearer " + System.getenv("DASHSCOPE_API_KEY"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            assumeTrue(false, "DASHSCOPE_API_KEY 无效（HTTP " + response.statusCode()
                    + "），跳过真实 LLM 端到端测试：需更新 key 后重跑");
        }
        assertThat(response.statusCode()).as("DashScope HTTP 状态").isEqualTo(200);

        JsonNode body = MAPPER.readTree(response.body());
        String text = body.path("choices").path(0).path("message").path("content").asText("");
        assertThat(text).as("真实模型返回非空文本").isNotBlank();

        RecordingHandler handler = new RecordingHandler();
        A2uiParseResult result = new A2uiResponseParser(
                SpecSchemaValidator.bundled(), new A2uiCodec()).parse(text, "e2e-s1", handler);

        if (result.a2uiBlockFound()) {
            // 模型生成的 A2UI 块是 Agent→Renderer 方向（如 createSurface），
            // 校验通过后作为出站载荷由宿主转发渲染端；渲染端回流才会走入站分发。
            assertThat(result.validationIssues()).isEmpty();
            assertThat(result.outboundMessages()).isNotEmpty();
        } else {
            // 模型本次未生成 A2UI 块：软通过，记录而不伪造结果
            assertThat(result.remainingText()).contains(text);
            assertThat(handler.routes).isEmpty();
        }
    }

    private static final class RecordingHandler implements RendererToAgentHandler {
        final List<String> routes = new ArrayList<>();
        @Override public void onAction(Action m) { routes.add("onAction"); }
        @Override public void onCallAgentFunction(CallAgentFunction m) { routes.add("onCallAgentFunction"); }
        @Override public void onRendererFunctionResponse(RendererFunctionResponseMessage m) { routes.add("onRendererFunctionResponse"); }
        @Override public void onError(ErrorMessage m) { routes.add("onError"); }
    }
}
