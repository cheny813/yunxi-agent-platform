package io.yunxi.ingress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2ui4j.core.codec.A2uiCodec;
import io.a2ui4j.core.dispatch.RendererToAgentHandler;
import io.a2ui4j.core.message.CallAgentFunction;
import io.a2ui4j.core.message.ErrorMessage;
import io.a2ui4j.core.message.RendererFunctionResponseMessage;
import io.a2ui4j.core.spec.SpecSchemaValidator;
import io.yunxi.a2ui.A2uiParseResult;
import io.yunxi.a2ui.A2uiResponseParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2UI 流末分流器（纯传输层组件）：会话流结束时对最终正文做一次哨兵块解析，
 * 把经 a2ui4j 校验的 Agent→Renderer 消息整理为 {@code a2ui-message} 事件载荷。
 *
 * <p>协议解析与校验的唯一实现位置在 a2ui4j（经 {@link A2uiResponseParser}）；
 * 本类只做「取最终文本 → 调库 → 组装载荷」，不复制任何协议语义。正文无哨兵块时
 * 不产生载荷（不下发事件）；校验不通过时载荷只含 issues 不含消息（fail closed）。
 * 入站（Renderer→Agent）消息不产生自模型正文，出现即忽略。</p>
 *
 * <p>本类无配置态：是否启用 A2UI 由注入侧 per-Agent 开关决定——未被提示的 Agent
 * 不会输出哨兵块，故分流器只在「出现哨兵块」时动作，不重复判定开关（单一真相源）。
 * 由应用装配点（RuntimeConfiguration）声明为 Bean。</p>
 */
public class A2uiStreamProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final A2uiResponseParser parser = new A2uiResponseParser(
            SpecSchemaValidator.bundled(), new A2uiCodec());

    public A2uiStreamProcessor() {
    }

    /**
     * 流末分流：返回 {@code a2ui-message} 事件载荷，或 {@code null} 表示不下发事件。
     *
     * @param finalText 模型最终正文（流式侧为全部正文增量的累计）
     * @param surfaceId 会话 surface 标识（取会话 id，缺省回退 main）
     */
    public JsonNode split(String finalText, String surfaceId) {
        if (finalText == null || !finalText.contains("<a2ui-json>")) {
            return null;
        }

        A2uiParseResult result = parser.parse(finalText, surfaceId, new IgnoringInboundHandler());
        if (!result.a2uiBlockFound()) {
            return null;
        }

        List<JsonNode> messages = new ArrayList<>();
        for (var message : result.outboundMessages()) {
            try {
                messages.add(MAPPER.readTree(new A2uiCodec().encode(message)));
            } catch (Exception e) {
                throw new IllegalStateException("出站消息编码失败", e);
            }
        }
        List<String> issues = new ArrayList<>();
        result.validationIssues().forEach(issue -> issues.add(issue.toString()));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("surfaceId", surfaceId);
        payload.put("messages", messages);
        payload.put("issues", issues);
        return MAPPER.valueToTree(payload);
    }

    /** 模型正文不应产生入站消息；万一出现则忽略，不影响出站分流。 */
    private static final class IgnoringInboundHandler implements RendererToAgentHandler {
        @Override
        public void onAction(io.a2ui4j.core.message.Action message) {
            // 模型正文中的入站消息不属于本次出站分流职责，忽略
        }

        @Override
        public void onCallAgentFunction(CallAgentFunction message) {
            // 同上
        }

        @Override
        public void onRendererFunctionResponse(RendererFunctionResponseMessage message) {
            // 同上
        }

        @Override
        public void onError(ErrorMessage message) {
            // 同上
        }
    }
}
