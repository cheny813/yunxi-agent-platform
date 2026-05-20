package io.yunxi.platform.shared.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;

import java.util.function.Supplier;

/**
 * 异常处理宸ュ叿绫?
 * <p>
 * 提供统一鐨勫紓甯稿鐞嗘ā寮忥紝閬垮厤閲嶅鐨?try-catch 浠ｇ爜
 * </p>
 *
 */
@Slf4j
public final class ExceptionUtils {

    private ExceptionUtils() {
        // 宸ュ叿绫讳笉鍏佽实例鍖?
    }

    /**
     * 执行操作锛屾崟鑾峰紓甯稿苟转换涓鸿繍琛屾椂异常
     *
     * @param operation    瑕佹墽琛岀殑操作
     * @param errorMessage 错误信息模板
     * @param args         错误信息参数
     * @param <T>          返回鍊肩被鍨?
     * @return 操作结果
     * @throws RuntimeException 褰撴搷浣滄姏鍑哄紓甯告椂
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
     * 执行操作锛屾崟鑾峰紓甯稿苟返回默认鍊?
     *
     * @param operation    瑕佹墽琛岀殑操作
     * @param defaultValue 默认鍊?
     * @param logError     是否记录错误日志
     * @param <T>          返回鍊肩被鍨?
     * @return 操作结果鎴栭粯璁ゅ€?
     */
    public static <T> T executeOrDefault(Supplier<T> operation, T defaultValue, boolean logError) {
        try {
            return operation.get();
        } catch (Exception e) {
            if (logError) {
                log.warn("操作执行失败锛屼娇鐢ㄩ粯璁ゅ€? {}", e.getMessage());
            }
            return defaultValue;
        }
    }

    /**
     * 执行操作锛屾崟鑾峰紓甯稿苟返回 null
     *
     * @param operation 瑕佹墽琛岀殑操作
     * @param logError  是否记录错误日志
     * @param <T>       返回鍊肩被鍨?
     * @return 操作结果鎴?null
     */
    @Nullable
    public static <T> T executeOrNull(Supplier<T> operation, boolean logError) {
        return executeOrDefault(operation, null, logError);
    }

    /**
     * 执行操作锛屽拷鐣ュ紓甯?
     *
     * @param operation 瑕佹墽琛岀殑操作
     * @param logError  是否记录错误日志
     */
    public static void executeQuietly(Runnable operation, boolean logError) {
        try {
            operation.run();
        } catch (Exception e) {
            if (logError) {
                log.warn("闈欓粯执行操作失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 包装鍙楁异常涓鸿繍琛屾椂异常
     *
     * @param e 鍙楁异常
     * @return 运行鏃跺紓甯?
     */
    public static RuntimeException wrap(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }

    /**
     * 包装鍙楁异常涓鸿繍琛屾椂异常锛堝甫消息锛?
     *
     * @param e       鍙楁异常
     * @param message 错误消息
     * @return 运行鏃跺紓甯?
     */
    public static RuntimeException wrap(Exception e, String message) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(message, e);
    }
}