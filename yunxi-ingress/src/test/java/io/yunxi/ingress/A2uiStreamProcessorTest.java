package io.yunxi.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * A2UI 流末分流契约：分流器无配置态——正文无哨兵块时不动作；有哨兵块则经 a2ui4j
 * 校验后下发（校验不通过整批不下发消息、仅透传问题，fail closed）。启用与否由
 * 注入侧 per-Agent 开关决定：未被提示的 Agent 不会输出哨兵块，故分流器无需知道配置。
 */
class A2uiStreamProcessorTest {

    @Test
    void processorIgnoresPlainText() {
        A2uiStreamProcessor processor = new A2uiStreamProcessor();

        assertThat(processor.split("今天天气不错。", "s1")).isNull();
        assertThat(processor.split(null, "s1")).isNull();
    }

    @Test
    void validBlockProducesOutboundPayload() {
        A2uiStreamProcessor processor = new A2uiStreamProcessor();
        JsonNode payload = processor.split("""
                界面如下：<a2ui-json>[{"version":"1.0","createSurface":
                {"surfaceId":"s1","catalogId":"acme:basic"}}]</a2ui-json>
                """, "s1");

        assertThat(payload).isNotNull();
        assertThat(payload.path("surfaceId").asText()).isEqualTo("s1");
        assertThat(payload.path("messages")).hasSize(1);
        assertThat(payload.path("messages").get(0).path("createSurface").path("surfaceId").asText())
                .isEqualTo("s1");
        assertThat(payload.path("issues")).isEmpty();
    }

    @Test
    void invalidBlockFailsClosedWithIssuesOnly() {
        A2uiStreamProcessor processor = new A2uiStreamProcessor();
        JsonNode payload = processor.split("""
                <a2ui-json>[{"version":"1.0","updateComponents":{"surfaceId":"s1",
                 "components":[{"id":"x","component":"GhostWidget"}]}}]</a2ui-json>
                """, "s1");

        assertThat(payload).isNotNull();
        assertThat(payload.path("messages")).isEmpty();
        assertThat(payload.path("issues")).isNotEmpty();
    }
}
