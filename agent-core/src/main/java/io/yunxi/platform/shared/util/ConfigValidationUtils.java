package io.yunxi.platform.shared.util;

import org.springframework.util.StringUtils;

/**
 * 验证工具类
 * <p>
 * 提供统一的配置验证方法
 * </p>
 *
 */
public final class ConfigValidationUtils {

    private ConfigValidationUtils() {
        // 工具类不允许实例化
    }

    /**
     * 验证字符串不为空
     *
     * @param value     要验证的值
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当值为空时
     */
    public static void notEmpty(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    /**
     * 验证不为 null
     *
     * @param value     要验证的值
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当值为 null 时
     */
    public static void notNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 不能为 null");
        }
    }

    /**
     * 验证数字在范围内
     *
     * @param value     要验证的值
     * @param min       最小值（包含）
     * @param max       最大值（包含）
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当值不在范围内时
     */
    public static void inRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 必须在 " + min + " 到 " + max + " 之间，当前值: " + value);
        }
    }

    /**
     * 验证数字在范围内
     *
     * @param value     要验证的值
     * @param min       最小值（包含）
     * @param max       最大值（包含）
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当值不在范围内时
     */
    public static void inRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 必须在 " + min + " 到 " + max + " 之间，当前值: " + value);
        }
    }

    /**
     * 验证 URL 格式
     *
     * @param url       要验证的 URL
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当 URL 格式无效时
     */
    public static void validUrl(String url, String fieldName) {
        notEmpty(url, fieldName);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(fieldName + " 必须是有效的 HTTP/HTTPS URL: " + url);
        }
    }

    /**
     * 验证端口号
     *
     * @param port      要验证的端口
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当端口号无效时
     */
    public static void validPort(int port, String fieldName) {
        inRange(port, 1, 65535, fieldName);
    }

    /**
     * 验证不为空
     *
     * @param collection 要验证的集合
     * @param fieldName  字段名
     * @throws IllegalArgumentException 当集合为空时
     */
    public static void notEmpty(java.util.Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }

    /**
     * 验证不为空
     *
     * @param array     要验证的数组
     * @param fieldName 字段名
     * @throws IllegalArgumentException 当数组为空时
     */
    public static void notEmpty(Object[] array, String fieldName) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
    }
}
