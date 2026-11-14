package io.yunxi.runtime.tenant;

import io.yunxi.definition.TenantStatus;

/**
 * 租户不可服务异常。当租户处于 {@link TenantStatus#SUSPENDED} 或 {@link TenantStatus#ARCHIVED}
 * 时，四协议入口 {@link TenantLifecycleService#admit(String)} 抛出，用于 fail-closed 拒绝新建会话。
 */
public class TenantNotServiceableException extends RuntimeException {

    private final String tenantId;
    private final TenantStatus status;

    public TenantNotServiceableException(String tenantId, TenantStatus status) {
        super("tenant '" + tenantId + "' is not serviceable (status=" + status + ")");
        this.tenantId = tenantId;
        this.status = status;
    }

    public String getTenantId() {
        return tenantId;
    }

    public TenantStatus getStatus() {
        return status;
    }
}
