package io.yunxi.platform.shared.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

/**
 * Redis 值的统一编解码器：全平台唯一序列化真相源。
 *
 * <p>所有 Redis 值编解码一律经本类，不得另行配置序列化器或手写 JSON 拼装。契约如下：</p>
 *
 * <p><b>不写入类型信息。</b>序列化结果只含 JSON 原生类型（对象、数组、字符串、数值、
 * 布尔、null），不附加类型标识。这样读取侧无须依赖任何领域类型可实例化 —— 持久化数据
 * 的可用性不应取决于读取环境是否具备某个类。</p>
 *
 * <p><b>写入侧收窄。</b>进入 Redis 的值应先经 {@link #toNative(Object)} 规整。若值中
 * 混入领域对象，序列化时不会附加类型标识，但仍会经 {@link #write(Object, String)} 的
 * 体检计数，使越界写入可被观测。</p>
 *
 * <p><b>读取侧逐条容错。</b>{@link #convertList} 对列表元素逐条转换，单条失败只跳过该条
 * 并计数，不使整批数据不可读。</p>
 *
 * <p><b>用于结构转换，不参与持久化</b>的场景（例如把领域对象规整为负载映射）应使用
 * {@link #toNative(Object)}，其内部绑定器与存储绑定器刻意分离：结构转换不应受存储格式
 * 演进的影响。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class RedisValueCodec implements RedisSerializer<Object> {

    /** 场景标识：用户画像读写 */
    public static final String SCENE_PROFILE = "profile";
    /** 场景标识：轨迹读写 */
    public static final String SCENE_TRACE = "trace";
    /** 场景标识：通用缓存 */
    public static final String SCENE_CACHE = "cache";
    /** 场景标识：通用值读写 */
    public static final String SCENE_GENERIC = "generic";

    /** 类型标识字面量：落盘内容中出现它即意味着写入侧越界 */
    private static final String TYPE_TAG = "\"@class\"";

    /**
     * 唯一编解码器实例，配置全部来自 {@link CodecSpec}。
     */
    private static final ObjectMapper MAPPER = CodecSpec.storage();

    /** 列表读取的类型引用：元素保持为自由结构，不尝试还原具体类型 */
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {
    };

    /** 映射读取的类型引用 */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CodecMetrics metrics;

    public RedisValueCodec(CodecMetrics metrics) {
        this.metrics = metrics;
    }

    // ── RedisSerializer 实现：供 RedisTemplate 直接使用 ────────────────

    /**
     * 序列化 Redis 值。
     *
     * <p>输出为不含类型信息的纯 JSON。序列化失败返回 {@code null}，由 Redis 侧按空值处理，
     * 不向调用方抛异常 —— 观测类数据写入失败不应中断执行。</p>
     *
     * @param value 待序列化对象
     * @return JSON 字节；序列化失败返回 null
     */
    @Override
    public byte[] serialize(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (Exception e) {
            metrics.recordFailure(SCENE_GENERIC + "-write", e.getMessage());
            return null;
        }
    }

    /**
     * 反序列化为自由结构。
     *
     * <p>刻意返回 {@link Object}（映射/列表/标量）而非具体类型：调用方用
     * {@link #read(String, Class, String)} 或 {@link #convertList} 显式指定目标形状，
     * 使「存储里是什么」与「读成什么」之间的转换可见、可控。</p>
     *
     * @param bytes 字节数组
     * @return 自由结构；空值或失败返回 null
     */
    @Override
    public Object deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, Object.class);
        } catch (Exception e) {
            metrics.recordFailure(SCENE_GENERIC + "-read", e.getMessage());
            return null;
        }
    }

    // ── 字符串通道：显式声明目标形状 ──────────────────────────────────

    /**
     * 序列化为字符串，供纯字符串通道使用。
     *
     * @param value 待序列化对象，为 null 时返回 null
     * @param scene 场景标识，用于失败计数分桶
     * @return JSON 字符串；序列化失败返回 null
     */
    public String write(Object value, String scene) {
        if (value == null) {
            return null;
        }
        try {
            String json = MAPPER.writeValueAsString(value);
            assertNoTypeTag(json, scene);
            return json;
        } catch (Exception e) {
            metrics.recordFailure(scene + "-write", e.getMessage());
            return null;
        }
    }

    /**
     * 序列化为字符串，使用通用场景标识。
     *
     * @param value 待序列化对象
     * @return JSON 字符串；序列化失败返回 null
     */
    public String write(Object value) {
        return write(value, SCENE_GENERIC);
    }

    /**
     * 反序列化为指定类型。
     *
     * @param json  JSON 字符串
     * @param type  目标类型
     * @param scene 场景标识，用于失败计数分桶
     * @param <T>   目标类型
     * @return 反序列化结果；失败返回 null
     */
    public <T> T read(String json, Class<T> type, String scene) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            metrics.recordFailure(scene + "-read", e.getMessage());
            return null;
        }
    }

    // ── 自由结构读取 ──────────────────────────────────────────────────

    /**
     * 反序列化字节数组为映射。
     *
     * @param bytes 字节数组
     * @param scene 场景标识
     * @return 映射；失败或空值返回 null
     */
    public Map<String, Object> readMap(byte[] bytes, String scene) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return MAPPER.readValue(bytes, MAP_TYPE);
        } catch (Exception e) {
            metrics.recordFailure(scene + "-read", e.getMessage());
            return null;
        }
    }

    /**
     * 反序列化字节数组为映射，使用通用场景标识。
     *
     * @param bytes 字节数组
     * @return 映射；失败或空值返回 null
     */
    public Map<String, Object> readMap(byte[] bytes) {
        return readMap(bytes, SCENE_GENERIC);
    }

    // ── 逐条容错转换 ──────────────────────────────────────────────────

    /**
     * 逐条容错地把自由结构的列表元素转换为目标类型。
     *
     * <p>用于「存储里是自由映射，读回时要还原为领域对象」的场景。逐条转换、逐条跳过，
     * 单条失败不影响其余元素 —— 失败范围限定在单条，而非整批。</p>
     *
     * @param raw   自由结构的元素列表
     * @param type  目标类型
     * @param scene 场景标识
     * @param <T>   目标类型
     * @return 转换成功的元素列表；跳过数在失败计数中体现
     */
    public <T> List<T> convertList(Collection<?> raw, Class<T> type, String scene) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element == null) {
                continue;
            }
            try {
                if (type.isInstance(element)) {
                    result.add(type.cast(element));
                } else {
                    result.add(MAPPER.convertValue(element, type));
                }
            } catch (Exception e) {
                metrics.recordFailure(scene + "-convert", e.getMessage());
            }
        }
        return result;
    }

    // ── 收窄：把任意值转为仅含 JSON 原生类型的结构 ────────────────────

    /**
     * 把任意对象收窄为仅含 JSON 原生类型的结构。
     *
     * <p>收窄规则统一由 {@link CodecSpec#toNative(Object, ObjectMapper)} 定义，本方法只做
     * 场景标识绑定与失败计数。</p>
     *
     * @param value 原始值
     * @return 仅含 JSON 原生类型的值
     */
    public Object toNative(Object value) {
        return toNative(value, SCENE_GENERIC);
    }

    /**
     * 把任意对象收窄为仅含 JSON 原生类型的结构，并指定场景标识。
     *
     * @param value 原始值
     * @param scene 场景标识，用于失败计数分桶
     * @return 仅含 JSON 原生类型的值
     */
    public Object toNative(Object value, String scene) {
        Object result = CodecSpec.toNative(value, CodecSpec.structure());
        if (result instanceof String && !(value instanceof String) && value != null
                && !value.getClass().isEnum()) {
            // 非字符串/枚举却退化为字符串，说明收窄过程未能结构化该对象
            metrics.recordFailure(scene + "-normalize", value.getClass().getName());
        }
        return result;
    }

    /**
     * 校验一段已落盘内容的格式契约。
     *
     * <p>供启动自检与迁移检查使用：类型标识一旦落盘，读取侧就必须能实例化对应类型。</p>
     *
     * @param bytes 已落盘的字节内容
     * @return 含类型标识返回 true
     */
    public static boolean containsTypeTag(byte[] bytes) {
        return containsTypeTag(bytes == null ? null : new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * 校验一段文本的格式契约。
     *
     * @param text 待校验文本
     * @return 含类型标识返回 true
     */
    public static boolean containsTypeTag(String text) {
        return text != null && text.contains(TYPE_TAG);
    }

    private void assertNoTypeTag(String json, String scene) {
        if (containsTypeTag(json)) {
            metrics.recordFailure(scene + "-typetag", "序列化结果含类型标识，写入侧越界");
        }
    }
}
