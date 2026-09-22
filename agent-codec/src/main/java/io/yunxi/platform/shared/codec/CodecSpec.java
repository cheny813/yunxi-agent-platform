package io.yunxi.platform.shared.codec;

import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 全平台 JSON 绑定的唯一配置来源。
 *
 * <p>所有需要 JSON 编解码的组件都从本类取绑定器，不得自行 {@code new ObjectMapper()} 或
 * 自行 {@code JsonMapper.builder()}。散落的绑定器配置会让同一份数据在不同组件里出现不同的
 * 读写行为，而这类差异只在特定字段上暴露，极难定位。</p>
 *
 * <p>本类提供两种形态，按用途选择：</p>
 *
 * <p><b>{@link #storage()} —— 持久化绑定器。</b>用于写入 Redis、数据库等持久化介质的
 * 值编解码，以及从中读取。特点：</p>
 * <ul>
 *   <li>不写入类型信息，序列化结果只含 JSON 原生类型；</li>
 *   <li>关闭未知字段失败，使持久化对象可增字段而不破坏历史数据读取；</li>
 *   <li>注册 Java 时间模块，时间以 ISO 字符串而非时间戳表示。</li>
 * </ul>
 *
 * <p><b>{@link #structure()} —— 结构转换绑定器。</b>用于把领域对象转换为普通结构
 * （映射 / 列表 / 标量），不直接落盘。特点：</p>
 * <ul>
 *   <li>配置最小，不受持久化格式演进影响 —— 结构转换的语义应保持稳定；</li>
 *   <li>转换结果应经 {@link #toNative(Object, ObjectMapper)} 规整后再进入持久化。</li>
 * </ul>
 *
 * @author yunxi-agent-platform
 */
public final class CodecSpec {

    private CodecSpec() {
    }

    /**
     * 持久化绑定器：不写类型信息、容忍未知字段、ISO 时间。
     */
    private static final ObjectMapper STORAGE_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .build();

    /**
     * 结构转换绑定器：最小配置，不参与持久化格式决策。
     */
    private static final ObjectMapper STRUCTURE_MAPPER = new ObjectMapper();

    /**
     * 返回持久化绑定器。
     *
     * @return 用于持久化介质读写的绑定器
     */
    public static ObjectMapper storage() {
        return STORAGE_MAPPER;
    }

    /**
     * 返回结构转换绑定器。
     *
     * @return 用于领域对象结构转换的绑定器
     */
    public static ObjectMapper structure() {
        return STRUCTURE_MAPPER;
    }

    /**
     * 把任意对象收窄为仅含 JSON 原生类型的结构。
     *
     * <p>递归处理映射、集合与数组；枚举取名称；其余领域对象先转为普通结构再递归收窄。
     * 转换失败时退化为字符串表示，不抛异常 —— 收窄是写入侧的卫生处理，不应中断调用方流程。</p>
     *
     * @param value    原始值
     * @param mapper   用于领域对象转向的绑定器，传 null 时使用 {@link #structure()}
     * @return 仅含 JSON 原生类型的值
     */
    public static Object toNative(Object value, ObjectMapper mapper) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), toNative(v, mapper)));
            return result;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new java.util.ArrayList<>();
            iterable.forEach(v -> result.add(toNative(v, mapper)));
            return result;
        }
        if (value instanceof Object[] array) {
            List<Object> result = new java.util.ArrayList<>(array.length);
            for (Object v : array) {
                result.add(toNative(v, mapper));
            }
            return result;
        }
        if (value.getClass().isEnum()) {
            return ((Enum<?>) value).name();
        }
        ObjectMapper effective = mapper == null ? STRUCTURE_MAPPER : mapper;
        try {
            Object tree = effective.convertValue(value, Object.class);
            return tree == value ? String.valueOf(value) : toNative(tree, effective);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
