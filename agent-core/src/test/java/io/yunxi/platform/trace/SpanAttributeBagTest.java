package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;

/**
 * SpanAttributeBag：中间件侧补充属性的写入、读取与隔离测试。
 */
@DisplayName("SpanAttributeBag 轨迹属性袋")
class SpanAttributeBagTest {

    private static RuntimeContext context() {
        return RuntimeContext.builder().userId("u1").sessionId("s1").build();
    }

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        ToolUseBlock block = mock(ToolUseBlock.class);
        when(block.getId()).thenReturn(id);
        when(block.getName()).thenReturn(name);
        when(block.getInput()).thenReturn(input);
        return block;
    }

    @Test
    @DisplayName("同一上下文多次取用得到同一实例")
    void sameContextSameInstance() {
        RuntimeContext ctx = context();

        SpanAttributeBag first = SpanAttributeBag.of(ctx);
        SpanAttributeBag second = SpanAttributeBag.of(ctx);

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("不同上下文的属性互不可见")
    void differentContextsIsolated() {
        RuntimeContext ctxA = context();
        RuntimeContext ctxB = context();

        SpanAttributeBag.of(ctxA).putModel("model-a", "provider-a");
        SpanAttributeBag.of(ctxB).putModel("model-b", "provider-b");

        assertThat(SpanAttributeBag.of(ctxA).snapshot())
                .containsEntry(SpanAttributes.MODEL, "model-a");
        assertThat(SpanAttributeBag.of(ctxB).snapshot())
                .containsEntry(SpanAttributes.MODEL, "model-b");
    }

    @Test
    @DisplayName("写入模型与提供方")
    void putModel() {
        SpanAttributeBag bag = SpanAttributeBag.of(context());

        bag.putModel("qwen-max", "DashScope");

        assertThat(bag.snapshot())
                .containsEntry(SpanAttributes.MODEL, "qwen-max")
                .containsEntry(SpanAttributes.PROVIDER, "DashScope");
        assertThat(bag.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("模型名或提供方为空时不写入")
    void putModelIgnoresBlank() {
        SpanAttributeBag bag = SpanAttributeBag.of(context());

        bag.putModel("  ", null);

        assertThat(bag.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("技能归属按工具调用标识记录并可分别读取")
    void putSkillPerToolCall() {
        SpanAttributeBag bag = SpanAttributeBag.of(context());
        ToolUseBlock call1 = toolCall("call-1", "read_skill", Map.of("skillId", "skill-a"));
        ToolUseBlock call2 = toolCall("call-2", "use_skill", Map.of("skill_id", "skill-b"));

        bag.putSkill(call1, "skill-a", "view");
        bag.putSkill(call2, "skill-b", "use");

        assertThat(bag.skillOf("call-1"))
                .containsEntry(SpanAttributes.SKILL, "skill-a")
                .containsEntry(SpanAttributes.SKILL_SOURCE, "view");
        assertThat(bag.skillOf("call-2"))
                .containsEntry(SpanAttributes.SKILL, "skill-b")
                .containsEntry(SpanAttributes.SKILL_SOURCE, "use");
        assertThat(bag.skillOf("call-unknown")).isNull();
        assertThat(bag.skillOf(null)).isNull();
    }

    @Test
    @DisplayName("技能名为空时不记录")
    void putSkillIgnoresBlank() {
        SpanAttributeBag bag = SpanAttributeBag.of(context());

        bag.putSkill(toolCall("call-1", "read_skill", Map.of()), "  ", "view");
        bag.putSkill(null, "skill-a", "view");

        assertThat(bag.skillOf("call-1")).isNull();
    }

    @Test
    @DisplayName("快照是副本，修改不影响内部状态")
    void snapshotIsCopy() {
        SpanAttributeBag bag = SpanAttributeBag.of(context());
        bag.putModel("model-a", "p");

        Map<String, Object> snapshot = bag.snapshot();
        snapshot.put("injected", "value");

        assertThat(bag.snapshot()).doesNotContainKey("injected");
    }

    @Test
    @DisplayName("上下文为空时写入不抛异常")
    void nullContextIsSafe() {
        assertThatCode(() -> {
            SpanAttributeBag bag = SpanAttributeBag.of(null);
            bag.putModel("m", "p");
            bag.putSkill(toolCall("c", "read_skill", Map.of("skillId", "s")), "s", "view");
        }).doesNotThrowAnyException();
    }
}
