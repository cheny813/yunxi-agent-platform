package io.yunxi.runtime.tenant;

import io.yunxi.definition.TenantStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 租户上下文（ThreadLocal 持有）。在四协议入口经 {@link TenantLifecycleService#admit(String)}
 * 解析并绑定租户 {@code tenantId} 与 {@link TenantStatus}，供下游存储键空间 / 配额 / 准入闸门读取。
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT =
            ThreadLocal.withInitial(() -> null);
    private static final ThreadLocal<TenantStatus> STATUS =
            ThreadLocal.withInitial(() -> TenantStatus.ACTIVE);

    private TenantContext() {
    }

    public static void set(String tenant) {
        CURRENT.set(tenant);
        STATUS.set(TenantStatus.ACTIVE);
    }

    /** 绑定租户与已解析状态（入口闸门 {@code admit} 调用，携带 fail-closed 判定所需的 status）。 */
    public static void set(String tenant, TenantStatus status) {
        CURRENT.set(tenant);
        STATUS.set(status);
    }

    public static void clear() {
        CURRENT.remove();
        STATUS.remove();
    }

    public static String current() {
        return CURRENT.get();
    }

    /** 当前租户生命周期状态；未绑定时默认 {@link TenantStatus#ACTIVE}（fail-open）。 */
    public static TenantStatus status() {
        return STATUS.get();
    }

    public static <T> Flux<T> withTenant(Flux<T> source, String tenant) {
        return withTenant(source, tenant, TenantStatus.ACTIVE);
    }

    public static <T> Flux<T> withTenant(Flux<T> source, String tenant, TenantStatus status) {
        if (tenant == null) {
            return source;
        }
        return source
                .doFirst(() -> {
                    CURRENT.set(tenant);
                    STATUS.set(status);
                })
                .doFinally(signal -> {
                    CURRENT.remove();
                    STATUS.remove();
                });
    }

    public static <T> Mono<T> withTenant(Mono<T> source, String tenant) {
        return withTenant(source, tenant, TenantStatus.ACTIVE);
    }

    public static <T> Mono<T> withTenant(Mono<T> source, String tenant, TenantStatus status) {
        if (tenant == null) {
            return source;
        }
        return source
                .doFirst(() -> {
                    CURRENT.set(tenant);
                    STATUS.set(status);
                })
                .doFinally(signal -> {
                    CURRENT.remove();
                    STATUS.remove();
                });
    }
}
