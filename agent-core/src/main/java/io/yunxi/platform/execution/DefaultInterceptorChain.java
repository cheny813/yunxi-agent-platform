package io.yunxi.platform.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.yunxi.platform.execution.spi.ExecutionInterceptor;

/**
 * 默认拦截器链。
 *
 * <p>收集 Spring 容器内全部 {@link ExecutionInterceptor}（@Component），
 * 按 {@code getOrder()} 升序执行 {@code preHandle}。拦截器契约：修改
 * {@link ExecutionContext} 共享状态（resolvedAgent/inputMessages/intentResult 等），
 * 不直接调用 Agent；异常由本链向上抛出，由引擎收口为错误结果。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class DefaultInterceptorChain {

    private static final Logger log = LoggerFactory.getLogger(DefaultInterceptorChain.class);

    private final List<ExecutionInterceptor> interceptors;

    public DefaultInterceptorChain(List<ExecutionInterceptor> interceptors) {
        List<ExecutionInterceptor> sorted = new ArrayList<>(interceptors);
        sorted.sort((a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        this.interceptors = Collections.unmodifiableList(sorted);
    }

    /**
     * 依次执行全部拦截器 preHandle。
     *
     * @param ctx 执行上下文
     */
    public void preHandleAll(ExecutionContext ctx) {
        for (ExecutionInterceptor interceptor : interceptors) {
            long start = System.nanoTime();
            interceptor.preHandle(ctx);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            log.debug("Interceptor[{}] order={} elapsedMs={}",
                    interceptor.getClass().getSimpleName(), interceptor.getOrder(), elapsedMs);
        }
    }

    /**
     * 依次执行全部拦截器 postHandle（升序，与 preHandle 一致）。
     *
     * <p>单个拦截器异常被隔离捕获并记录日志，不中断后续收尾——
     * 保证审计等收尾动作在失败路径同样可达。实现类自身不应抛异常。</p>
     *
     * @param ctx 执行上下文（含引擎写入的结果摘要）
     */
    public void postHandleAll(ExecutionContext ctx) {
        for (ExecutionInterceptor interceptor : interceptors) {
            try {
                long start = System.nanoTime();
                interceptor.postHandle(ctx);
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                if (elapsedMs > 10) {
                    log.debug("Interceptor[{}] order={} postHandle elapsedMs={}",
                            interceptor.getClass().getSimpleName(), interceptor.getOrder(), elapsedMs);
                }
            } catch (Exception e) {
                log.warn("Interceptor[{}] postHandle failed: {}",
                        interceptor.getClass().getSimpleName(), e.getMessage(), e);
            }
        }
    }

    public List<ExecutionInterceptor> getInterceptors() {
        return interceptors;
    }
}
