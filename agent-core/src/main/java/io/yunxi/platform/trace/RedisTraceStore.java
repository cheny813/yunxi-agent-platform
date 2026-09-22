package io.yunxi.platform.trace;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;

import io.yunxi.platform.shared.codec.RedisTemplates;
import io.yunxi.platform.shared.codec.RedisValueCodec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 轨迹存储的 Redis 实现。
 *
 * <p>用于多实例部署：轨迹写入的实例与读取的实例可能不是同一个，内存实现只能看到本进程产生的
 * 那部分，前端回放与离线评估会因负载均衡而时有时无。Redis 实现使「谁写的都能被谁读到」。</p>
 *
 * <p><b>结构选择</b>：每条轨迹一个列表键（{@code LIST}），用 {@code RPUSH} 追加、
 * {@code LRANGE} 读取、{@code LTRIM} 截断。选列表而非有序集合的原因：快照的顺序即产生顺序，
 * 而列表天然保序且追加成本恒定；有序集合需要额外维护分数（要么用时间戳——同一毫秒内会产生
 * 同分并列，要么用递增序号——又需要额外的计数器键）。</p>
 *
 * <p><b>实时订阅的降级</b>：Redis 列表本身没有推送能力，故实时流只对<b>本实例</b>写入的快照
 * 推送（复用内存通道），跨实例的实时推送需要 Redis Pub/Sub 或 Streams，当前未做。
 * 这不影响主要用途 —— 历史回放与评估读的是全量列表；实时渲染在本实例内的场景下仍然即时。
 * 若将来需要跨实例实时推送，应改的是 {@link #stream} 的实现，而非写入结构。</p>
 *
 * <p><b>容量控制</b>：每次追加后按 {@code maxSpansPerTrace} 裁剪，避免长会话无限增长。
 * TTL 与轨迹生命周期绑定：每次追加刷新过期时间，使活跃轨迹不过期、沉寂轨迹自动回收。</p>
 *
 * <p><b>编解码</b>：值走全平台统一编解码器，不含类型信息。读回的元素是自由结构，
 * 逐条转换为轨迹快照，单条失败只跳过该条 —— 详见 {@link #read(String)}。</p>
 *
 * @author yunxi-agent-platform
 */
public class RedisTraceStore implements TraceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTraceStore.class);

    /** 键前缀，避免与业务键冲突 */
    public static final String KEY_PREFIX = "yunxi:trace:";

    /** 单轨迹默认保留的最大快照数 */
    private static final int DEFAULT_MAX_SPANS_PER_TRACE = 10_000;

    /** 默认过期时间：轨迹沉寂超过该时长后自动回收 */
    private static final Duration DEFAULT_TTL = Duration.ofDays(7);

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisValueCodec codec;
    private final int maxSpansPerTrace;
    private final Duration ttl;

    /** 本实例的实时推送通道（跨实例推送未实现，见类注释） */
    private final ConcurrentHashMap<String, Sinks.Many<ReasoningSpan>> localSinks = new ConcurrentHashMap<>();

    public RedisTraceStore(RedisTemplate<String, Object> redisTemplate, RedisValueCodec codec) {
        this(redisTemplate, codec, DEFAULT_MAX_SPANS_PER_TRACE, DEFAULT_TTL);
    }

    public RedisTraceStore(RedisTemplate<String, Object> redisTemplate, RedisValueCodec codec,
                           int maxSpansPerTrace, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.codec = codec;
        this.maxSpansPerTrace = maxSpansPerTrace > 0 ? maxSpansPerTrace : DEFAULT_MAX_SPANS_PER_TRACE;
        this.ttl = (ttl == null || ttl.isZero()) ? DEFAULT_TTL : ttl;
    }

    @Override
    public void append(ReasoningSpan span) {
        if (span == null || span.traceId() == null) {
            return;
        }
        String key = keyOf(span.traceId());
        try {
            ListOperations<String, Object> ops = redisTemplate.opsForList();
            ops.rightPush(key, span);
            ops.trim(key, -maxSpansPerTrace, -1);
            redisTemplate.expire(key, ttl);

            Sinks.Many<ReasoningSpan> sink = localSinks.get(span.traceId());
            if (sink != null) {
                sink.tryEmitNext(span);
            }
        } catch (Exception e) {
            // 轨迹是观测资产：存储异常不得影响执行
            log.warn("轨迹写入 Redis 失败: traceId={}, kind={}: {}",
                    span.traceId(), span.kind(), e.getMessage());
        }
    }

    /**
     * 读取一个轨迹的全部快照。
     *
     * <p>逐条转换：单条快照损坏只跳过该条并计入编解码失败指标，不影响整条轨迹的可读性。</p>
     */
    @Override
    public List<ReasoningSpan> read(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        try {
            List<Object> raw = redisTemplate.opsForList().range(keyOf(traceId), 0, -1);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            return codec.convertList(raw, ReasoningSpan.class, RedisValueCodec.SCENE_TRACE);
        } catch (Exception e) {
            log.warn("轨迹读取 Redis 失败: traceId={}: {}", traceId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 订阅轨迹快照：先回放已落库的全量历史，再接收本实例后续写入的快照。
     *
     * <p>历史部分来自 Redis（跨实例可见），增量部分来自本实例通道 —— 跨实例的实时增量
     * 需要额外的发布订阅机制，当前不提供，详见类注释。</p>
     */
    @Override
    public Flux<ReasoningSpan> stream(String traceId) {
        if (traceId == null) {
            return Flux.empty();
        }
        List<ReasoningSpan> history = read(traceId);
        Sinks.Many<ReasoningSpan> sink = localSinks.computeIfAbsent(traceId,
                k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux().startWith(history);
    }

    @Override
    public void clear(String traceId) {
        if (traceId == null) {
            return;
        }
        try {
            redisTemplate.delete(keyOf(traceId));
        } catch (Exception e) {
            log.warn("轨迹清理 Redis 失败: traceId={}: {}", traceId, e.getMessage());
        }
        Sinks.Many<ReasoningSpan> sink = localSinks.remove(traceId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
    }

    /**
     * 枚举最近活跃的轨迹标识。
     *
     * <p>用 {@code SCAN} 匹配键前缀而非 {@code KEYS}：{@code KEYS} 会遍历整个键空间并阻塞
     * Redis 单线程，在轨迹量大的实例上会拖垮全部请求；{@code SCAN} 是游标式增量遍历，
     * 单次成本有界。取够 {@code limit} 条即停止遍历，不做全量扫描。</p>
     *
     * <p>顺序不保证严格按活跃时间：{@code SCAN} 的返回序取决于哈希槽分布。消费方若需
     * 严格时序，应在读取快照后按时间自行排序——这里只承诺「集合语义」与「不阻塞」。</p>
     */
    @Override
    public List<String> recentTraceIds(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(Math.min(limit, 128));
        try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory().getConnection()
                .scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(256).build())) {
            while (cursor.hasNext() && result.size() < limit) {
                String key = new String(cursor.next(), StandardCharsets.UTF_8);
                if (key.startsWith(KEY_PREFIX) && key.length() > KEY_PREFIX.length()) {
                    result.add(key.substring(KEY_PREFIX.length()));
                }
            }
        } catch (Exception e) {
            log.warn("轨迹枚举 Redis 失败: {}", e.getMessage());
            return List.of();
        }
        return result;
    }

    private static String keyOf(String traceId) {
        return KEY_PREFIX + traceId;
    }

    @Override
    public String storeEndpoint() {
        return RedisTemplates.endpointOf(redisTemplate.getConnectionFactory())
                + " (键前缀=" + KEY_PREFIX + ")";
    }

}
