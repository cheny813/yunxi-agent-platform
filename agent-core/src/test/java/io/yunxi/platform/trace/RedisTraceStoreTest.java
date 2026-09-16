package io.yunxi.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import io.agentscope.core.message.ToolResultState;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedisTraceStore} 单元测试。
 *
 * <p>用 mock 替代真实 Redis，验证三类行为：写入结构（追加 + 裁剪 + 过期）、
 * 读取兼容、以及<b>存储故障不得影响执行</b> —— 最后一条是本类最关键的断言，
 * 因为轨迹是观测资产，写不进去不该让用户的请求失败。</p>
 */
@DisplayName("RedisTraceStore 跨实例轨迹存储")
class RedisTraceStoreTest {

    private RedisTemplate<String, Object> redisTemplate;
    private ListOperations<String, Object> listOps;
    private RedisTraceStore store;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        store = new RedisTraceStore(redisTemplate, 100, Duration.ofDays(1));
    }

    private static ReasoningSpan span(String key) {
        return ReasoningSpan.delta("t1", key, "e1", SpanKind.TEXT, "内容");
    }

    @Test
    @DisplayName("写入：追加到列表尾部并裁剪、刷新过期")
    void appendPushesTrimsAndExpires() {
        store.append(span("k1"));

        verify(listOps).rightPush(eq("yunxi:trace:t1"), any(ReasoningSpan.class));
        verify(listOps).trim(eq("yunxi:trace:t1"), eq(-100L), eq(-1L));
        verify(redisTemplate).expire(eq("yunxi:trace:t1"), any(Duration.class));
    }

    @Test
    @DisplayName("写入：无轨迹标识时静默忽略，不触碰存储")
    void appendWithoutTraceIdIsIgnored() {
        store.append(null);
        store.append(ReasoningSpan.delta(null, "k", "e", SpanKind.TEXT, "x"));

        verify(listOps, never()).rightPush(anyString(), any());
    }

    @Test
    @DisplayName("读取：返回列表中全部快照，顺序保持")
    void readReturnsAllSpansInOrder() {
        List<Object> raw = new ArrayList<>();
        ReasoningSpan a = ReasoningSpan.open("t1", "k1", "e1", SpanKind.TEXT, "turn");
        ReasoningSpan b = ReasoningSpan.closed("t1", "k1", "e1", SpanKind.TEXT,
                ToolResultState.SUCCESS, 1L, Map.of());
        raw.add(a);
        raw.add(b);
        when(listOps.range("yunxi:trace:t1", 0, -1)).thenReturn(raw);

        List<ReasoningSpan> spans = store.read("t1");

        assertThat(spans).hasSize(2);
        assertThat(spans.get(0)).isSameAs(a);
        assertThat(spans.get(1)).isSameAs(b);
    }

    @Test
    @DisplayName("读取：键不存在时返回空列表")
    void readMissingKeyReturnsEmpty() {
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(null);

        assertThat(store.read("missing")).isEmpty();
    }

    @Test
    @DisplayName("读取：混入非快照类型时过滤掉，不抛类型转换异常")
    void readFiltersForeignTypes() {
        List<Object> raw = new ArrayList<>();
        raw.add(span("k1"));
        raw.add("这不是快照");
        when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(raw);

        assertThat(store.read("t1")).hasSize(1);
    }

    @Test
    @DisplayName("存储故障不中断执行（写入异常被吞掉）")
    void writeFailureDoesNotPropagate() {
        doThrow(new IllegalStateException("Redis 不可用"))
                .when(listOps).rightPush(anyString(), any());

        // 不抛异常即为通过 —— 轨迹写不进去不应让用户请求失败
        store.append(span("k1"));
    }

    @Test
    @DisplayName("存储故障时读取降级为空列表，不抛异常")
    void readFailureDegradesToEmpty() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("Redis 不可用"));

        assertThat(store.read("t1")).isEmpty();
    }

    @Test
    @DisplayName("订阅：历史来自 Redis（跨实例可见），增量来自本实例写入")
    void streamReplaysHistoryThenEmitsNew() {
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenReturn(new ArrayList<>(List.of(span("k0"))));

        List<ReasoningSpan> received = new ArrayList<>();
        Flux<ReasoningSpan> flux = store.stream("t1");
        flux.subscribe(received::add);

        store.append(span("k1"));

        assertThat(received)
                .as("应先回放历史再推送本实例新写入的快照")
                .hasSize(2);
    }

    @Test
    @DisplayName("清理：删除键并结束本实例订阅")
    void clearDeletesKeyAndCompletesSink() {
        store.stream("t1").subscribe();
        store.clear("t1");

        verify(redisTemplate).delete("yunxi:trace:t1");
    }

    @Test
    @DisplayName("清理：无轨迹标识时不触碰存储")
    void clearWithoutTraceIdIsIgnored() {
        store.clear(null);

        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("容量参数非正时回落到默认值（防误配导致列表不裁剪）")
    void invalidCapacityFallsBackToDefault() {
        RedisTraceStore fallback = new RedisTraceStore(redisTemplate, 0, null);
        fallback.append(span("k1"));

        // 默认上限 10000，故 trim 的起始索引应为 -10000
        verify(listOps, times(1)).trim(eq("yunxi:trace:t1"), eq(-10_000L), eq(-1L));
    }

    @Test
    @DisplayName("过期时间非正时回落到默认值（防误配导致键永不过期）")
    void invalidTtlFallsBackToDefault() {
        RedisTraceStore fallback = new RedisTraceStore(redisTemplate, 10, Duration.ZERO);
        fallback.append(span("k1"));

        verify(redisTemplate).expire(eq("yunxi:trace:t1"), any(Duration.class));
        verify(redisTemplate, never()).expire(anyString(), eq(0L), any(TimeUnit.class));
    }
}
