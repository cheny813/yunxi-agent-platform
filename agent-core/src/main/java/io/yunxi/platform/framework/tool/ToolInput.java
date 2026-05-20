package io.yunxi.platform.framework.tool;

import java.util.Map;

/**
 * 工具输入参数
 *
 * @author yunxi-agent-platform
 */
@SuppressWarnings("unchecked")
public class ToolInput {

    /**
     * 输入参数映射
     */
    private final Map<String, Object> parameters;

    /**
     * 构造工具输入参数
     *
     * @param parameters 参数映射
     */
    public ToolInput(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    /**
     * 获取指定类型的参数值
     *
     * @param key  参数名
     * @param type 参数类型
     * @param <T>  泛型类型
     * @return 参数值
     */
    public <T> T getParameter(String key, Class<T> type) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }

        if (type.isInstance(value)) {
            return (T) value;
        }

        // 尝试类型转换
        if (type == String.class) {
            return (T) value.toString();
        }
        if (type == Integer.class || type == int.class) {
            return (T) Integer.valueOf(value.toString());
        }
        if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(value.toString());
        }
        if (type == Double.class || type == double.class) {
            return (T) Double.valueOf(value.toString());
        }
        if (type == Boolean.class || type == boolean.class) {
            return (T) Boolean.valueOf(value.toString());
        }

        throw new IllegalArgumentException("无法将参数 " + key + " 的值 " + value + " 转换为类型 " + type.getName());
    }

    /**
     * 获取字符串参数
     */
    public String getString(String key) {
        return getParameter(key, String.class);
    }

    /**
     * 获取字符串参数（带默认值）
     */
    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取整数参数
     */
    public Integer getInt(String key) {
        return getParameter(key, Integer.class);
    }

    /**
     * 获取整数参数（带默认值）
     */
    public Integer getInt(String key, Integer defaultValue) {
        Integer value = getInt(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取布尔参数
     */
    public Boolean getBoolean(String key) {
        return getParameter(key, Boolean.class);
    }

    /**
     * 获取布尔参数（带默认值）
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        Boolean value = getBoolean(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取原始参数映射
     */
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * 检查参数是否存在
     */
    public boolean hasParameter(String key) {
        return parameters != null && parameters.containsKey(key);
    }

    /**
     * 获取指定类型的参数值（原始类型）
     * <p>
     * 用于支持直接传入 Class 类型
     * </p>
     *
     * @param key  参数名
     * @param type 参数类型
     * @param <T>  泛型类型
     * @return 参数值
     */
    public <T> T getParameterRaw(String key, java.lang.reflect.Type type) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        return (T) value;
    }
}
