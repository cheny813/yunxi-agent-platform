package io.yunxi.a2ui;

import static org.assertj.core.api.Assertions.assertThat;

import io.a2ui4j.core.codec.A2uiCodec;
import io.a2ui4j.core.dispatch.RendererToAgentHandler;
import io.a2ui4j.core.message.A2uiEnvelope;
import io.a2ui4j.core.message.CallAgentFunction;
import io.a2ui4j.core.message.CreateSurface;
import io.a2ui4j.core.message.ErrorMessage;
import io.a2ui4j.core.message.RendererFunctionResponseMessage;
import io.a2ui4j.core.message.Action;
import io.a2ui4j.core.spec.SpecSchemaValidator;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A2UI 响应解析契约：把模型文本中的 <a2ui-json> 块经 a2ui4j 解析、修复、校验后
 * 分发给宿主 handler；无哨兵块时原样保留文本（软通过）；非法消息拒绝不分发。
 */
class A2uiResponseParserTest {

    private final A2uiResponseParser parser = new A2uiResponseParser(
            SpecSchemaValidator.bundled(), new A2uiCodec());

    private static final class RecordingHandler implements RendererToAgentHandler {
        final List<String> routes = new ArrayList<>();
        Action action;
        ErrorMessage error;

        @Override public void onAction(Action m) { routes.add("onAction"); this.action = m; }
        @Override public void onCallAgentFunction(CallAgentFunction m) { routes.add("onCallAgentFunction"); }
        @Override public void onRendererFunctionResponse(RendererFunctionResponseMessage m) { routes.add("onRendererFunctionResponse"); }
        @Override public void onError(ErrorMessage m) { routes.add("onError"); this.error = m; }
    }

    @Test
    void parsesSentinelBlockAndRoutesAction() {
        String text = """
                这是界面：
                <a2ui-json>[{"version":"1.0","action":{"name":"submit",
                 "surfaceId":"s1","sourceComponentId":"btn1","timestamp":"2026-11-26T12:00:00Z"}}]</a2ui-json>
                """;
        RecordingHandler handler = new RecordingHandler();
        A2uiParseResult result = parser.parse(text, "s1", handler);

        assertThat(result.a2uiBlockFound()).isTrue();
        assertThat(result.validationIssues()).isEmpty();
        assertThat(handler.routes).containsExactly("onAction");
        assertThat(handler.action.name()).isEqualTo("submit");
        assertThat(result.remainingText()).contains("这是界面：");
    }

    @Test
    void noSentinelBlockKeepsTextAndDispatchesNothing() {
        RecordingHandler handler = new RecordingHandler();
        A2uiParseResult result = parser.parse("普通回答，没有 UI。", "s1", handler);

        assertThat(result.a2uiBlockFound()).isFalse();
        assertThat(result.remainingText()).isEqualTo("普通回答，没有 UI。");
        assertThat(handler.routes).isEmpty();
    }

    @Test
    void agentToRendererBlockCollectsOutboundWithoutDispatch() {
        // 模型响应里的 A2UI 块是 Agent→Renderer 方向（如 createSurface），应收集为
        // 出站消息由宿主转发渲染端，而不是走 renderer→agent 入站分发。
        String text = """
                界面如下：
                <a2ui-json>[{"version":"1.0","createSurface":{"surfaceId":"s1",
                 "catalogId":"acme:basic"}}]</a2ui-json>
                """;
        RecordingHandler handler = new RecordingHandler();
        A2uiParseResult result = parser.parse(text, "s1", handler);

        assertThat(result.a2uiBlockFound()).isTrue();
        assertThat(result.validationIssues()).isEmpty();
        assertThat(result.outboundMessages()).hasSize(1);
        assertThat(result.outboundMessages().get(0)).isInstanceOf(CreateSurface.class);
        assertThat(handler.routes).isEmpty();
    }

    @Test
    void invalidEnvelopeRejectedWithoutDispatch() {
        // 白名单外组件导致 schema 校验失败
        String text = """
                <a2ui-json>[{"version":"1.0","updateComponents":{"surfaceId":"s1",
                 "components":[{"id":"x","component":"GhostWidget"}]}}]</a2ui-json>
                """;
        RecordingHandler handler = new RecordingHandler();
        A2uiParseResult result = parser.parse(text, "s1", handler);

        assertThat(result.a2uiBlockFound()).isTrue();
        assertThat(result.validationIssues()).isNotEmpty();
        assertThat(handler.routes).isEmpty();
    }
}
