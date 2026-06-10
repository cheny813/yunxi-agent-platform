package io.yunxi.platform.agent.gateway;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * 封装 AgentService 的底层调用，对外暴露简洁的 API。
 * 核心方法 {@link #call} 和 {@link #callStream}
 * 隐藏了 Agent 查找、超时处理等细节。
 * </p>
 *
 * <p>
 * V2.0 升级：
 * <ul>
 * <li>合并接口与实现，删除空的 Pre/PostProcessor</li>
 * <li>安全控制迁移到 V2.0 {@link io.agentscope.core.middleware.MiddlewareBase} 体系</li>
 * <li>精简冗余方法，代码量从 433 行缩减到 ~110 行</li>
 * </ul>
 * </p>
 *
 * @see AgentGatewayImpl
 */
public interface AgentGateway {

    /**
     * 同步调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message);

    /**
     * 流式调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return Agent 响应文本流
     */
    Flux<String> callStream(String agentName, String message);

    /**
     * 同步调用 Agent（指定 Profile）。
     *
     * @param agentName Agent 名称
     * @param profile   Agent Profile 标识
     * @param message   用户消息
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String profile, String message);

    /**
     * 同步调用 Agent（指定调用选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @param options   调用选项（超时、Profile、上下文等）
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message, CallOptions options);

    /**
     * 流式调用 Agent（指定调用选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @param options   调用选项（超时、Profile、上下文等）
     * @return Agent 响应文本流
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
 */
@Component
class AgentGatewayImpl implements AgentGateway {

    /** 类级别日志记录器 */
    private static final Logger log = LoggerFactory.getLogger(AgentGatewayImpl.class);

    /** Agent 服务，用于获取 Agent 实例 */
    private final AgentService agentService;

    /** Agent 定义加载器，用于获取 Agent 配置中的默认超时时间 */
    private final AgentDefinitionLoader definitionLoader;

    /**
     * 构造 Agent 网关实现。
     *
     * @param agentService     Agent 服务
     * @param definitionLoader Agent 定义加载器
     */
    public AgentGatewayImpl(AgentService agentService, AgentDefinitionLoader definitionLoader) {
        this.agentService = agentService;
        this.definitionLoader = definitionLoader;
    }

    /**
     * 同步调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return Agent 响应文本
     */
    @Override
    public Mono<String> call(String agentName, String message) {
        return call(agentName, message, new CallOptions());
    }

    /**
     * 流式调用 Agent（使用默认选项）。
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @return Agent 响应文本流
     */
    @Override
    public Flux<String> callStream(String agentName, String message) {
        return callStream(agentName, message, new CallOptions());
    }

    /**
     * 同步调用 Agent（指定 Profile）。
     *
     * <p>
     * Profile 用于选择 Agent 的角色配置，名称解析为 agentName#profile。
     * </p>
     *
     * @param agentName Agent 名称
     * @param profile   Agent Profile 标识
     * @param message   用户消息
     * @return Agent 响应文本
     */
    @Override
    public Mono<String> call(String agentName, String profile, String message) {
        return call(agentName, message, new CallOptions().setProfile(profile));
    }

    /**
     * 同步调用 Agent（指定调用选项）。
     *
     * <p>
     * 核心调用流程：
     * 1. 根据 Agent 名称和选项解析实际的 Agent 实例
     * 2. 解析超时时间（选项 > Agent 定义配置 > 默认 120 秒）
     * 3. 调用 Agent.call() 并提取文本响应
     * </p>
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @param options   调用选项
     * @return Agent 响应文本
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
     * 流式调用 Agent（指定调用选项）。
     *
     * <p>
     * 核心调用流程：
     * 1. 根据 Agent 名称和选项解析实际的 Agent 实例
     * 2. 解析超时时间
     * 3. 调用 Agent.stream() 并逐事件提取文本内容
     * </p>
     *
     * @param agentName Agent 名称
     * @param message   用户消息
     * @param options   调用选项
     * @return Agent 响应文本流
     */
    @Override
    public Flux<String> callStream(String agentName, String message, CallOptions options) {
        return getAgent(agentName, options)
                .flux()
                .flatMap(agent -> {
                    Duration timeout = resolveTimeout(agentName, options);
                    return agent.stream(List.of(Msg.builder().textContent(message).build()),
                            io.agentscope.core.agent.StreamOptions.defaults())
                            .timeout(timeout)
                            .map(event -> event.getMessage().getTextContent());
                });
    }

    /**
     * 获取 Agent 实例。
     *
     * <p>
     * 根据 CallOptions 中的 Profile 解析实际的 Agent 名称，
     * 然后从 AgentService 中获取对应的 Agent 实例。
     * Profile 格式：agentName#profile
     * </p>
     *
     * @param agentName Agent 名称
     * @param options   调用选项
     * @return Agent 实例的 Mono
     */
    private Mono<io.agentscope.core.agent.Agent> getAgent(String agentName, CallOptions options) {
        return Mono.fromCallable(() -> {
            String effectiveName = resolveAgentName(agentName, options);
            return agentService.getAgentInstance(effectiveName);
        });
    }

    /**
     * 解析实际的 Agent 名称。
     *
     * <p>
     * 如果 CallOptions 中指定了 Profile，则名称格式为 agentName#profile，
     * 否则直接使用 agentName。
     * </p>
     *
     * @param agentName Agent 名称
     * @param options   调用选项
     * @return 解析后的 Agent 名称
     */
    private String resolveAgentName(String agentName, CallOptions options) {
        String profile = options != null ? options.getProfile() : null;
        return (profile != null && !profile.isBlank()) ? agentName + "#" + profile : agentName;
    }

    /**
     * 解析调用超时时间。
     *
     * <p>
     * 优先级：CallOptions.timeout > AgentDefinition.runtime.timeout > 默认 120 秒
     * </p>
     *
     * @param agentName Agent 名称
     * @param options   调用选项
     * @return 解析后的超时时间
     */
    private Duration resolveTimeout(String agentName, CallOptions options) {
        // 第一优先级：CallOptions 中指定的超时
        if (options != null && options.getTimeout() != null)
            return options.getTimeout();
        // 第二优先级：Agent 定义配置中的超时
        AgentDefinition def = definitionLoader.getAgentDefinition(agentName);
        if (def != null && def.getRuntime() != null)
            return def.getRuntime().getTimeout();
        // 第三优先级：默认 120 秒
        return Duration.ofSeconds(120);
    }
}
