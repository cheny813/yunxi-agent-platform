package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 把入口解析出的意图标签透传到模型调用层：读取 {@link RuntimeContext} 属性
 * {@code yunxi.intent.label}（入口意图引擎写入，经执行链路 callerContext 带入），
 * 以 {@code yunxi_user_intent} 键合并进模型调用的 {@link GenerateOptions}，供按请求选型
 * （AutoModelProvider 经 TaskSignals）消费。标签缺失或空白时原样放行，不影响规则式选型。
 *
 * <p>归属：意图信号的传递是横切关切，按扩展点形态落在本运行时平面，模型与推理链路零感知。
 */
public class IntentSignalMiddleware implements MiddlewareBase {

    /** 入口写入 RuntimeContext 的意图标签属性键，与入口上下文键同名。 */
    public static final String CTX_KEY = "yunxi.intent.label";

    /** 模型调用 options 中的意图信号键，TaskSignals 消费键。 */
    public static final String SIGNAL_KEY = "yunxi_user_intent";

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_MODEL_CALL);
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        Object label = ctx == null ? null : ctx.get(CTX_KEY);
        if (!(label instanceof String intent) || intent.isBlank() || input.options() == null) {
            return next.apply(input);
        }
        GenerateOptions merged = GenerateOptions.mergeOptions(
                GenerateOptions.builder().additionalBodyParam(SIGNAL_KEY, intent.strip()).build(),
                input.options());
        return next.apply(new ModelCallInput(
                input.messages(), input.tools(), merged, input.model()));
    }
}
