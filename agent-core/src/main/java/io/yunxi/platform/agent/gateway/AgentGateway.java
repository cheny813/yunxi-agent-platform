package io.yunxi.platform.agent.gateway;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.yunxi.platform.agent.CallOptions;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 网关，提供统一的 Agent 调用入口。
 *
 * <p>
 * V2.0-RC3: 使用 {@code agent.streamEvents()} 替代已废弃的 {@code agent.stream()}。
 * {@code streamEvents()} 由 {@link io.agentscope.harness.agent.HarnessAgent} 自身实现
 * （不在 {@code Agent} 接口上）。
 * </p>
 */
public interface AgentGateway {

    /**
     * 同步调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称，对应 YAML 定义的 agent-name
     * @param message   用户输入文本
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message);

    /**
     * 流式调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户输入文本
     * @return Agent 响应文本流，每项为一个文本片段
     */
    Flux<String> callStream(String agentName, String message);

    /**
     * 同步调用 Agent（指定 Profile）。
     * <p>
     * Profile 用于选择 Agent 的角色配置（如不同人格或技能组合），
     * 名称解析为 {@code agentName#profile}。
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Agent Profile 标识
     * @param message   用户输入文本
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String profile, String message);

    /**
     * 同步调用 Agent（指定调用选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户输入文本
     * @param options   调用选项（超时、Profile、上下文等）
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message, CallOptions options);

    /**
     * 流式调用 Agent（指定调用选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户输入文本
     * @param options   调用选项（超时、Profile、上下文等）
     * @return Agent 响应文本流，每项为一个文本片段
     */
    Flux<String> callStream(String agentName, String message, CallOptions options);
}

/**
 * Agent 网关实现类。
 *
 * <p>
 * 封装 Agent 调用的核心逻辑，包括 Agent 查找、名称解析、超时处理等。
 * 所有调用最终都委托给 AgentService 获取的 Agent 实例执行。
 * </p>
 *
 * <p>
 * V2.0-RC3: 流式调用使用 {@code streamEvents()} 替代废弃的 {@code stream()}。
 * </p>
 */
@Component
class AgentGatewayImpl implements AgentGateway {

    private static final Logger log = LoggerFactory.getLogger(AgentGatewayImpl.class);

    private final AgentService agentService;
    private final AgentDefinitionLoader definitionLoader;

    public AgentGatewayImpl(AgentService agentService, AgentDefinitionLoader definitionLoader) {
        this.agentService = agentService;
        this.definitionLoader = definitionLoader;
    }

    @Override
    public Mono<String> call(String agentName, String message) {
        return call(agentName, message, new CallOptions());
    }

    @Override
    public Flux<String> callStream(String agentName, String message) {
        return callStream(agentName, message, new CallOptions());
    }

    @Override
    public Mono<String> call(String agentName, String profile, String message) {
        return call(agentName, message, new CallOptions().setProfile(profile));
    }

    /**
     * 同步调用核心实现。
     * <p>
     * 流程：获取 Agent 实例 → 解析超时 → 调用 {@code agent.call()} → 提取文本响应。
     * </p>
     */
    @Override
    public Mono<String> call(String agentName, String message, CallOptions options) {
        return getAgent(agentName, options)
                .flatMap(agent -> {
                    Duration timeout = resolveTimeout(agentName, options);
                    return agent.call(Msg.builder().textContent(message).build()).timeout(timeout);
                })
                .map(Msg::getTextContent);
    }

    /**
     * 流式调用核心实现。
     * <p>
     * 流程：获取 Agent 实例 → 解析超时 → 调用 {@code agent.streamEvents()} →
     * 过滤 TEXT_BLOCK_DELTA/END 事件 → 提取 delta 文本 → 过滤空串。
     * </p>
     */
    @Override
    public Flux<String> callStream(String agentName, String message, CallOptions options) {
        return getAgent(agentName, options)
                .flux()
                .flatMap(agent -> {
                    Duration timeout = resolveTimeout(agentName, options);
                    // V2.0-RC3: 使用 streamEvents 替代已废弃的 stream()
                    // HarnessAgent 自身实现 streamEvents()，不再继承 ReActAgent
                    io.agentscope.harness.agent.HarnessAgent ha = (io.agentscope.harness.agent.HarnessAgent) agent;
                    return ha.streamEvents(List.of(Msg.builder().textContent(message).build()))
                            .timeout(timeout)
                            .filter(e -> e.getType() == AgentEventType.TEXT_BLOCK_DELTA
                                    || e.getType() == AgentEventType.TEXT_BLOCK_END)
                            .map(e -> {
                                if (e instanceof TextBlockDeltaEvent de)
                                    return de.getDelta();
                                if (e instanceof TextBlockEndEvent)
                                    return "";
                                return "";
                            })
                            .filter(text -> !text.isEmpty());
                });
    }

    /**
     * 获取 Agent 实例。
     * <p>
     * 根据 CallOptions 中的 Profile 解析最终 Agent 名称（格式：agentName#profile），
     * 然后从 AgentService 获取实例。
     * </p>
     */
    private Mono<io.agentscope.core.agent.Agent> getAgent(String agentName, CallOptions options) {
        return Mono.fromCallable(() -> {
            String effectiveName = resolveAgentName(agentName, options);
            return agentService.getAgentInstance(effectiveName);
        });
    }

    /**
     * 解析最终 Agent 名称。
     * <p>
     * 若 CallOptions 中指定了 Profile，返回 agentName#profile，否则直接返回 agentName。
     * </p>
     */
    private String resolveAgentName(String agentName, CallOptions options) {
        String profile = options != null ? options.getProfile() : null;
        return (profile != null && !profile.isBlank()) ? agentName + "#" + profile : agentName;
    }

    /**
     * 解析调用超时。
     * <p>
     * 优先级：CallOptions.timeout > AgentDefinition.runtime.timeout > 默认 120 秒
     * </p>
     */
    private Duration resolveTimeout(String agentName, CallOptions options) {
        if (options != null && options.getTimeout() != null)
            return options.getTimeout();
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def != null && def.getRuntime() != null)
            return def.getRuntime().getTimeout();
        return Duration.ofSeconds(120);
    }
}
