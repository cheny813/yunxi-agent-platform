package io.yunxi.runtime.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.yunxi.capability.ModelRoutingPolicy;
import io.yunxi.capability.RoutedModel;
import io.yunxi.observability.AuditEvent;
import io.yunxi.observability.AuditSink;
import io.yunxi.observability.MetricRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * 韧性中间件（order=200，最内层，紧贴核心执行）。
 *
 * <p>职责：在模型调用与工具执行两个热点处施加限流与熔断，并复用 {@link ModelRoutingPolicy} 在熔断时决策兜底模型，
 * 闭环「故障转移 / 熔断缺失」内核缺口（内核提供模型实例但不提供跨调用韧性编排）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>限流：每个 {@code 模型} / {@code 工具} 维度独立 RateLimiter，超出即快速失败（Flux.error）。</li>
 *   <li>熔断：失败率超阈值后进入 OPEN，后续请求直接快速失败，避免对故障下游的无效重试风暴。</li>
 *   <li>故障转移：熔断打开时咨询路由策略取兜底模型 id，落指标与审计；真实模型切换由运行时装配完成。</li>
 * </ul>
 *
 * <p>本中间件只做协议无关的「保护 + 决策」，绝不持有任何横切语义的第二条真相。
 */
public class ResilienceMiddleware implements MiddlewareBase {

    private final MetricRegistry metricRegistry;
    private final AuditSink auditSink;
    private final ModelRoutingPolicy routingPolicy;
    private final ResilienceThresholds thresholds;

    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    public ResilienceMiddleware(
            MetricRegistry metricRegistry,
            AuditSink auditSink,
            ModelRoutingPolicy routingPolicy,
            ResilienceThresholds thresholds) {
        this.metricRegistry = metricRegistry;
        this.auditSink = auditSink;
        this.routingPolicy = routingPolicy;
        this.thresholds = thresholds;
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        String key = "model:" + agent.getName() + ":" + input.model().getModelName();
        if (!permit(key)) {
            return Flux.error(new RuntimeException("resilience rejected model call: " + key));
        }
        CircuitBreaker cb = circuitBreaker(key);
        if (!cb.tryAcquirePermission()) {
            metricRegistry.incrementCounter("model.call.circuit_open");
            return Flux.error(CallNotPermittedException.createCallNotPermittedException(cb));
        }
        long start = System.nanoTime();
        return next.apply(input)
                .doOnComplete(() -> cb.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS))
                .doOnError(error -> {
                    cb.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, error);
                    maybeFailover(key, agent, ctx, input.model().getModelName(), cb, error);
                });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        String key = "acting:" + agent.getName();
        if (!permit(key)) {
            return Flux.error(new RuntimeException("resilience rejected tool execution: " + key));
        }
        CircuitBreaker cb = circuitBreaker(key);
        if (!cb.tryAcquirePermission()) {
            metricRegistry.incrementCounter("tool.call.circuit_open");
            return Flux.error(CallNotPermittedException.createCallNotPermittedException(cb));
        }
        long start = System.nanoTime();
        return next.apply(input)
                .doOnComplete(() -> cb.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS))
                .doOnError(error -> {
                    cb.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, error);
                    maybeFailover(key, agent, ctx, "tool", cb, error);
                });
    }

    private boolean permit(String key) {
        RateLimiter limiter =
                rateLimiters.computeIfAbsent(key, k -> RateLimiter.of(k, rateLimiterConfig()));
        return limiter.acquirePermission();
    }

    private CircuitBreaker circuitBreaker(String key) {
        return circuitBreakers.computeIfAbsent(key, k -> CircuitBreaker.of(k, circuitBreakerConfig()));
    }

    private void maybeFailover(
            String key,
            Agent agent,
            RuntimeContext ctx,
            String primary,
            CircuitBreaker cb,
            Throwable error) {
        if (routingPolicy == null || cb.getState() != CircuitBreaker.State.OPEN) {
            return;
        }
        RoutedModel routed = routingPolicy.route(agent.getName(), "", ctx);
        if (routed == null || routed.fallbackModelIds().isEmpty()) {
            return;
        }
        metricRegistry.incrementCounter("model.failover.requested");
        auditSink.emit(new AuditEvent(
                "model.failover",
                ctx == null ? "" : orEmpty(ctx.getUserId()),
                java.time.Instant.now(),
                Map.of(
                        "key", key,
                        "primary", primary,
                        "fallback", routed.fallbackModelIds().get(0))));
    }

    RateLimiterConfig rateLimiterConfig() {
        return RateLimiterConfig.custom()
                .limitForPeriod(thresholds.limitForPeriod())
                .limitRefreshPeriod(Duration.ofSeconds(thresholds.limitRefreshSeconds()))
                .timeoutDuration(Duration.ofMillis(0))
                .build();
    }

    CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(thresholds.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(thresholds.slidingWindowSize())
                .waitDurationInOpenState(Duration.ofSeconds(thresholds.waitOpenSeconds()))
                .build();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
