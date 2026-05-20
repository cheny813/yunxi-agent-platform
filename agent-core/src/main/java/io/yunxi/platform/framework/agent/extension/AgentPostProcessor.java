package io.yunxi.platform.framework.agent.extension;

import reactor.core.publisher.Mono;

/**
 * Agent 调用后置处理器 — 扩展点
 * <p>
 * 在 Agent 调用完成后执行，可用于审计、日志、结果转换等。
 * 实现此接口并注册为 Spring Bean，在 YAML 配置中引用即可生效。
 * </p>
 *
 * <pre>
 * &#64;Component("auditPostProcessor")
 * public class AuditPostProcessor implements AgentPostProcessor {
 *     public Mono&lt;String&gt; postProcess(String agentName, String input, String output) {
 *         log.info("Agent [{}] 调用完成，输入长度: {}，输出长度: {}",
 *             agentName, input.length(), output.length());
 *         return Mono.just(output);
 *     }
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public interface AgentPostProcessor {

    /**
     * 在 Agent 调用完成后处理结果。
     *
     * @param agentName Agent 名称
     * @param input     原始用户输入
     * @param output    Agent 输出结果
     * @return 处理后的结果（可修改或包装）
     */
    Mono<String> postProcess(String agentName, String input, String output);
}