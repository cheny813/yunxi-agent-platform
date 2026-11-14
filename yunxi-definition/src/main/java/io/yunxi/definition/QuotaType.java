package io.yunxi.definition;

/**
 * 配额超限类型，供异常与错误响应区分维度。
 */
public enum QuotaType {
    /** 每日请求数配额（滚动 24h 窗口）。 */
    DAILY_REQUEST,
    /** 并发会话数闸门（在途 Agent 调用数上限）。 */
    CONCURRENT_SESSION
}
