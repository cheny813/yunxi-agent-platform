package io.yunxi.platform.shared.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 统一编解码器的往返契约测试。
 *
 * <p>这些用例锁定的是编解码器的行为契约，而非某个调用点的实现：一旦有人改动序列化配置
 * （例如重新开启类型信息、或放宽写入侧的越界检查），这里会立即变红。</p>
 *
 * <p>本模块不依赖任何业务模块，故用例使用的样本类型在测试内自带，避免反向依赖。</p>
 *
 * @author yunxi-agent-platform
 */
class RedisValueCodecTest {

    private CodecMetrics metrics;
    private RedisValueCodec codec;

    @BeforeEach
    void setUp() {
        metrics = new CodecMetrics(CodecTestSupport.emptyRegistryProvider());
        codec = new RedisValueCodec(metrics);
    }

    @Test
    @DisplayName("序列化：嵌套映射与列表的往返完全一致")
    void roundTripNestedStructure() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("toolCallId", "call-1");
        sample.put("usage", Map.of("inputTokens", 100, "outputTokens", 20, "time", 0.5d));
        sample.put("tasks", List.of(Map.of("taskId", "t1"), Map.of("taskId", "t2")));

        byte[] encoded = codec.serialize(sample);

        assertThat(encoded).isNotNull();
        assertThat(codec.deserialize(encoded)).isEqualTo(sample);
    }

    @Test
    @DisplayName("序列化：结果不含类型标识（不写入 @class）")
    void noTypeTagWritten() {
        byte[] encoded = codec.serialize(Map.of("kind", "MODEL_CALL"));

        assertThat(encoded).isNotNull();
        assertThat(RedisValueCodec.containsTypeTag(encoded))
                .as("持久化内容不得含类型标识，否则读取侧必须能实例化对应类型")
                .isFalse();
    }

    @Test
    @DisplayName("序列化：领域对象不产生类型标识，且字段可读回")
    void domainObjectDoesNotLeakTypeTag() {
        byte[] encoded = codec.serialize(new SampleUsage(7009, 68, 0, 1.2));

        assertThat(encoded).isNotNull();
        assertThat(RedisValueCodec.containsTypeTag(encoded))
                .as("领域对象直接落盘若写入 @class，读取侧就必须能实例化该类")
                .isFalse();
        assertThat(codec.readMap(encoded)).containsEntry("inputTokens", 7009);
    }

    @Test
    @DisplayName("字符串通道：往返一致且不含类型标识")
    void stringChannelRoundTrip() {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("userId", "u1");
        sample.put("updatedAt", 123L);

        String json = codec.write(sample, RedisValueCodec.SCENE_PROFILE);

        assertThat(json).isNotNull();
        assertThat(json).doesNotContain("@class");
        assertThat(codec.read(json, Map.class, RedisValueCodec.SCENE_PROFILE))
                .containsEntry("userId", "u1");
    }

    @Test
    @DisplayName("字符串通道：非 JSON 内容读回为 null 并计数")
    void stringChannelInvalidInputCountsFailure() {
        assertThat(codec.read("这不是 JSON", Map.class, RedisValueCodec.SCENE_PROFILE)).isNull();
        assertThat(metrics.failureCount(RedisValueCodec.SCENE_PROFILE + "-read")).isEqualTo(1L);
    }

    @Test
    @DisplayName("逐条容错：一条脏数据不拖垮整批元素的转换")
    void convertListSkipsBadElements() {
        List<Object> raw = new ArrayList<>();
        raw.add(Map.of("traceId", "t1", "kind", "MODEL_CALL"));
        raw.add("脏数据，无法转换为目标类型");
        raw.add(Map.of("traceId", "t2", "kind", "TOOL_CALL"));

        List<SampleSpan> spans = codec.convertList(raw, SampleSpan.class, RedisValueCodec.SCENE_TRACE);

        assertThat(spans)
                .as("两条合法元素应被保留，脏数据被跳过而非使整批失败")
                .hasSize(2);
        assertThat(spans.get(0).getTraceId()).isEqualTo("t1");
        assertThat(spans.get(1).getKind()).isEqualTo("TOOL_CALL");
        assertThat(metrics.failureCount(RedisValueCodec.SCENE_TRACE + "-convert")).isEqualTo(1L);
    }

    @Test
    @DisplayName("逐条容错：已是目标类型的元素直接复用")
    void convertListReusesTargetType() {
        SampleSpan span = new SampleSpan();
        span.setTraceId("t1");

        List<SampleSpan> spans = codec.convertList(List.of(span), SampleSpan.class,
                RedisValueCodec.SCENE_TRACE);

        assertThat(spans).containsExactly(span);
    }

    @Test
    @DisplayName("收窄：任意对象被转为仅含 JSON 原生类型的结构")
    void toNativeStripsDomainTypes() {
        Object nativeOnly = codec.toNative(Map.of(
                "usage", new SampleUsage(1, 2, 0, 0.1),
                "plain", "text"));

        assertThat(nativeOnly).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) nativeOnly;
        assertThat(map.get("usage")).isInstanceOf(Map.class);
        assertThat(map.get("plain")).isEqualTo("text");
        assertThat(metrics.failureCount(RedisValueCodec.SCENE_GENERIC + "-normalize"))
                .as("成功结构化的对象不应被计入收窄失败")
                .isZero();
    }

    @Test
    @DisplayName("收窄：枚举取名称而非序号")
    void toNativeConvertsEnumToName() {
        assertThat(codec.toNative(Map.of("kind", SampleKind.MODEL_CALL)))
                .isEqualTo(Map.of("kind", "MODEL_CALL"));
    }

    @Test
    @DisplayName("收窄：无法结构化的对象退化为字符串并计数")
    void toNativeFallsBackAndCountsFailure() {
        Object result = codec.toNative(new Object(), RedisValueCodec.SCENE_TRACE);

        assertThat(result).isInstanceOf(String.class);
        assertThat(metrics.failureCount(RedisValueCodec.SCENE_TRACE + "-normalize")).isEqualTo(1L);
    }

    @Test
    @DisplayName("失败计数：按场景分桶且可汇总")
    void failureCountersBucketByScene() {
        codec.read("坏数据", Map.class, "scene-a");
        codec.read("坏数据", Map.class, "scene-a");
        codec.read("坏数据", Map.class, "scene-b");

        assertThat(metrics.failureCount("scene-a-read")).isEqualTo(2L);
        assertThat(metrics.failureCount("scene-b-read")).isEqualTo(1L);
        assertThat(metrics.snapshot()).containsKeys("scene-a-read", "scene-b-read");
    }

    // ── 测试样本类型：本模块不依赖业务模块，样本自带 ──────────────────

    /** 模拟框架用量对象：不可变、无默认构造器，用于验证结构转换路径。 */
    public record SampleUsage(int inputTokens, int outputTokens, int cachedTokens, double time) {
    }

    /** 模拟轨迹快照：验证逐条转换。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SampleSpan {
        private String traceId;
        private String kind;

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }
    }

    /** 用于验证枚举收窄。 */
    public enum SampleKind {
        MODEL_CALL,
        TOOL_CALL
    }
}
