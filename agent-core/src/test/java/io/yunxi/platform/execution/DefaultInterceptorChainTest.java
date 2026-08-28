package io.yunxi.platform.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.yunxi.platform.execution.spi.ExecutionInterceptor;

/**
 * DefaultInterceptorChain：排序、异常传播与 postHandle 隔离测试。
 */
@DisplayName("DefaultInterceptorChain 拦截器链")
class DefaultInterceptorChainTest {

    /** 记录调用顺序的测试拦截器 */
    private static class Recorder implements ExecutionInterceptor {
        final String name;
        final int order;
        final List<String> calls;
        final boolean throwOnPre;

        Recorder(String name, int order, List<String> calls, boolean throwOnPre) {
            this.name = name;
            this.order = order;
            this.calls = calls;
            this.throwOnPre = throwOnPre;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void preHandle(ExecutionContext ctx) {
            calls.add("pre:" + name);
            if (throwOnPre) {
                throw new IllegalStateException("拦截器 " + name + " 失败");
            }
        }

        @Override
        public void postHandle(ExecutionContext ctx) {
            calls.add("post:" + name);
        }
    }

    private ExecutionContext ctx() {
        ExecutionRequest request = ExecutionRequest.builder().message("hi").agentName("a").build();
        return new ExecutionContext(request, null, null, "a");
    }

    @Test
    @DisplayName("preHandle 按 order 升序执行")
    void preHandleOrdered() {
        List<String> calls = new ArrayList<>();
        DefaultInterceptorChain chain = new DefaultInterceptorChain(List.of(
                new Recorder("300", 300, calls, false),
                new Recorder("100", 100, calls, false),
                new Recorder("200", 200, calls, false)));

        chain.preHandleAll(ctx());

        assertThat(calls).containsExactly("pre:100", "pre:200", "pre:300");
    }

    @Test
    @DisplayName("preHandle 异常向上传播，中断后续拦截器")
    void preHandleThrowsPropagates() {
        List<String> calls = new ArrayList<>();
        DefaultInterceptorChain chain = new DefaultInterceptorChain(List.of(
                new Recorder("100", 100, calls, true),
                new Recorder("200", 200, calls, false)));

        assertThatThrownBy(() -> chain.preHandleAll(ctx()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("100");
        assertThat(calls).containsExactly("pre:100");
    }

    @Test
    @DisplayName("postHandleAll 异常被隔离，其余拦截器照常执行")
    void postHandleAllIsolatesException() {
        List<String> calls = new ArrayList<>();
        DefaultInterceptorChain chain = new DefaultInterceptorChain(List.of(
                new Recorder("100", 100, calls, false),
                new InterceptorWithThrowingPost("200", 200, calls),
                new Recorder("300", 300, calls, false)));

        // 不抛出
        chain.postHandleAll(ctx());

        assertThat(calls).containsExactly("post:100", "post:200", "post:300");
    }

    @Test
    @DisplayName("空链安全执行")
    void emptyChain() {
        DefaultInterceptorChain chain = new DefaultInterceptorChain(List.of());
        chain.preHandleAll(ctx());
        chain.postHandleAll(ctx());
    }

    @Test
    @DisplayName("超过 10ms 的 postHandle 不影响结果")
    void slowPostHandleStillRuns() {
        AtomicInteger count = new AtomicInteger();
        DefaultInterceptorChain chain = new DefaultInterceptorChain(List.of(
                new ExecutionInterceptor() {
                    @Override
                    public int getOrder() {
                        return 100;
                    }

                    @Override
                    public void preHandle(ExecutionContext ctx) {
                        // 空实现
                    }

                    @Override
                    public void postHandle(ExecutionContext ctx) {
                        count.incrementAndGet();
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }));
        chain.postHandleAll(ctx());
        assertThat(count.get()).isEqualTo(1);
    }

    /** postHandle 抛异常的测试拦截器 */
    private static class InterceptorWithThrowingPost implements ExecutionInterceptor {
        final String name;
        final int order;
        final List<String> calls;

        InterceptorWithThrowingPost(String name, int order, List<String> calls) {
            this.name = name;
            this.order = order;
            this.calls = calls;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public void preHandle(ExecutionContext ctx) {
            // 空实现
        }

        @Override
        public void postHandle(ExecutionContext ctx) {
            calls.add("post:" + name);
            throw new IllegalStateException("post " + name + " 失败");
        }
    }
}
