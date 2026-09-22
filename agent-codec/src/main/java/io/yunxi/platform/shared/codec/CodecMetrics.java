package io.yunxi.platform.shared.codec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 编解码失败的统一计数与上报。
 *
 * <p>持久化边界上的编解码失败以「返回空 / 跳过该条 / 记一条 warn 日志」降级，降级本身不报错。
 * 本类为这类静默降级提供计数与阈值告警，使其可从指标与启动自检中观测，而非只能表现为
 * 消费方拿到空数据。</p>
 *
 * <p>不抛异常：编解码失败发生在旁路（轨迹、画像、节流状态），抛异常会把观测资产的故障
 * 升级为执行主流程的故障。此处只计数，由调用方决定是跳过该条还是返回空。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class CodecMetrics {

    private static final Logger log = LoggerFactory.getLogger(CodecMetrics.class);

    /** 指标前缀 */
    private static final String METRIC_NAME = "yunxi.codec.failures";

    /**
     * 失败计数是否达到值得告警的量级。
     *
     * <p>取 10 而非 1：偶发失败可能来自历史遗留数据或单条脏快照，属预期内的容错；
     * 持续失败才意味着写入侧与读取侧已经不一致，这时才有排查价值。</p>
     */
    private static final long ALERT_THRESHOLD = 10L;

    /** 按「场景」分桶计数，键为场景标识（如 trace-read / profile-write） */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 已告警过的场景，避免同一条日志刷屏 */
    private final Map<String, Boolean> alerted = new ConcurrentHashMap<>();

    private final MeterRegistry meterRegistry;

    public CodecMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * 登记一次编解码失败。
     *
     * @param scene   场景标识，用于区分失败的来源（如 {@code trace-read}、{@code profile-write}）
     * @param message 失败原因摘要，可为 null
     */
    public void recordFailure(String scene, String message) {
        if (scene == null) {
            return;
        }
        long count = counters.computeIfAbsent(scene, k -> new AtomicLong()).incrementAndGet();
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_NAME, "scene", scene).increment();
        }
        if (count == ALERT_THRESHOLD) {
            alerted.putIfAbsent(scene, Boolean.TRUE);
            log.warn("[codec] 编解码失败已累计 {} 次，场景={}：读写两侧可能存在不一致（最近原因: {}）",
                    count, scene, message);
        } else if (log.isDebugEnabled()) {
            log.debug("[codec] 编解码失败: 场景={}, 累计={}, 原因={}", scene, count, message);
        }
    }

    /**
     * 返回某场景的累计失败次数。
     *
     * @param scene 场景标识
     * @return 累计失败次数，未记录时返回 0
     */
    public long failureCount(String scene) {
        AtomicLong counter = counters.get(scene);
        return counter == null ? 0L : counter.get();
    }

    /**
     * 汇总所有场景的失败次数，供启动自检与健康检查使用。
     *
     * @return 场景标识到失败次数的映射快照
     */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        counters.forEach((scene, counter) -> result.put(scene, counter.get()));
        return result;
    }
}
