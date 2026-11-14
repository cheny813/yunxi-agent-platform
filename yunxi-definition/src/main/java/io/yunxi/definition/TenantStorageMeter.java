package io.yunxi.definition;

/**
 * 租户存储计量钩子（产品平面治理）。挂在租户键空间边界（两个租户感知装饰器的写 / 删路径）上，
 * 由实现维护每租户近似存储用量计数，并在超阈值时发软告警（不阻断写入，fail-open）。
 *
 * <p>接口落在 yunxi-definition，使 yunxi-runtime 装饰器可依赖而不引入 runtime→yunxi-app 编译环；
 * 具体 Redis 计数实现位于 yunxi-app（与 {@code TenantQuotaMiddleware} 同后端）。
 */
public interface TenantStorageMeter {

    /**
     * 记录一次写入（v1 按写入次数近似计数）。tenantId 为 {@code null} 时静默跳过。
     */
    void recordWrite(String tenantId);

    /**
     * 记录一次删除（计数回退）。tenantId 为 {@code null} 时静默跳过。
     */
    void recordDelete(String tenantId);
}
