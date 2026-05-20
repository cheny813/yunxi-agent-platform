package io.yunxi.platform.shared.util;

import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本解析工具类
 * <p>
 * 提供通用的文本解析功能，从键值对格式文本中提取数据
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
public class TextParserUtil {

    /**
     * 从文本行中解析键值对
     * <p>
     * 支持格式：key=value 或 key="value" 或 key='value'
     * </p>
     *
     * @param text 文本行
     * @return 键值对映射
     */
    public static Map<String, String> parseKeyValuePairs(String text) {
        Map<String, String> result = new HashMap<>();

        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        // 匹配 key=value 或 key="value" 或 key='value'
        Pattern pattern = Pattern.compile("(\\w+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);

            // 去除引号
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            } else if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }

            result.put(key, value);
        }

        return result;
    }

    /**
     * 从文本行中解析键值对并尝试类型转换
     *
     * @param text 文本行
     * @return 键值对映射（值可能为 Integer, Long, Double, Boolean, String）
     */
    public static Map<String, Object> parseKeyValuePairsWithTypeConversion(String text) {
        Map<String, Object> result = new HashMap<>();

        if (text == null || text.trim().isEmpty()) {
            return result;
        }

        Pattern pattern = Pattern.compile("(\\w+)\\s*=\\s*(\\S+)");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2);

            // 尝试类型转换
            Object convertedValue = convertValue(value);
            result.put(key, convertedValue);
        }

        return result;
    }

    /**
     * 从文本行中提取 Long 值
     *
     * @param text 文本行
     * @param key  键名
     * @return Long 值，如果解析失败返回 null
     */
    public static Long parseLong(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("解析 Long 失败: key={}, value={}", key, matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 从文本行中提取 Integer 值
     *
     * @param text 文本行
     * @param key  键名
     * @return Integer 值，如果解析失败返回 null
     */
    public static Integer parseInt(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*(\\d+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("解析 Integer 失败: key={}, value={}", key, matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 从文本行中提取 Double 值
     *
     * @param text 文本行
     * @param key  键名
     * @return Double 值，如果解析失败返回 null
     */
    public static Double parseDouble(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("解析 Double 失败: key={}, value={}", key, matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 从文本行中提取 BigDecimal 值
     *
     * @param text 文本行
     * @param key  键名
     * @return BigDecimal 值，如果解析失败返回 null
     */
    public static java.math.BigDecimal parseBigDecimal(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*([\\d.]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return new java.math.BigDecimal(matcher.group(1));
            } catch (NumberFormatException e) {
                log.warn("解析 BigDecimal 失败: key={}, value={}", key, matcher.group(1));
            }
        }
        return null;
    }

    /**
     * 从文本行中提取 String 值
     *
     * @param text 文本行
     * @param key  键名
     * @return String 值，如果解析失败返回 null
     */
    public static String parseString(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*([^,\\}\\s]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 从文本行中提取 Boolean 值
     *
     * @param text 文本行
     * @param key  键名
     * @return Boolean 值，如果解析失败返回 null
     */
    public static Boolean parseBoolean(String text, String key) {
        if (text == null || key == null) {
            return null;
        }

        Pattern pattern = Pattern.compile(Pattern.quote(key) + "\\s*=\\s*(true|false|1|0)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String value = matcher.group(1).toLowerCase();
            return value.equals("true") || value.equals("1");
        }
        return null;
    }

    /**
     * 从 Row 格式文本中提取数据
     * <p>
     * 支持格式：Row 1: {key1=value1, key2=value2, ...}
     * </p>
     *
     * @param rowText Row 格式文本
     * @return 数据映射
     */
    public static Map<String, Object> parseRow(String rowText) {
        return parseKeyValuePairsWithTypeConversion(rowText);
    }

    /**
     * 尝试将字符串值转换为合适的类型
     *
     * @param value 字符串值
     * @return 转换后的值（Integer, Long, Double, Boolean, String）
     */
    public static Object convertValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // 尝试解析为 Boolean
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }

        // 尝试解析为 Long
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e1) {
            // 不是 Long
        }

        // 尝试解析为 Double
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e2) {
            // 不是 Double
        }

        // 返回字符串
        return value;
    }

    /**
     * 提取正则匹配的分组
     *
     * @param text    文本
     * @param pattern 正则表达式
     * @param group   分组索引
     * @return 匹配的值，如果未匹配返回 null
     */
    public static String extractGroup(String text, String pattern, int group) {
        if (text == null || pattern == null) {
            return null;
        }

        try {
            Pattern p = Pattern.compile(pattern);
            Matcher matcher = p.matcher(text);
            if (matcher.find()) {
                return matcher.group(group);
            }
        } catch (Exception e) {
            log.warn("正则匹配失败: pattern={}", pattern, e);
        }

        return null;
    }

    /**
     * 检查文本是否匹配正则表达式
     *
     * @param text    文本
     * @param pattern 正则表达式
     * @return true-匹配，false-不匹配
     */
    public static boolean matches(String text, String pattern) {
        if (text == null || pattern == null) {
            return false;
        }

        try {
            Pattern p = Pattern.compile(pattern);
            return p.matcher(text).find();
        } catch (Exception e) {
            log.warn("正则匹配失败: pattern={}", pattern, e);
            return false;
        }
    }
}
