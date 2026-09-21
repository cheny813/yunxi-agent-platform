package io.yunxi.platform.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 轨迹存储的内存实现。
 *
 * <p>按轨迹标识分桶保存快照，并为每个轨迹维护一个多播通道，使订阅方能同时获得历史快照与
 * 后续新快照。适用单实例部署与开发环境；多实例部署需替换为共享存储实现。</p>
 *
 * <p>轨迹数据在进程内累积，为避免长期运行占用过多内存，可配置单轨迹保留的最大快照数，
 * 超出后丢弃最早的部分。</p>
 *
 * @author yunxi-agent-platform
 */
public class InMemoryTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTraceStore.class);

    /** 单轨迹默认保留的最大快照数 */
    private static final int DEFAULT_MAX_SPANS_PER_TRACE = 10_000;

    private final Map<String, CopyOnWriteArrayList<ReasoningSpan>> traces = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<ReasoningSpan>> sinks = new ConcurrentHashMap<>();
    /** 轨迹活跃顺序：新轨迹追加到尾部，用于 {@link #recentTraceIds(int)} 取最近活跃者。 */
    private final java.util.Deque<String> activityOrder = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final int maxSpansPerTrace;

    public InMemoryTraceStore() {
        this(DEFAULT_MAX_SPANS_PER_TRACE);
    }

    public InMemoryTraceStore(int maxSpansPerTrace) {
        this.maxSpansPerTrace = maxSpansPerTrace > 0 ? maxSpansPerTrace : DEFAULT_MAX_SPANS_PER_TRACE;
    }

    @Override
    public void append(ReasoningSpan span) {
        if (span == null || span.traceId() == null) {
            return;
        }
        try {
            boolean isNew = !traces.containsKey(span.traceId());
            CopyOnWriteArrayList<ReasoningSpan> list =
                    traces.computeIfAbsent(span.traceId(), k -> new CopyOnWriteArrayList<>());
            if (isNew) {
                activityOrder.addLast(span.traceId());
            }
            list.add(span);
            if (list.size() > maxSpansPerTrace) {
                list.remove(0);
            }
            Sinks.Many<ReasoningSpan> sink = sinks.get(span.traceId());
            if (sink != null) {
                sink.tryEmitNext(span);
            }
        } catch (Exception e) {
            log.warn("轨迹写入失败: traceId={}, kind={}: {}",
                    span.traceId(), span.kind(), e.getMessage());
        }
    }

    @Override
    public List<ReasoningSpan> read(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<ReasoningSpan> list = traces.get(traceId);
        return list == null ? List.of() : new ArrayList<>(list);
    }

    @Override
    public Flux<ReasoningSpan> stream(String traceId) {
        if (traceId == null) {
            return Flux.empty();
        }
        List<ReasoningSpan> history = read(traceId);
        Sinks.Many<ReasoningSpan> sink = sinks.computeIfAbsent(traceId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux().startWith(history);
    }

    @Override
    public void clear(String traceId) {
        if (traceId == null) {
            return;
        }
        traces.remove(traceId);
        activityOrder.remove(traceId);
        Sinks.Many<ReasoningSpan> sink = sinks.remove(traceId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 枚举最近活跃的轨迹标识，按加入顺序由新到旧返回。
     *
     * <p>顺序由 {@link #activityOrder} 维护：新轨迹追加到尾部，故从尾部反向取即为最近活跃者。</p>
     */
    @Override
    public List<String> recentTraceIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(Math.min(limit, activityOrder.size()));
        java.util.Iterator<String> it = activityOrder.descendingIterator();
        while (it.hasNext() && result.size() < limit) {
            result.add(it.next());
        }
        return result;
    }

}
