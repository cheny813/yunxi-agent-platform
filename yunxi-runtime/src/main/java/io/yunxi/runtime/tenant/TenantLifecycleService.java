package io.yunxi.runtime.tenant;

import io.yunxi.config.ConfigCenter;
import io.yunxi.definition.Tenant;
import io.yunxi.definition.TenantStatus;
import java.util.List;
import java.util.Optional;

/**
 * 租户生命周期编排（tenant 轴的治理门面）。
 *
 * <p>薄编排层：租户定义的持久化与多实例一致由 {@link ConfigCenter}（Nacos 权威 + 监听器下发）承载，
 * 本服务只负责状态机迁移与 fail-open 语义。解析与运行时拒绝由 {@link TenantResolver}/{@link TenantContext}
 * 在四协议入口消费 {@link #getStatus(String)} 完成。</p>
 *
 * <p>fail-open：未定义租户视为 {@code ACTIVE}（与 {@code TenantConfig#defaultTenant()} 透传一致），
 * 不阻断请求。</p>
 */
public class TenantLifecycleService {

    private final ConfigCenter configCenter;

    public TenantLifecycleService(ConfigCenter configCenter) {
        this.configCenter = configCenter;
    }

    /** 注册（或覆盖）一个租户定义。 */
    public void register(Tenant tenant) {
        configCenter.registerTenant(tenant);
    }

    /** 按 tenantId 读取租户定义。 */
    public Optional<Tenant> getTenant(String tenantId) {
        return configCenter.getTenant(tenantId);
    }

    /** 列出全部租户定义（不可变快照）。 */
    public List<Tenant> listTenants() {
        return configCenter.listTenants();
    }

    /** 读取租户状态；未定义或 {@code tenantId == null}（多租户关闭）返回 {@link TenantStatus#ACTIVE}（fail-open）。 */
    public TenantStatus getStatus(String tenantId) {
        if (tenantId == null) {
            return TenantStatus.ACTIVE; // 多租户关闭 / 未解析：fail-open 视为 ACTIVE
        }
        return configCenter.getTenant(tenantId)
                .map(Tenant::status)
                .orElse(TenantStatus.ACTIVE);
    }

    /** 是否可正常服务：{@code ACTIVE} 或未定义（fail-open）为 true，{@code SUSPENDED}/{@code ARCHIVED} 为 false。 */
    public boolean isActive(String tenantId) {
        return getStatus(tenantId) == TenantStatus.ACTIVE;
    }

    /**
     * 准入闸门（四协议入口 fail-closed 单一实现）：解析租户状态并绑定到 {@link TenantContext}，
     * 非 {@link TenantStatus#ACTIVE} 抛 {@link TenantNotServiceableException}。
     *
     * <p>复用 {@link #getStatus(String)}（缺省 fail-open 为 ACTIVE），因此未定义 / 多租户关闭
     * 时的 {@code defaultTenant} 均为 ACTIVE，不阻断既有行为。</p>
     */
    public TenantStatus admit(String tenantId) {
        TenantStatus status = getStatus(tenantId);
        TenantContext.set(tenantId, status);
        if (status != TenantStatus.ACTIVE) {
            throw new TenantNotServiceableException(tenantId, status);
        }
        return status;
    }

    /** 停用：ACTIVE → SUSPENDED（拒绝新建会话）。未知租户抛 {@link IllegalStateException}。 */
    public void markSuspended(String tenantId) {
        Tenant t = configCenter.getTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("unknown tenant: " + tenantId));
        configCenter.registerTenant(t.withStatus(TenantStatus.SUSPENDED));
    }

    /** 退役：ACTIVE → ARCHIVED（拒绝服务，触发清理）。未知租户抛 {@link IllegalStateException}。 */
    public void markArchived(String tenantId) {
        Tenant t = configCenter.getTenant(tenantId)
                .orElseThrow(() -> new IllegalStateException("unknown tenant: " + tenantId));
        configCenter.registerTenant(t.withStatus(TenantStatus.ARCHIVED));
    }
}
