package io.yunxi.fabric;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.bus.MessageBus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * 跨实例业务事件总线门面（yunxi 产品平面）。
 *
 * <p>职责：在框架 {@link MessageBus}（Mode D 瞬时广播）之上提供
 * 强类型的事件发布 / 订阅与 JSON 路由，把底层字节通道封装为 {@link CrossInstanceEvent} 信封。
 *
 * <p>fail-open 退化（对应待办：总线不可达主流程不阻断）：
 * <ul>
 *   <li>{@link #publish} 到底层总线抛错或返回错误 Mono 时，仅记录 warn 并丢弃，绝不向上传播
 *       —— 配置中心 / 分片环仍可由 Nacos 订阅 + Redis 共享缓存兜底。</li>
 *   <li>{@link #subscribe} 建订阅失败时返回 no-op {@link Disposable}，订阅流内反序列化 / 分发异常
 *       被吞掉——单条脏消息不影响后续事件，未知 {@code type} 亦安全忽略。</li>
 * </ul>
 *
 * <p>通道约定：每个 {@link CrossInstanceEventType} 对应一个独立通道
 * {@code <prefix>bus:cross-instance:<TYPE_NAME>}，由 {@link RedisMessageBus#busKey} 统一加前缀。
 */
public class CrossInstanceEventBus {

    private static final Logger log = LoggerFactory.getLogger(CrossInstanceEventBus.class);

    /** 事件契约版本，当前为 {@code "1"}。 */
    public static final String CONTRACT_VERSION = "1";

    private static final String CHANNEL_PREFIX = "cross-instance:";

    private final MessageBus bus;
    private final ObjectMapper mapper;

    public CrossInstanceEventBus(MessageBus bus) {
        this(bus, new ObjectMapper());
    }

    public CrossInstanceEventBus(MessageBus bus, ObjectMapper mapper) {
        this.bus = Objects.requireNonNull(bus, "bus must not be null");
        this.mapper = mapper != null ? mapper : new ObjectMapper();
    }

    /**
     * 发布一个跨实例事件（fire-and-forget，fail-open）。
     *
     * <p>调用方不必等待、不必捕获：底层总线不可达时仅记 warn 并丢弃。
     */
    public void publish(CrossInstanceEvent event) {
        if (event == null || event.getType() == null) {
            return;
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", event.getType().name());
        msg.put("version", event.getVersion() != null ? event.getVersion() : CONTRACT_VERSION);
        msg.put("sourceInstanceKey", event.getSourceInstanceKey());
        msg.put("timestamp", event.getTimestamp());
        msg.put("payload", event.getPayload() != null ? event.getPayload() : Map.of());

        try {
            bus.publish(channel(event.getType()), msg)
                    .subscribe(
                            ignored -> {},
                            err -> log.warn(
                                    "[CrossInstanceEventBus] publish {} failed, fail-open: {}",
                                    event.getType(),
                                    err.getMessage()));
        } catch (RuntimeException e) {
            log.warn(
                    "[CrossInstanceEventBus] publish {} threw synchronously, fail-open: {}",
                    event.getType(),
                    e.getMessage());
        }
    }

    /**
     * 订阅某类型的跨实例事件（fail-open）。
     *
     * @return 订阅句柄，调用其 {@link Disposable#dispose()} 可退订；建订阅失败时返回 no-op 句柄。
     */
    public Disposable subscribe(CrossInstanceEventType type, Consumer<CrossInstanceEvent> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        try {
            Flux<Map<String, Object>> flux = bus.subscribe(channel(type));
            return flux.subscribe(
                    raw -> {
                        try {
                            CrossInstanceEvent event = mapper.convertValue(raw, CrossInstanceEvent.class);
                            if (event == null || event.getType() == null) {
                                return;
                            }
                            if (event.getType() != type) {
                                return; // 防御：通道本应只承载该 type，保险忽略
                            }
                            handler.accept(event);
                        } catch (Exception e) {
                            log.warn(
                                    "[CrossInstanceEventBus] deserialize/dispatch {} failed, ignore: {}",
                                    type,
                                    e.getMessage());
                        }
                    },
                    err -> log.warn(
                            "[CrossInstanceEventBus] subscribe {} stream errored, fail-open: {}",
                            type,
                            err.getMessage()));
        } catch (RuntimeException e) {
            log.warn(
                    "[CrossInstanceEventBus] subscribe {} setup failed, fail-open: {}",
                    type,
                    e.getMessage());
            return () -> {};
        }
    }

    private String channel(CrossInstanceEventType type) {
        return CHANNEL_PREFIX + type.name();
    }

    /** 释放底层总线资源（RedisMessageBus 为 no-op，全局连接工厂由 Spring 管理）。 */
    public void close() {
        try {
            bus.close();
        } catch (Exception ignored) {
            // 全局工厂不在此关闭
        }
    }
}
