package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.Model;
import reactor.core.publisher.Flux;

/**
 * SpanAttributeMiddleware：模型与技能属性登记测试。
 */
@DisplayName("SpanAttributeMiddleware 属性补充")
class SpanAttributeMiddlewareTest {

    private final SpanAttributeMiddleware middleware = new SpanAttributeMiddleware();

    private RuntimeContext ctx;
    private Agent agent;

    @BeforeEach
    void setUp() {
        ctx = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        agent = null;
    }

    private static Function<ModelCallInput, Flux<AgentEvent>> modelChain() {
        return in -> Flux.empty();
    }

    private static Function<ActingInput, Flux<AgentEvent>> actingChain() {
        return in -> Flux.empty();
    }

    private static ActingInput actingInput(List<ToolUseBlock> calls) {
        return new ActingInput(calls);
    }

    private static ToolUseBlock toolCall(String id, String name, Map<String, Object> input) {
        ToolUseBlock block = mock(ToolUseBlock.class);
        when(block.getId()).thenReturn(id);
        when(block.getName()).thenReturn(name);
        when(block.getInput()).thenReturn(input);
        return block;
    }

    @Test
    @DisplayName("模型调用：读取入参中的模型名写入属性")
    void registersModelFromInput() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("qwen-max");
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, model);

        middleware.onModelCall(agent, ctx, input, modelChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).snapshot())
                .containsEntry(SpanAttributes.MODEL, "qwen-max");
    }

    @Test
    @DisplayName("提供方由实现类名推导；类名非标识符形式时不写入")
    void providerDerivation() {
        // 代理类名含 '$'，非纯标识符，推导结果不可信，应不写入而非写入错误值
        Model proxy = mock(Model.class);
        when(proxy.getModelName()).thenReturn("qwen-max");

        middleware.onModelCall(agent, ctx,
                new ModelCallInput(List.of(), List.of(), null, proxy), modelChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).snapshot())
                .containsEntry(SpanAttributes.MODEL, "qwen-max")
                .doesNotContainKey(SpanAttributes.PROVIDER);
    }

    @Test
    @DisplayName("模型调用：模型为空时不写入且不抛异常")
    void nullModelIsSafe() {
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, null);

        middleware.onModelCall(agent, ctx, input, modelChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("动作阶段：查看技能类工具记录技能归属")
    void registersSkillForViewTool() {
        ToolUseBlock call = toolCall("call-1", "read_skill", Map.of("skillId", "skill-a"));

        middleware.onActing(agent, ctx, actingInput(List.of(call)), actingChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).skillOf("call-1"))
                .containsEntry(SpanAttributes.SKILL, "skill-a")
                .containsEntry(SpanAttributes.SKILL_SOURCE, "view");
    }

    @Test
    @DisplayName("动作阶段：使用技能类工具记录为 use")
    void registersSkillForUseTool() {
        ToolUseBlock call = toolCall("call-2", "use_skill", Map.of("skill_id", "skill-b"));

        middleware.onActing(agent, ctx, actingInput(List.of(call)), actingChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).skillOf("call-2"))
                .containsEntry(SpanAttributes.SKILL, "skill-b")
                .containsEntry(SpanAttributes.SKILL_SOURCE, "use");
    }

    @Test
    @DisplayName("动作阶段：非技能工具不记录")
    void ignoresNonSkillTool() {
        ToolUseBlock call = toolCall("call-3", "calculator", Map.of("skillId", "should-not-record"));

        middleware.onActing(agent, ctx, actingInput(List.of(call)), actingChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).skillOf("call-3")).isNull();
    }

    @Test
    @DisplayName("动作阶段：技能标识按 skillId / skill_id / name 顺序回退提取")
    void skillNameFallbackOrder() {
        ToolUseBlock bySkillId = toolCall("c1", "read_skill",
                Map.of("skillId", "from-skill-id", "name", "from-name"));
        ToolUseBlock bySnakeCase = toolCall("c2", "read_skill", Map.of("skill_id", "from-snake"));
        ToolUseBlock byName = toolCall("c3", "read_skill", Map.of("name", "from-name"));

        middleware.onActing(agent, ctx,
                actingInput(List.of(bySkillId, bySnakeCase, byName)), actingChain()).blockLast();

        SpanAttributeBag bag = SpanAttributeBag.of(ctx);
        assertThat(bag.skillOf("c1")).containsEntry(SpanAttributes.SKILL, "from-skill-id");
        assertThat(bag.skillOf("c2")).containsEntry(SpanAttributes.SKILL, "from-snake");
        assertThat(bag.skillOf("c3")).containsEntry(SpanAttributes.SKILL, "from-name");
    }

    @Test
    @DisplayName("属性袋按调用隔离，不同上下文互不干扰")
    void attributesIsolatedAcrossCalls() {
        RuntimeContext other = RuntimeContext.builder().userId("u1").sessionId("s1").build();
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("qwen-max");

        middleware.onModelCall(agent, ctx,
                new ModelCallInput(List.of(), List.of(), null, model), modelChain()).blockLast();

        assertThat(SpanAttributeBag.of(ctx).snapshot()).containsKey(SpanAttributes.MODEL);
        assertThat(SpanAttributeBag.of(other).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("登记过程不改变事件流：原样透传")
    void eventStreamUnchanged() {
        AgentEvent event = mock(AgentEvent.class);
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("qwen-max");

        List<AgentEvent> emitted = middleware.onModelCall(agent, ctx,
                        new ModelCallInput(List.of(), List.of(), null, model),
                        in -> Flux.just(event))
                .collectList().block();

        assertThat(emitted).containsExactly(event);
    }
}
