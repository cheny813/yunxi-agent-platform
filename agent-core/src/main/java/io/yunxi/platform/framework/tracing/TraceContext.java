package io.yunxi.platform.framework.tracing;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 链路追踪上下文
 * <p>
 * 管理 TraceId 的生成、传递和清理，支持跨线程和跨服务调用。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public class TraceContext {

    /**
     * TraceId HTTP 头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中 TraceId 的键名
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    /**
     * 生成新的 TraceId
     *
     * @return 新的 TraceId（UUID 格式，去除横线）
     */
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 获取当前线程的 TraceId
     *
     * @return 当前 TraceId，如果不存在则返回 null
     */
    public static String getCurrentTraceId() {
        return MDC.get(TRACE_ID_MDC_KEY);
    }

    /**
     * 设置 TraceId 到当前线程的 MDC
     *
     * @param traceId TraceId
     */
    public static void setCurrentTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            MDC.put(TRACE_ID_MDC_KEY, traceId);
        }
    }

    /**
     * 设置 TraceId，如果不存在则生成新的
     *
     * @param traceId TraceId，可为 null
     * @return 实际设置的 TraceId
     */
    public static String setTraceIdIfAbsent(String traceId) {
        String currentTraceId = getCurrentTraceId();
        if (currentTraceId == null) {
            if (traceId == null || traceId.isEmpty()) {
                traceId = generateTraceId();
            }
            setCurrentTraceId(traceId);
            return traceId;
        }
        return currentTraceId;
    }

    /**
     * 清除当前线程的 TraceId
     */
    public static void clear() {
        MDC.remove(TRACE_ID_MDC_KEY);
    }

    /**
     * 执行 Runnable 并携带当前 TraceId 到新线程
     *
     * @param runnable 任务
     * @return 包装后的 Runnable
     */
    public static Runnable wrapWithTraceId(Runnable runnable) {
        String traceId = getCurrentTraceId();
        return () -> {
            setCurrentTraceId(traceId);
            try {
                runnable.run();
            } finally {
                clear();
            }
        };
    }
}
