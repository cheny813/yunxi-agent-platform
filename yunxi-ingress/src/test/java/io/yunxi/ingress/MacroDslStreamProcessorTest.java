package io.yunxi.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * macro-dsl 流末分流契约：从正文提取 &lt;macro-a2ui&gt; 哨兵块（@macroui DSL 文本），
 * 组装 SSE 命名事件载荷。DSL 解析/校验由前端 @macroui/a2ui 负责（其 parser 内建
 * maxBuffer/maxDepth 资源保护），后端只做传输层提取，不复制任何 DSL 语义。
 */
class MacroDslStreamProcessorTest {

    @Test
    void ignoresPlainTextAndGoogleA2uiBlock() {
        MacroDslStreamProcessor processor = new MacroDslStreamProcessor();

        assertThat(processor.split("今天天气不错。")).isNull();
        assertThat(processor.split(null)).isNull();
        assertThat(processor.split("<a2ui-json>[{}]</a2ui-json>")).isNull();
    }

    @Test
    void extractsMacroDslBlock() {
        MacroDslStreamProcessor processor = new MacroDslStreamProcessor();

        JsonNode payload = processor.split("""
                报表如下：<macro-a2ui>[card tt:周报][table][tr 日期 数值][/table][/card]</macro-a2ui>
                """);

        assertThat(payload).isNotNull();
        assertThat(payload.path("dsl").asText())
                .isEqualTo("[card tt:周报][table][tr 日期 数值][/table][/card]");
    }

    @Test
    void multipleBlocksAreConcatenatedInOrder() {
        MacroDslStreamProcessor processor = new MacroDslStreamProcessor();

        JsonNode payload = processor.split("<macro-a2ui>[p A]</macro-a2ui><macro-a2ui>[p B]</macro-a2ui>");

        assertThat(payload).isNotNull();
        assertThat(payload.path("dsl").asText()).isEqualTo("[p A][p B]");
    }

    @Test
    void unclosedBlockIsIgnored() {
        MacroDslStreamProcessor processor = new MacroDslStreamProcessor();
        assertThat(processor.split("正文<macro-a2ui>[card tt:未闭合")).isNull();
    }
}
