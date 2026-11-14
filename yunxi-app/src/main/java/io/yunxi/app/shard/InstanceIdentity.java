package io.yunxi.app.shard;

/**
 * 本实例稳定身份（instanceKey）。独立于 aistio 条件装配，基于通用 Environment 计算
 * {@code app-host-port}，与 {@code AistioConnector.resolveInstanceKey} 的 fallback 分支同款。
 */
public interface InstanceIdentity {

    /** 返回本实例稳定身份键（如 {@code yunxi-myhost-8080}）。 */
    String instanceKey();
}
