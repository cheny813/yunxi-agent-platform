package io.yunxi.definition;

/**
 * 租户配额定义（治理元数据）。按租户维度约束 Agent 调用：每日请求数、并发会话数、存储软阈值。
 * 任一字段为 {@code null} 表示该维度不限额（fail-open）。
 *
 * <p>{@code storageBytesLimit} 为存储软告警阈值（v1 按写入次数近似计数，非精确字节），
 * 由 {@code RedisTenantStorageMeter} 在运行时读取派生；其余由 {@code TenantQuotaMiddleware} 派生。
 * 配额不进入 {@code Tenant} 定义单一真相源——{@code Tenant} 仅携带不含配额/分片运行时状态的治理元数据。
 */
public record TenantQuota(Long dailyRequestLimit, Long concurrentSessionLimit, Long storageBytesLimit) {
}
