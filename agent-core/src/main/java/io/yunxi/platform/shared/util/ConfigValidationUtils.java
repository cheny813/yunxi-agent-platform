package io.yunxi.platform.shared.util;

import org.springframework.util.StringUtils;

/**
 * 配置验证宸ュ叿绫?
 * <p>
 * 提供统一鐨勯厤缃獙璇佹柟娉?
 * </p>
 *
 */
public final class ConfigValidationUtils {

    private ConfigValidationUtils() {
        // 宸ュ叿绫讳笉鍏佽实例鍖?
    }

    /**
     * 验证瀛楃涓蹭笉涓虹┖
     *
     * @param value     瑕侀獙璇佺殑鍊?
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撳€间负绌烘椂
     */
    public static void notEmpty(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(fieldName + " 涓嶈兘涓虹┖");
        }
    }

    /**
     * 验证对象涓嶄负绌?
     *
     * @param value     瑕侀獙璇佺殑鍊?
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撳€间负 null 鏃?
     */
    public static void notNull(Object value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " 涓嶈兘涓?null");
        }
    }

    /**
     * 验证鏁板瓧鍦ㄨ寖鍥村唴
     *
     * @param value     瑕侀獙璇佺殑鍊?
     * @param min       鏈€灏忓€硷紙鍖呭惈锛?
     * @param max       鏈€澶у€硷紙鍖呭惈锛?
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撳€间笉鍦ㄨ寖鍥村唴鏃?
     */
    public static void inRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 蹇呴』鍦?" + min + " 鍒?" + max + " 涔嬮棿锛屽綋鍓嶅€? " + value);
        }
    }

    /**
     * 验证鏁板瓧鍦ㄨ寖鍥村唴
     *
     * @param value     瑕侀獙璇佺殑鍊?
     * @param min       鏈€灏忓€硷紙鍖呭惈锛?
     * @param max       鏈€澶у€硷紙鍖呭惈锛?
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撳€间笉鍦ㄨ寖鍥村唴鏃?
     */
    public static void inRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " 蹇呴』鍦?" + min + " 鍒?" + max + " 涔嬮棿锛屽綋鍓嶅€? " + value);
        }
    }

    /**
     * 验证 URL 格式
     *
     * @param url       瑕侀獙璇佺殑 URL
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰?URL 格式鏃犳晥鏃?
     */
    public static void validUrl(String url, String fieldName) {
        notEmpty(url, fieldName);
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new IllegalArgumentException(fieldName + " 蹇呴』鏄湁鏁堢殑 HTTP/HTTPS URL: " + url);
        }
    }

    /**
     * 验证绔彛鍙?
     *
     * @param port      瑕侀獙璇佺殑绔彛
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撶鍙ｆ棤鏁堟椂
     */
    public static void validPort(int port, String fieldName) {
        inRange(port, 1, 65535, fieldName);
    }

    /**
     * 验证集合涓嶄负绌?
     *
     * @param collection 瑕侀獙璇佺殑集合
     * @param fieldName  字段名称
     * @throws IllegalArgumentException 褰撻泦鍚堜负绌烘椂
     */
    public static void notEmpty(java.util.Collection<?> collection, String fieldName) {
        if (collection == null || collection.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 涓嶈兘涓虹┖");
        }
    }

    /**
     * 验证数组涓嶄负绌?
     *
     * @param array     瑕侀獙璇佺殑数组
     * @param fieldName 字段名称
     * @throws IllegalArgumentException 褰撴暟缁勪负绌烘椂
     */
    public static void notEmpty(Object[] array, String fieldName) {
        if (array == null || array.length == 0) {
            throw new IllegalArgumentException(fieldName + " 涓嶈兘涓虹┖");
        }
    }
}