package io.yunxi.platform.shared.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志工具类
 * <p>
 * 提供统一的日志记录方法和模式
 * </p>
 */
public final class LogUtils {

    private static final Logger log = LoggerFactory.getLogger(LogUtils.class);

    private LogUtils() {
        // 工具类不允许实例化
    }

    /**
     * 记录操作成功日志
     *
     * @param operation 操作名称
     * @param details   详细信息
     */
    public static void success(String operation, Object... details) {
        if (details.length == 0) {
            log.info("{} 成功", operation);
        } else if (details.length == 1) {
            log.info("{} 成功: {}", operation, details[0]);
        } else {
            log.info("{} 成功: {}", operation, details);
        }
    }

    /**
     * 记录操作失败日志
     *
     * @param operation 操作名称
     * @param error     错误信息
     * @param cause     异常原因
     */
    public static void failure(String operation, String error, Throwable cause) {
        log.error("{} 失败: {}", operation, error, cause);
    }

    /**
     * 记录操作失败日志
     *
     * @param operation 操作名称
     * @param error     错误信息
     */
    public static void failure(String operation, String error) {
        log.error("{} 失败: {}", operation, error);
    }

    /**
     * 记录初始化完成日志
     *
     * @param component 组件名称
     * @param details   初始化详情
     */
    public static void initCompleted(String component, Object... details) {
        if (details.length == 0) {
            log.info("{} 初始化完成", component);
        } else {
            log.info("{} 初始化完成 {}", component, details);
        }
    }

    /**
     * 记录配置信息日志
     *
     * @param configName 配置名称
     * @param value      配置值
     */
    public static void config(String configName, Object value) {
        log.info("配置 {}: {}", configName, value);
    }

    /**
     * 记录性能指标日志
     *
     * @param operation  操作名称
     * @param durationMs 耗时（毫秒）
     * @param details    其他详情
     */
    public static void performance(String operation, long durationMs, Object... details) {
        if (details.length == 0) {
            log.info("{} 耗时 {}ms", operation, durationMs);
        } else {
            log.info("{} 耗时 {}ms, {}", operation, durationMs, details);
        }
    }

    /**
     * 记录调试信息（带条件）
     *
     * @param condition 记录条件
     * @param message   消息模板
     * @param args      参数
     */
    public static void debugIf(boolean condition, String message, Object... args) {
        if (condition && log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    /**
     * 记录警告信息（带条件）
     *
     * @param condition 记录条件
     * @param message   消息模板
     * @param args      参数
     */
    public static void warnIf(boolean condition, String message, Object... args) {
        if (condition) {
            log.warn(message, args);
        }
    }
}
