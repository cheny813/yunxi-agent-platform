package io.yunxi.fabric;

import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * 测试用内存型 {@link MessageBus}（仅实现 Mode D 瞬时广播；其余模式为 no-op），不依赖 Redis。
 *
 * <p>用于在无 Redis 环境下确定性地验证 {@link CrossInstanceEventBus} 的路由、fail-open 与脏消息忽略。
 * 通过 {@link #setThrowOnPublish} / {@link #setThrowOnSubscribe} 模拟总线不可达，通过
 * {@link #deliverRaw} 注入原始脏消息。
 */
public class InMemoryMessageBus implements MessageBus {

    private final Map<String, Sinks.Many<Map<String, Object>>> channels = new ConcurrentHashMap<>();
    private volatile boolean throwOnPublish = false;
    private volatile boolean throwOnSubscribe = false;

    public void setThrowOnPublish(boolean v) {
        this.throwOnPublish = v;
    }

    public void setThrowOnSubscribe(boolean v) {
        this.throwOnSubscribe = v;
    }

    /** 绕过门面，直接向某通道投递一条原始 map（用于模拟脏消息 / 类型不匹配）。 */
    public void deliverRaw(String channel, Map<String, Object> raw) {
        Sinks.Many<Map<String, Object>> sink = channels.get(channel);
        if (sink != null) {
            sink.tryEmitNext(raw);
        }
    }

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        if (throwOnPublish) {
            return Mono.error(new IllegalStateException("simulated bus down"));
        }
        Sinks.Many<Map<String, Object>> sink = channels.get(key);
        if (sink != null) {
            sink.tryEmitNext(payload);
        }
        return Mono.empty();
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        if (throwOnSubscribe) {
            throw new IllegalStateException("simulated subscribe down");
        }
        Sinks.Many<Map<String, Object>> sink =
                channels.computeIfAbsent(key, k -> Sinks.many().multicast().onBackpressureBuffer());
        return sink.asFlux();
    }

    // ---- 以下模式在本测试中不使用，提供 no-op 实现以满足接口契约 ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.just("1");
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        return Mono.empty();
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.just(false);
    }

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.just("1");
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.just(List.of());
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.empty();
    }
}
