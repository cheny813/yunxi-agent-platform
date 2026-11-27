package io.yunxi.a2ui;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import java.util.EnumSet;
import java.util.Set;
import reactor.core.publisher.Mono;

/**
 * A2UI prompt 注入中间件：把 a2ui4j 生成的协议说明追加到 Agent 的 system prompt，
 * 使模型知道如何产出 <a2ui-json> 哨兵块。
 *
 * <p>协议内容全部来自 a2ui4j 的 CatalogPromptBuilder（单一真相源），本类只负责
 * 经框架原生 onSystemPrompt 扩展点接线，不拼装/复制任何协议规则。默认不注册，
 * 由 A2uiAgentBuildContributor 按 per-Agent 声明或全局开关显式启用。
 */
public class A2uiPromptMiddleware implements MiddlewareBase {

    private final String a2uiPrompt;

    public A2uiPromptMiddleware(String a2uiPrompt) {
        this.a2uiPrompt = a2uiPrompt;
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Set<ExtensionPoint> activePoints() {
        return EnumSet.of(ExtensionPoint.ON_SYSTEM_PROMPT);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        if (a2uiPrompt == null || a2uiPrompt.isBlank()) {
            return Mono.justOrEmpty(currentPrompt);
        }
        if (currentPrompt == null || currentPrompt.isBlank()) {
            return Mono.just(a2uiPrompt);
        }
        return Mono.just(currentPrompt + "\n\n" + a2uiPrompt);
    }
}
