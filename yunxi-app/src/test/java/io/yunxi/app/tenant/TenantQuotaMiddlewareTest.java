package io.yunxi.app.tenant;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.yunxi.app.config.TenantQuotaProperties;
import io.yunxi.definition.QuotaType;
import io.yunxi.definition.TenantQuota;
import io.yunxi.runtime.tenant.QuotaExceededException;
import io.yunxi.runtime.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantQuotaMiddlewareTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ObjectProvider<StringRedisTemplate> redisProvider;
    @Mock
    Agent agent;
    @Mock
    RuntimeContext ctx;
    @Mock
    AgentInput input;

    private TenantQuotaMiddleware middleware(TenantQuotaProperties props, Long redisCount) {
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.execute(any(), anyList())).thenReturn(redisCount);
        return new TenantQuotaMiddleware(props, redisProvider);
    }

    private List<AgentEvent> run(TenantQuotaMiddleware m) {
        Function<AgentInput, Flux<AgentEvent>> next = i -> Flux.just(mock(AgentEvent.class));
        return m.onAgent(agent, ctx, input, next).collectList().block();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void overDailyRequestLimit_throws() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(10L, null, null)));
        TenantContext.set("t1");
        TenantQuotaMiddleware m = middleware(props, 100L);
        assertThatThrownBy(() -> m.onAgent(agent, ctx, input, i -> Flux.empty()))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> assertThat(((QuotaExceededException) e).getType()).isEqualTo(QuotaType.DAILY_REQUEST));
    }

    @Test
    void underDailyRequestLimit_passes() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(10L, null, null)));
        TenantContext.set("t1");
        TenantQuotaMiddleware m = middleware(props, 5L);
        assertThat(run(m)).hasSize(1);
    }

    @Test
    void overConcurrentSessionLimit_throws() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(null, 2L, null)));
        TenantContext.set("t1");
        TenantQuotaMiddleware m = middleware(props, 5L);
        assertThatThrownBy(() -> m.onAgent(agent, ctx, input, i -> Flux.empty()))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(e -> assertThat(((QuotaExceededException) e).getType()).isEqualTo(QuotaType.CONCURRENT_SESSION));
        verify(redis, atLeast(1)).execute(any(), anyList());
    }

    @Test
    void underConcurrentSessionLimit_passesAndReleases() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(null, 2L, null)));
        TenantContext.set("t1");
        TenantQuotaMiddleware m = middleware(props, 1L);
        assertThat(run(m)).hasSize(1);
        verify(redis, atLeast(2)).execute(any(), anyList());
    }

    @Test
    void noQuotaConfigured_failOpen() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        TenantContext.set("t1");
        TenantQuotaMiddleware m = middleware(props, 999L);
        assertThat(run(m)).hasSize(1);
        verify(redis, never()).execute(any(), anyList());
    }

    @Test
    void nullTenant_failOpen() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(1L, 1L, null)));
        TenantQuotaMiddleware m = middleware(props, 999L);
        assertThat(run(m)).hasSize(1);
        verify(redis, never()).execute(any(), anyList());
    }

    @Test
    void redisUnavailable_failOpen() {
        TenantQuotaProperties props = new TenantQuotaProperties();
        props.setTenants(Map.of("t1", new TenantQuota(1L, 1L, null)));
        TenantContext.set("t1");
        when(redisProvider.getIfAvailable()).thenReturn(null);
        TenantQuotaMiddleware m = new TenantQuotaMiddleware(props, redisProvider);
        assertThat(run(m)).hasSize(1);
    }
}
