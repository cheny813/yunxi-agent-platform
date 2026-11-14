package io.yunxi.definition;

import java.time.Instant;

/**
 * 租户定义（tenant 轴的单一真相源载体，经 {@code ConfigCenter} 持久化，与 AgentSpec 同通道）。
 *
 * <p>状态机语义见 {@link TenantStatus}。定义仅携带治理元数据，不含键空间/配额/分片运行时状态
 * （后者分别由 {@code TenantContext} / 配额 Middleware / 分片路由表在运行时派生）。
 *
 * <p>时间戳采用 ISO-8601 字符串（{@code Instant.toString()} 输出），便于 JSON 序列化零依赖、可读，
 * 避免引入 Jackson JavaTimeModule 与现有序列化策略漂移。</p>
 */
public record Tenant(String tenantId, TenantStatus status, String displayName, String createdAt, String updatedAt) {

    /** 便捷工厂：创建处于 {@code ACTIVE} 的新租户，时间戳取当前时刻（ISO-8601）。 */
    public static Tenant active(String tenantId, String displayName) {
        String now = Instant.now().toString();
        return new Tenant(tenantId, TenantStatus.ACTIVE, displayName, now, now);
    }

    /** 状态迁移：返回新实例（不可变记录语义），并刷新 {@code updatedAt}。 */
    public Tenant withStatus(TenantStatus next) {
        return new Tenant(tenantId, next, displayName, createdAt, Instant.now().toString());
    }
}
