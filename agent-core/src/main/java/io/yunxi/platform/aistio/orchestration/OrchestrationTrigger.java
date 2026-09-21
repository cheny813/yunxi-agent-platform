package io.yunxi.platform.aistio.orchestration;

import java.util.Map;

import reactor.core.publisher.Mono;

/**
 * 编排触发侧（数据面唯一可用的启动入口）。
 *
 * <p>权限边界：编排资源（定义 CRUD / 运行查询 / 控制）对数据面内网 token 全部 403（§0-A.1 实测），
 * yunxi 唯一可用的启动入口是 **automation webhook**（免内网 token，凭 {@code X-Automation-Secret}）。
 * 因此本接口只做「触发」，不做「读/控」——读与控属于接活上下文，由执行侧承担。
 *
 * <p><b>硬约束</b>：本接口不得出现任何以 {@code runId} 为入参的方法 —— 触发侧拿不到 orchestrationRunId
 * （webhook 只回 delivery，编排 run 由异步 worker 才创建）。触发与执行的关联一律走
 * {@code correlationId}（§3.8）。
 */
public interface OrchestrationTrigger {

    /**
     * 触发一次编排运行（唯一路径：automation webhook）。
     *
     * <p>{@code POST /hooks/v1/automations/{automationId}/{triggerId}}，
     * 头 {@code X-Automation-Secret} 与 {@code Idempotency-Key}（必填，≤200 字符），
     * body 原样成为 run input；本实现会在 body 中追加 {@code _yunxi} 段承载 correlationId（§3.8）。
     *
     * <p><b>第三参是业务请求 ID，不是幂等键本身</b>：为避免重复起 run，同一逻辑请求须复用同一个
     * {@code Idempotency-Key}，否则任何一层超时重试都会重复起 run。故此处按 {@code bizRequestId}
 * 经 {@link TriggerIdempotencyStore} 换取/生成幂等键。（早期草稿的签名写作
 * {@code idempotencyKey}，与幂等键换取逻辑冲突，以换取逻辑为准。）
     *
     * @param definitionKey  定义 key（配置化引用，映射到 automation 绑定；非服务端 definitionId）
     * @param input          业务入参，原样作为 run input
     * @param bizRequestId   业务请求 ID（同一逻辑请求重试时传同一个值）
     * @return 触发回执（仅含 webhook 身份下真实可得的字段，不含 orchestrationRunId）
     */
    Mono<TriggerReceipt> start(String definitionKey, Map<String, Object> input, String bizRequestId);

    /**
     * 启动期能力自查：入口可达性 + 配置齐备性（§3.3.4 无副作用探测，不得真实触发编排）。
     */
    OrchestrationCapability capability();
}
