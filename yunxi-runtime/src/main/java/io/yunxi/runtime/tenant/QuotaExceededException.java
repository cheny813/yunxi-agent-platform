package io.yunxi.runtime.tenant;

import io.yunxi.definition.QuotaType;

/**
 * 租户配额超限异常（fail-closed）：在 {@code TenantQuotaMiddleware#onAgent} 入口抛出，
 * 由 {@code TenantQuotaExceptionHandler} 统一映射为 HTTP 429。
 */
public class QuotaExceededException extends RuntimeException {

    private final String tenantId;
    private final QuotaType type;
    private final long limit;

    public QuotaExceededException(String tenantId, QuotaType type, long limit) {
        super("Tenant quota exceeded: tenant=" + tenantId + " type=" + type + " limit=" + limit);
        this.tenantId = tenantId;
        this.type = type;
        this.limit = limit;
    }

    public String getTenantId() {
        return tenantId;
    }

    public QuotaType getType() {
        return type;
    }

    public long getLimit() {
        return limit;
    }
}
