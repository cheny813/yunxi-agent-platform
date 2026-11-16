package io.yunxi.runtime.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

/**
 * 意图信号透传：IntentSignalMiddleware 把入口写入 RuntimeContext 属性 {@code yunxi.intent.label}
 * 的意图标签搬到模型调用 options 的 {@code yunxi_user_intent}（TaskSignals 消费键），
 * 使 AutoModelProvider 的按请求选型能拿到意图信号。标签缺失或空白时不得改写任何 options
 * （fail-open：规则式选型不受影响）。
 */
class IntentSignalMiddlewareTest {

    private static final String CTX_KEY = "yunxi.intent.label";
    private static final String SIGNAL_KEY = "yunxi_user_intent";

    private final Agent agent = Mockito.mock(Agent.class);
    private final Model model = Mockito.mock(Model.class);

    @Test
    void passesIntentLabelIntoModelCallOptions() {
        RuntimeContext ctx = RuntimeContext.builder().put(CTX_KEY, "翻译").build();
        GenerateOptions options = GenerateOptions.builder().temperature(0.3).build();
        ModelCallInput input =
                new ModelCallInput(List.of(), List.of(), options, model);

        ModelCallInput observed = invokeAndCapture(ctx, input);

        assertEquals("翻译", observed.options().getAdditionalBodyParams().get(SIGNAL_KEY));
        // 原有标量字段在合并后必须保留
        assertEquals(0.3, observed.options().getTemperature());
    }

    @Test
    void preservesExistingAdditionalBodyParams() {
        RuntimeContext ctx = RuntimeContext.builder().put(CTX_KEY, "翻译").build();
        GenerateOptions options = GenerateOptions.builder()
                .additionalBodyParam("vendor.existing", 42)
                .build();
        ModelCallInput input =
                new ModelCallInput(List.of(), List.of(), options, model);

        ModelCallInput observed = invokeAndCapture(ctx, input);

        assertEquals(42, observed.options().getAdditionalBodyParams().get("vendor.existing"));
        assertEquals("翻译", observed.options().getAdditionalBodyParams().get(SIGNAL_KEY));
    }

    @Test
    void passesThroughWhenContextHasNoLabel() {
        RuntimeContext ctx = RuntimeContext.empty();
        GenerateOptions options = GenerateOptions.builder().temperature(0.3).build();
        ModelCallInput input =
                new ModelCallInput(List.of(), List.of(), options, model);

        ModelCallInput observed = invokeAndCapture(ctx, input);

        assertFalse(observed.options().getAdditionalBodyParams().containsKey(SIGNAL_KEY));
        assertEquals(0.3, observed.options().getTemperature());
    }

    @Test
    void passesThroughWhenLabelIsBlank() {
        RuntimeContext ctx = RuntimeContext.builder().put(CTX_KEY, "   ").build();
        ModelCallInput input =
                new ModelCallInput(List.of(), List.of(), GenerateOptions.builder().build(), model);

        ModelCallInput observed = invokeAndCapture(ctx, input);

        assertFalse(observed.options().getAdditionalBodyParams().containsKey(SIGNAL_KEY));
    }

    @Test
    void passesThroughWhenOptionsAreNull() {
        RuntimeContext ctx = RuntimeContext.builder().put(CTX_KEY, "翻译").build();
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), null, model);

        ModelCallInput observed = invokeAndCapture(ctx, input);

        assertNull(observed.options());
    }

    @Test
    void onlyActiveAtModelCallPoint() {
        IntentSignalMiddleware middleware = new IntentSignalMiddleware();
        assertEquals(1, middleware.activePoints().size());
        assertTrue(middleware.activePoints()
                .contains(io.agentscope.core.middleware.MiddlewareBase.ExtensionPoint.ON_MODEL_CALL));
    }

    private ModelCallInput invokeAndCapture(RuntimeContext ctx, ModelCallInput input) {
        final ModelCallInput[] observed = new ModelCallInput[1];
        Function<ModelCallInput, Flux<AgentEvent>> next = observedInput -> {
            observed[0] = observedInput;
            return Flux.empty();
        };
        new IntentSignalMiddleware().onModelCall(agent, ctx, input, next).blockLast();
        assertSame(input.messages(), observed[0].messages());
        return observed[0];
    }
}
