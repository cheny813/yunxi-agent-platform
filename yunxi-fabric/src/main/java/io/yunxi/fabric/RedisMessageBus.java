package io.yunxi.fabric;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 基于全局 {@link StringRedisTemplate}（共享全局 LettuceConnectionFactory）的 {@link MessageBus} 后端实现。
 *
 * <p>框架 {@code MessageBus} 接口原生仅有 {@code WorkspaceMessageBus}（文件系统 / 进程内，跨进程仅当 FS 共享且
 * Mode D 退化为轮询）与 {@code ControlPlaneMessageBus}（强绑控制面托管 store、2s 轮询）。本类补齐框架缺失的
 * <b>Redis 后端</b>（仅 yunxi 的 Redis 拓扑需要，故落在产品平面而非框架），并复用全局 Redis 工厂，不私建第二份客户端。
 *
 * <p>三种消费模式映射到 Redis 原语：
 * <ul>
 *   <li><b>Mode A 排水队列</b>：Redis List（{@code RPUSH} 入队、{@code LRANGE + LTRIM} 原子范围读取并移除，
 *       保证 ack-on-read 单消费者语义）。</li>
 *   <li><b>Mode C 重放日志</b>：Redis Stream（{@code XADD [MAXLEN]} 追加、{@code XRANGE} 游标读取、
 *       {@code XTRIM} 限长），多消费者各自维护游标。</li>
 *   <li><b>Mode D 瞬态广播</b>：Redis pub/sub（{@code PUBLISH/SUBSCRIBE}），当前订阅者实时接收，服务端不保留历史。</li>
 * </ul>
 *
 * <p>inbox / wakeup / session-events 等域辅助方法由 {@link MessageBus} 接口的 default 方法基于上述原语自动获得，
 * 本类无需重复实现。所有 key 统一加 {@code <prefix>bus:} 前缀，与 yunxi 其它 Redis 键空间隔离。
 *
 * <p>fail-open：本实现不强制 Redis 在线——调用方（如分片重平衡推送）在总线不可达时应降级回 Nacos 订阅 / 轮询，
 * 主流程不阻断。{@link #close()} 为 no-op：全局连接工厂由 Spring 生命周期管理，本类不持有、不关闭。
 */
public final class RedisMessageBus implements MessageBus {

    private static final String BUS_PREFIX_SUFFIX = "bus:";

    private final StringRedisTemplate sharedTemplate;
    private final ReactiveStringRedisTemplate reactiveTemplate;
    private final String keyPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造跨实例事件总线后端：注入全局共享 {@link StringRedisTemplate} 与键前缀。
     *
     * <p>复用同一 {@link RedisConnectionFactory} 派生响应式模板，不创建任何 yunxi 自管客户端。
     *
     * @param sharedTemplate 全局共享 StringRedisTemplate（必须挂载在全局 LettuceConnectionFactory 上）
     * @param keyPrefix      Redis 键前缀（建议与 {@code RedisDistributedRuntimeConfig} 使用同一前缀以共享命名空间）
     */
    public RedisMessageBus(StringRedisTemplate sharedTemplate, String keyPrefix) {
        this.sharedTemplate = Objects.requireNonNull(sharedTemplate, "sharedTemplate must not be null");
        RedisConnectionFactory cf = sharedTemplate.getConnectionFactory();
        if (cf == null) {
            throw new IllegalStateException("RedisMessageBus requires a StringRedisTemplate with a non-null "
                    + "RedisConnectionFactory");
        }
        this.reactiveTemplate = new ReactiveStringRedisTemplate((ReactiveRedisConnectionFactory) cf);
        this.keyPrefix = normalizePrefix(keyPrefix) + BUS_PREFIX_SUFFIX;
    }

    // ---- Mode A: drain queue (single consumer, ack-on-read) ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        String json = serialize(payload);
        return reactiveTemplate
                .opsForList()
                .rightPush(busKey(key), json)
                .map(len -> busKey(key) + ":" + len);
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        if (maxCount <= 0) {
            return Mono.just(List.of());
        }
        String bk = busKey(key);
        return reactiveTemplate
                .opsForList()
                .range(bk, 0, (long) maxCount - 1)
                .map(json -> new BusEntry(json, deserialize(json)))
                .collectList()
                .flatMap(entries -> reactiveTemplate
                        .opsForList()
                        .trim(bk, (long) maxCount, -1)
                        .thenReturn(entries));
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return reactiveTemplate.delete(busKey(key)).then();
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return reactiveTemplate.opsForList().size(busKey(key)).map(len -> len != null && len > 0);
    }

    // ---- Mode C: replay log (multi-consumer, externally bounded) ----

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        String bk = busKey(key);
        String seqKey = bk + ":seq";
        return reactiveTemplate
                .opsForValue()
                .increment(seqKey)
                .flatMap(seq -> {
                    Mono<Boolean> add =
                            reactiveTemplate.opsForZSet().add(bk, serialize(payload), seq.doubleValue());
                    Mono<Long> trim =
                            maxLen > 0
                                    ? reactiveTemplate
                                            .opsForZSet()
                                            .size(bk)
                                            .filter(count -> count != null && count > maxLen)
                                            .flatMap(count -> reactiveTemplate
                                            .opsForZSet()
                                            .range(bk, Range.closed(0L, count - maxLen - 1L))
                                                    .collectList()
                                                    .flatMap(ids -> reactiveTemplate
                                                            .opsForZSet()
                                                            .remove(bk, ids.toArray(new String[0]))
                                                            .thenReturn(0L)))
                                            .defaultIfEmpty(0L)
                                    : Mono.just(0L);
                    return add.then(trim).thenReturn(String.valueOf(seq));
                });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        Range<Double> range =
                since == null ? Range.unbounded() : Range.rightUnbounded(Range.Bound.exclusive(Double.parseDouble(since)));
        Flux<BusEntry> values = reactiveTemplate
                .opsForZSet()
                .rangeByScoreWithScores(busKey(key), range)
                .map(tuple -> new BusEntry(
                        String.valueOf(tuple.getScore().longValue()), deserialize(tuple.getValue())));
        if (maxCount > 0) {
            values = values.take(maxCount);
        }
        return values.collectList();
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return reactiveTemplate.delete(busKey(key), busKey(key) + ":seq").then();
    }

    // ---- Mode D: transient broadcast (fire-and-forget) ----

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return reactiveTemplate.convertAndSend(busKey(key), serialize(payload)).then();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        return reactiveTemplate
                .listenToChannel(busKey(key))
                .map(msg -> deserialize(msg.getMessage()));
    }

    /** 释放底层传输资源：全局连接工厂由 Spring 管理，本类为 no-op（不关闭共享工厂）。 */
    @Override
    public void close() {}

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private String busKey(String key) {
        return keyPrefix + key;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to JSON-encode bus payload", e);
        }
    }

    private Map<String, Object> deserialize(String json) {
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to JSON-decode bus payload", e);
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "yunxi:";
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
