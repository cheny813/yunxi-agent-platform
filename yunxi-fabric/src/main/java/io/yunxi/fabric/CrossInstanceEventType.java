package io.yunxi.fabric;

/**
 * 跨实例业务事件类型枚举（事件契约的一部分）。
 *
 * <p>yunxi 平台级业务事件，经 {@link CrossInstanceEventBus} 在集群实例间扇出。每个枚举值对应一个
 * Redis pub/sub 通道（通道名 = {@code cross-instance:<NAME>}，由 {@link CrossInstanceEventBus} 约定），
 * 订阅方按类型路由、未知类型安全忽略。
 *
 * <p>三类事件：
 * <ul>
 *   <li>{@link #AGENT_DEF_CHANGED} — Agent 定义（agents dataId）快照发生增删改。</li>
 *   <li>{@link #TENANT_CONFIG_CHANGED} — 租户配置或租户→Agent 分配（allocation / tenants dataId）变更。</li>
 *   <li>{@link #SHARD_REBALANCE} — 实例成员集发生变更（上线 / 下线），需重算分片环。</li>
 * </ul>
 *
 * <p>新增事件类型必须在此登记；订阅方对未识别的 {@code type} 一律安全忽略，保证契约向前兼容。
 */
public enum CrossInstanceEventType {

    /** Agent 定义（agents dataId）快照变更。 */
    AGENT_DEF_CHANGED,

    /** 租户配置或租户→Agent 分配（allocation / tenants dataId）变更。 */
    TENANT_CONFIG_CHANGED,

    /** 实例成员集变更，需重算分片环。 */
    SHARD_REBALANCE
}
