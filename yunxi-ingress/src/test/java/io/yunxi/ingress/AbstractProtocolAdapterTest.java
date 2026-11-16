package io.yunxi.ingress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.UserMessage;
import io.yunxi.runtime.AgentCommand;
import io.yunxi.runtime.Settle;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 入口层翻译验证：{@link AbstractProtocolAdapter} 守护「协议请求 → 运行时命令」与「结算 → 协议响应」的字段映射。
 * 入口织面是产品平面最易写错处（字段错位下游全错），故用纯单元精准锁定映射，不依赖大模型。
 */
class AbstractProtocolAdapterTest {

    private final AbstractProtocolAdapter adapter = new WebProtocolAdapter();

    @Test
    void toCommand_mapsRequestFields_intoProtocolAgnosticCommand() {
        ConversationRequest req = new ConversationRequest("web", "agent-1", "user-1", "sess-1", "hello");
        req.setContext(Map.of("k", "v"));
        AgentCommand cmd = adapter.toCommand(req);

        assertEquals("agent-1", cmd.agentId());
        assertEquals("sess-1", cmd.conversationId());
        assertEquals("user-1", cmd.userId());
        assertEquals("sess-1", cmd.sessionId());
        assertEquals(1, cmd.messages().size());
        assertTrue(cmd.messages().get(0) instanceof UserMessage, "文本必须转成单条用户消息");
        assertEquals("hello", ((UserMessage) cmd.messages().get(0)).getTextContent());
        assertEquals(Map.of("k", "v"), cmd.contextData(), "业务上下文必须透传");
    }

    @Test
    void toCommand_carriesResolvedIntentLabel() {
        ConversationRequest req =
                new ConversationRequest("web", "resident-nutrition-assistant", "user-1", "sess-1", "血糖偏高怎么吃");
        req.setContext(Map.of("yunxi.intent.label", "nutrition", "yunxi.intent.confidence", "0.92"));

        AgentCommand cmd = adapter.toCommand(req);

        assertEquals("nutrition", cmd.intent(), "网关解析出的意图标签必须透传到执行侧命令");
    }

    @Test
    void toCommand_intentStaysNullWhenNotResolved() {
        ConversationRequest req = new ConversationRequest("web", "agent-1", "user-1", "sess-1", "hello");

        assertNull(adapter.toCommand(req).intent(), "未经网关解析的请求不得伪造意图标签");
    }

    @Test
    void toResponse_mapsSettleFields_intoProtocolResponse() {
        ConversationRequest req = new ConversationRequest("web", "agent-1", "user-1", "sess-1", "hello");
        AgentCommand cmd = adapter.toCommand(req);
        Settle settle = Settle.ok(cmd, "reply text");

        ConversationResponse resp = adapter.toResponse(settle, "reply text");
        assertEquals("agent-1", resp.getAgentId());
        assertEquals("sess-1", resp.getSessionId());
        assertEquals("reply text", resp.getText());
        assertEquals(Settle.Status.OK, resp.getStatus());
        assertNull(resp.getErrorCode(), "成功结算错误码必须为空");
    }
}
