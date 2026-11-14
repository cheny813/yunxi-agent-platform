package io.yunxi.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import static org.mockito.Mockito.mock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskSignals#from} 的提取逻辑：纯文本 token 估算（字符数 / 4）、工具密集判定、
 * 透传意图提取、以及空 / null 输入的安全降级。
 */
class TaskSignalsTest {

    private Msg text(String t) {
        return Msg.builder().role(MsgRole.USER).textContent(t).build();
    }

    @Test
    void fromEstimatesTokensAsCharsDiv4AndDefaultsToText() {
        TaskSignals s = TaskSignals.from(List.of(text("abcdefgh")), null, null);

        assertEquals(TaskSignals.MediaType.TEXT, s.mediaType());
        assertEquals(2, s.estimatedTokens()); // 8 chars / 4
        assertFalse(s.toolHeavy());
        assertNull(s.userIntent());
    }

    @Test
    void fromSumsAcrossMultipleMessages() {
        // 跨多条消息累加文本长度后估算 token（5 chars / 4 = 1）。
        TaskSignals s = TaskSignals.from(List.of(text("abc"), text("de")), null, null);

        assertEquals(1, s.estimatedTokens());
    }

    @Test
    void fromMarksToolHeavyWhenToolsPresent() {
        TaskSignals s = TaskSignals.from(List.of(text("hi")), List.of(mock(ToolSchema.class)), null);

        assertTrue(s.toolHeavy());
    }

    @Test
    void fromExtractsUserIntentFromAdditionalBodyParams() {
        GenerateOptions options =
                GenerateOptions.builder().additionalBodyParam("yunxi_user_intent", "translate").build();

        TaskSignals s = TaskSignals.from(List.of(text("hi")), null, options);

        assertEquals("translate", s.userIntent());
    }

    @Test
    void fromIgnoresBlankUserIntent() {
        GenerateOptions options =
                GenerateOptions.builder().additionalBodyParam("yunxi_user_intent", "   ").build();

        TaskSignals s = TaskSignals.from(List.of(text("hi")), null, options);

        assertNull(s.userIntent());
    }

    @Test
    void fromHandlesNullMessagesAndNullTools() {
        TaskSignals s = TaskSignals.from(null, null, null);

        assertEquals(TaskSignals.MediaType.TEXT, s.mediaType());
        assertEquals(0, s.estimatedTokens());
        assertFalse(s.toolHeavy());
        assertNull(s.userIntent());
    }

    @Test
    void fromSkipsNullMessages() {
        TaskSignals s = TaskSignals.from(Arrays.asList(null, text("abcd")), null, null);

        assertEquals(1, s.estimatedTokens());
    }
}
