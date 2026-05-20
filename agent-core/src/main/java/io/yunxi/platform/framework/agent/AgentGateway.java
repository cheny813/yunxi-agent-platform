package io.yunxi.platform.framework.agent;

import java.time.Duration;
import java.util.Map;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Agent 统一调用入口 — 业务层唯一需要的接口
 * <p>
 * 业务层只需注入此接口，按 AgentGateway，按 Agent 名称调用即可。
 * 所有横切关注点（鉴权、审计、限流、超时、优雅关闭、监控）在此统一处理。
 * </p>
 *
 * <pre>
 * // 业务层使用示例（一行代码）
 * &#64;Autowired
 * private AgentGateway agentGateway;
 *
 * public Mono&lt;String&gt; askQuestion(String question) {
 *     return agentGateway.call("nutrition-assistant", question);
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public interface AgentGateway {

    /**
     * 调用 Agent，返回完整响应。
     *
     * @param agentName Agent 名称（在 YAML 中定义）
     * @param message   用户输入
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message);

    /**
     * SSE 流式调用 Agent。
     *
     * @param agentName Agent 名称
     * @param message   用户输入
     * @return Agent 响应流
     */
    Flux<String> callStream(String agentName, String message);

    /**
     * 带 Profile 的调用（多租户 / 多配置）。
     *
     * @param agentName Agent 名称
     * @param profile   Profile 名称
     * @param message   用户输入
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String profile, String message);

    /**
     * 带调用选项的调用（按需覆盖 Agent 配置。
     *
     * @param agentName Agent 名称
     * @param message   用户输入
     * @param options   调用选项
     * @return Agent 响应文本
     */
    Mono<String> call(String agentName, String message, CallOptions options);

    /**
     * 带调用选项的流式调用。
     *
     * @param agentName Agent 名称
     * @param message   用户输入
     * @param options   调用选项
     * @return Agent 响应文本
     */
    Flux<String> callStream(String agentName, String message, CallOptions options);
}