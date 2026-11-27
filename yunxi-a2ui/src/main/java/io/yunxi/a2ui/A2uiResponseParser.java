package io.yunxi.a2ui;

import io.a2ui4j.core.codec.A2uiCodec;
import io.a2ui4j.core.dispatch.InboundDispatcher;
import io.a2ui4j.core.dispatch.RendererToAgentHandler;
import io.a2ui4j.core.format.A2uiPart;
import io.a2ui4j.core.format.DirectJsonParser;
import io.a2ui4j.core.format.ResponsePart;
import io.a2ui4j.core.format.TextPart;
import io.a2ui4j.core.message.A2uiEnvelope;
import io.a2ui4j.core.message.AgentToRendererMessage;
import io.a2ui4j.core.message.RendererToAgentMessage;
import io.a2ui4j.core.spec.SpecSchemaValidator;
import io.a2ui4j.core.spec.ValidationIssue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.a2ui4j.core.A2uiValidationException;
import java.util.ArrayList;
import java.util.List;

/**
 * A2UI 响应解析器：把模型最终文本中的 <a2ui-json> 哨兵块经 a2ui4j 解析、按消息
 * 方向校验后分流——Agent→Renderer 消息（如 createSurface）收集为出站载荷由宿主
 * 转发渲染端；Renderer→Agent 消息经 {@link InboundDispatcher} 分发给宿主 handler。
 *
 * <p>协议规则（解析、normalize、校验、白名单）全部来自 a2ui4j 公开 API——本类
 * 只做「取文本 → 调库 → 分流」的接线，不复制任何协议内容。无哨兵块时文本原样
 * 保留、不分发（软通过）；任一消息校验不通过则整批不分发、不收集（fail closed）。
 */
public class A2uiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SpecSchemaValidator validator;
    private final A2uiCodec codec;
    private final DirectJsonParser parser = new DirectJsonParser();

    public A2uiResponseParser(SpecSchemaValidator validator, A2uiCodec codec) {
        this.validator = validator;
        this.codec = codec;
    }

    /**
     * 解析模型文本并按消息方向校验、分流。
     *
     * @param text      模型最终文本
     * @param surfaceId 当前会话 surface 标识（normalize 修复用）
     * @param handler   宿主入站（Renderer→Agent）消息接收方
     */
    public A2uiParseResult parse(String text, String surfaceId, RendererToAgentHandler handler) {
        List<ResponsePart> parts = parser.parseResponse(text == null ? "" : text);
        StringBuilder remaining = new StringBuilder();
        List<A2uiEnvelope> inbound = new ArrayList<>();
        boolean found = false;
        for (ResponsePart part : parts) {
            if (part instanceof TextPart tp) {
                remaining.append(tp.text());
            } else if (part instanceof A2uiPart ap) {
                found = true;
                inbound.addAll(ap.a2ui());
            }
        }

        if (!found) {
            return new A2uiParseResult(false, remaining.toString(), List.of(), List.of());
        }

        List<ValidationIssue> issues = new ArrayList<>();
        List<A2uiEnvelope> outbound = new ArrayList<>();
        List<RendererToAgentMessage> rendererToAgent = new ArrayList<>();
        for (A2uiEnvelope message : inbound) {
            JsonNode envelope;
            try {
                envelope = MAPPER.readTree(codec.encode(message));
            } catch (JsonProcessingException e) {
                throw new A2uiValidationException("codec output could not be re-parsed", e);
            }
            if (message instanceof AgentToRendererMessage) {
                issues.addAll(validator.validateAgentToRenderer(envelope));
                outbound.add(message);
            } else if (message instanceof RendererToAgentMessage) {
                issues.addAll(validator.validateRendererToAgent(envelope));
                rendererToAgent.add((RendererToAgentMessage) message);
            }
        }
        if (!issues.isEmpty()) {
            return new A2uiParseResult(true, remaining.toString(), List.copyOf(issues), List.of());
        }

        InboundDispatcher dispatcher = new InboundDispatcher();
        for (RendererToAgentMessage message : rendererToAgent) {
            dispatcher.dispatch(message, handler);
        }
        return new A2uiParseResult(true, remaining.toString(), List.of(), List.copyOf(outbound));
    }
}
