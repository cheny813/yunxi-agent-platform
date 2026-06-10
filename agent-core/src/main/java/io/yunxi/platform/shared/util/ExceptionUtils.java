package io.yunxi.platform.shared.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.function.Supplier;

/**
 * 异常工具类
 * <p>
 * 提供统一的异常处理方式，避免重复的 try-catch 代码
 * </p>
 *
 */
@Slf4j
public final class ExceptionUtils {

    private ExceptionUtils() {
        // 工具类不允许实例化
    }

    /**
     * 执行操作，捕获异常并转为运行时异常
     *
     * @param operation    要执行的操作
     * @param errorMessage 错误消息模板
     * @param args         消息参数
     * @param <T>          值类型
     * @return 操作结果
     * @throws RuntimeException 当操作抛出异常时
     */
    public static <T> T executeOrThrow(Supplier<T> operation, String errorMessage, Object... args) {
        try {
            return operation.get();
        } catch (Exception e) {
            String message = String.format(errorMessage, args);
            log.error(message, e);
            throw new RuntimeException(message, e);
        }
    }

    /**
     * 执行操作，捕获异常并返回默认值
     *
     * @param operation    要执行的操作
     * @param defaultValue 默认值
     * @param logError     是否记录错误日志
     * @param <T>          值类型
     * @return 操作结果或默认值
     */
    public static <T> T executeOrDefault(Supplier<T> operation, T defaultValue, boolean logError) {
        try {
            return operation.get();
        } catch (Exception e) {
            if (logError) {
                log.warn("执行失败，使用默认值: {}", e.getMessage());
            }

            return defaultValue;
        }
    }

    /**
     * 执行操作，捕获异常并返回 null
     *
     * @param operation 要执行的操作
     * @param logError  是否记录错误日志
     * @param <T>       值类型
     * @return 操作结果或 null
     */
    @Nullable
    public static <T> T executeOrNull(Supplier<T> operation, boolean logError) {
        return executeOrDefault(operation, null, logError);
    }

    /**
     * 执行操作，静默忽略异常
     *
     * @param operation 要执行的操作
     * @param logError  是否记录错误日志
     */
    public static void executeQuietly(Runnable operation, boolean logError) {
        try {
            operation.run();
        } catch (Exception e) {
            if (logError) {
                log.warn("静默执行失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 包装受检异常为运行时异常
     *
     * @param e 受检异常
     * @return 运行时异常
     */
    public static RuntimeException wrap(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }

    /**
     * 包装受检异常为运行时异常（带消息）
     *
     * @param e       受检异常
     * @param message 错误消息
     * @return 运行时异常
     */
    public static RuntimeException wrap(Exception e, String message) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(message, e);
    }
}
