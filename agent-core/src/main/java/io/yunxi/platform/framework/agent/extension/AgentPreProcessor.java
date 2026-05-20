package io.yunxi.platform.framework.agent.extension;

import reactor.core.publisher.Mono;

/**
 * Agent 调用前置处理器 — 扩展点
 * <p>
 * 在 Agent 调用之前执行，可用于添加业务上下文、修改用户输入等。
 * 实现此接口并注册为 Spring Bean，在 YAML 配置中引用即可生效。
 * </p>
 *
 * <pre>
 * &#64;Component("nutritionPreProcessor")
 * public class NutritionPreProcessor implements AgentPreProcessor {
 *     public Mono&lt;String&gt; preProcess(String agentName, String message) {
 *         return Mono.just("[学校营养上下文] " + message);
 *     }
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public interface AgentPreProcessor {

    /**
     * 在 Agent 调用前处理消息。
     *
     * @param agentName Agent 名称
     * @param message   原始用户消息
     * @return 处理后的消息
     */
    Mono<String> preProcess(String agentName, String message);
}