package io.yunxi.platform.execution.spi;

import io.yunxi.platform.execution.ExecutionContext;

/**
 * 执行拦截器 SPI（横切关注点，如认证、意图管道、记忆组装、RAG、权限构建）。
 *
 * <p>采用"改 ctx 共享状态"契约：{@link #preHandle(ExecutionContext)} 返回 void，
 * 拦截器直接写入 {@link ExecutionContext} 的字段（如 {@code inputMessages}、{@code resolvedAgent}、
 * {@code routeResult}），引擎在链执行完后从 ctx 读取。按 {@link #getOrder()} 升序执行。</p>
 *
 * @author yunxi-agent-platform
 */
public interface ExecutionInterceptor {

    /**
     * 执行顺序，数值越小越先执行。
 * 约定：AuthResolve=100, Memory=150, IntentPipeline=200, RagRetrieval=300, Audit=500。
     *
     * @return 顺序值
     */
    int getOrder();

    /**
     * 前置处理：写入/修改 {@link ExecutionContext} 共享状态。
     *
     * @param ctx 执行上下文（可变）
     */
    void preHandle(ExecutionContext ctx);

    /**
     * 后置处理：仅做无流式依赖的收尾动作（如审计落库）。
     *
     * <p>设计约束：
     * <ul>
     *     <li>不接收事件流（不订阅 Flux，避免与流式通道耦合）</li>
     *     <li>触发时机：流式通道在事件流终结（doFinally）时触发；
     *         阻塞通道在策略执行完成后同步触发；失败路径同样收尾</li>
     *     <li>实现类不得抛异常——本方法内的异常由 {@link DefaultInterceptorChain#postHandleAll}
     *         隔离捕获，不污染上游信号</li>
     * </ul>
     *
     * @param ctx 执行上下文（可变，含引擎写入的 {@code executionError} 结果摘要）
     */
    default void postHandle(ExecutionContext ctx) {
        // 默认空实现：大多数拦截器只需 preHandle
    }
}
