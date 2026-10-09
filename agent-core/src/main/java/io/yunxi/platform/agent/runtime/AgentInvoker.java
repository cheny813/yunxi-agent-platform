package io.yunxi.platform.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 全平台唯一允许触达 AgentScope-Java Agent 调用的门面（调用组合根）。
 *
 * <p>所有协议入口（web/A2A、AG-UI、Chat Completions、aistio）发起 Agent 调用时，
 * 必须经本门面，不得在别处直接调用 {@code HarnessAgent.streamEvents} / {@code call}
 * 或 {@code ReActAgent.call}。这样可保证每次调用都统一经过共享 Agent 上的 Middleware 链
 * （含会话级工具组激活），且携带框架原生 RuntimeContext（身份 / 会话 / 调用级上下文）。
 *
 * <p>本类不含任何业务编排逻辑，仅做调用收口与类型转发。新增协议入口时只能调用本门面，
 * 从而结构性地避免「漏注入身份 / 漏激活会话级工具组」这类跨协议不对称缺陷。
 */
public final class AgentInvoker {

    private AgentInvoker() {
    }

    public static Flux<AgentEvent> streamEvents(
            HarnessAgent agent, List<Msg> messages, RuntimeContext ctx) {
        return agent.streamEvents(messages, ctx);
    }

    public static Mono<Msg> call(HarnessAgent agent, Msg message, RuntimeContext ctx) {
        return agent.call(message, ctx);
    }

    public static Mono<Msg> call(
            HarnessAgent agent, Msg message, JsonNode schema, RuntimeContext ctx) {
        return agent.call(List.of(message), schema, ctx);
    }

    public static Mono<Msg> call(
            HarnessAgent agent, Msg message, Class<?> structuredModel, RuntimeContext ctx) {
        return agent.call(List.of(message), structuredModel, ctx);
    }

    /**
     * Chat Completions（OpenAI 兼容）协议专用：该协议入口覆盖框架默认 Controller，使用裸
     * {@code ReActAgent}，其 {@code call(List)} 不接受 RuntimeContext（无法注入调用方身份）。
     * 收口到此方法仅为满足「唯一调用门面」结构约束；该协议入口因使用裸 ReActAgent、
     * 无法携带调用方身份（userId），此身份注入缺口需改走共享装配 Agent 后才能闭环。
     */
    public static Mono<Msg> call(ReActAgent agent, List<Msg> messages) {
        return agent.call(messages);
    }
}
