package io.yunxi.fabric;

import java.util.Collections;
import java.util.Map;

/**
 * 跨实例业务事件信封（事件契约的核心载荷）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code type} — {@link CrossInstanceEventType}，订阅方据此路由；未知类型安全忽略。</li>
 *   <li>{@code version} — 契约版本（当前 {@code "1"}），便于未来演进时做兼容/拒绝。</li>
 *   <li>{@code payload} — 自包含的业务数据（{@code Map}，JSON 友好），订阅方按 type 解读，禁止引用内部状态。</li>
 *   <li>{@code sourceInstanceKey} — 发起实例标识，订阅方可据此跳过自身广播，避免冗余刷新。</li>
 *   <li>{@code timestamp} — 发起时间戳（毫秒）。</li>
 * </ul>
 *
 * <p>序列化：经 {@link CrossInstanceEventBus} 转为 {@code Map<String,Object>} 后由底层 RedisMessageBus
 * 写 JSON；接收侧用 Jackson {@code convertValue} 还原，故字段需具备标准 getter/setter。
 */
public class CrossInstanceEvent {

    private CrossInstanceEventType type;
    private String version;
    private Map<String, Object> payload;
    private String sourceInstanceKey;
    private long timestamp;

    public CrossInstanceEvent() {
        // Jackson 反序列化需要无参构造
    }

    private CrossInstanceEvent(Builder b) {
        this.type = b.type;
        this.version = b.version;
        this.payload = b.payload != null ? b.payload : Collections.emptyMap();
        this.sourceInstanceKey = b.sourceInstanceKey;
        this.timestamp = b.timestamp;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 便捷工厂：以当前时间戳与默认契约版本构造一个事件。 */
    public static CrossInstanceEvent of(
            CrossInstanceEventType type, Map<String, Object> payload, String sourceInstanceKey) {
        return builder()
                .type(type)
                .payload(payload)
                .sourceInstanceKey(sourceInstanceKey)
                .build();
    }

    public CrossInstanceEventType getType() {
        return type;
    }

    public void setType(CrossInstanceEventType type) {
        this.type = type;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload;
    }

    public String getSourceInstanceKey() {
        return sourceInstanceKey;
    }

    public void setSourceInstanceKey(String sourceInstanceKey) {
        this.sourceInstanceKey = sourceInstanceKey;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public static final class Builder {
        private CrossInstanceEventType type;
        private String version = CrossInstanceEventBus.CONTRACT_VERSION;
        private Map<String, Object> payload;
        private String sourceInstanceKey;
        private long timestamp = System.currentTimeMillis();

        public Builder type(CrossInstanceEventType type) {
            this.type = type;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public Builder sourceInstanceKey(String sourceInstanceKey) {
            this.sourceInstanceKey = sourceInstanceKey;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public CrossInstanceEvent build() {
            return new CrossInstanceEvent(this);
        }
    }
}
