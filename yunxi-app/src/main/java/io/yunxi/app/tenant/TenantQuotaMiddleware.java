package io.yunxi.app.tenant;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.app.config.TenantQuotaProperties;
import io.yunxi.definition.QuotaType;
import io.yunxi.definition.TenantQuota;
import io.yunxi.runtime.tenant.QuotaExceededException;
import io.yunxi.runtime.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 租户配额中间件（order=100，协议无关横切闸门，挂入 {@code protocolAgnosticMiddlewares} 单一接线点）。
 *
 * <p>职责（单一）：在每次 Agent 调用入口，按当前租户维度强制执行两类配额——
 * ① 每日请求数（滚动 24h 窗口计数）；② 并发会话数（在途调用数信号量）。
 * 任一维度超限即 fail-closed 抛出 {@link QuotaExceededException}（由 {@code TenantQuotaExceptionHandler} 映射 429）。
 *
 * <p>fail-open：未绑定租户（多租户关闭）、未配置配额、或 Redis 不可用时跳过闸门，不阻断既有行为。
 *
 * <p>计数复用平台全局 {@link StringRedisTemplate}（不私建连接）；每日窗口经 Lua 原子 INCR+EXPIRE，
 * 并发信号量经 INCR 进入、Agent 运行结束（任意信号）经 {@code doFinally} DECR——与 Redis 会话轮次闸门同后端，
 * 保证跨实例一致性。
 */
public class TenantQuotaMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaMiddleware.class);

    private static final RedisScript<Long> INCR_DAILY_SCRIPT = RedisScript.of(
            "local v = redis.call('incr', KEYS[1])\nif v == 1 then redis.call('expire', KEYS[1], 86400) end\nreturn v",
            Long.class);
    private static final RedisScript<Long> INCR_CONCURRENT_SCRIPT = RedisScript.of(
            "local v = redis.call('incr', KEYS[1])\nif v == 1 then redis.call('expire', KEYS[1], 3600) end\nreturn v",
            Long.class);
    private static final RedisScript<Long> DECR_SCRIPT = RedisScript.of(
            "return redis.call('decr', KEYS[1])", Long.class);

    private static final String KEY_PREFIX = "yunxi:tenant-quota:";

    private final TenantQuotaProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public TenantQuotaMiddleware(TenantQuotaProperties properties,
            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    @Override
    public Set<MiddlewareBase.ExtensionPoint> activePoints() {
        return EnumSet.of(MiddlewareBase.ExtensionPoint.ON_AGENT);
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String tenant = TenantContext.current();
        if (tenant == null) {
            return next.apply(input);
        }
        Optional<TenantQuota> quota = properties.resolve(tenant);
        if (quota.isEmpty()) {
            return next.apply(input);
        }
        TenantQuota q = quota.get();

        if (q.dailyRequestLimit() != null) {
            Long count = safeIncrement(dailyKey(tenant), INCR_DAILY_SCRIPT);
            if (count != null && count > q.dailyRequestLimit()) {
                throw new QuotaExceededException(tenant, QuotaType.DAILY_REQUEST, q.dailyRequestLimit());
            }
        }

        if (q.concurrentSessionLimit() != null) {
            Long current = safeIncrement(concurrentKey(tenant), INCR_CONCURRENT_SCRIPT);
            if (current != null && current > q.concurrentSessionLimit()) {
                safeDecrement(concurrentKey(tenant));
                throw new QuotaExceededException(tenant, QuotaType.CONCURRENT_SESSION, q.concurrentSessionLimit());
            }
            return next.apply(input).doFinally(signal -> safeDecrement(concurrentKey(tenant)));
        }

        return next.apply(input);
    }

    private Long safeIncrement(String key, RedisScript<Long> script) {
        try {
            StringRedisTemplate rt = redisProvider.getIfAvailable();
            if (rt == null) {
                return null;
            }
            return rt.execute(script, List.of(key));
        } catch (RuntimeException e) {
            log.warn("[TenantQuota] Redis 计数失败，fail-open 跳过闸门 key={}：{}", key, e.getMessage());
            return null;
        }
    }

    private void safeDecrement(String key) {
        try {
            StringRedisTemplate rt = redisProvider.getIfAvailable();
            if (rt == null) {
                return;
            }
            rt.execute(DECR_SCRIPT, List.of(key));
        } catch (RuntimeException e) {
            log.warn("[TenantQuota] Redis 计数回退失败 key={}：{}", key, e.getMessage());
        }
    }

    private static String dailyKey(String tenant) {
        return KEY_PREFIX + tenant + ":daily";
    }

    private static String concurrentKey(String tenant) {
        return KEY_PREFIX + tenant + ":concurrent";
    }
}
