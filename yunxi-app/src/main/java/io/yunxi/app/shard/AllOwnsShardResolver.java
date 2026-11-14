package io.yunxi.app.shard;

/**
 * 分片关闭时的默认兜底解析器：本实例拥有全部 agent（{@code owns} 永真），行为等同现状（单机 / 全量布建）。
 *
 * <p>仅当 {@code yunxi.shard.enabled=false}（默认，{@code matchIfMissing}）时由装配图注入，保证
 * {@code AgentProvisioner} 始终能注入一个 {@link AgentShardResolver}；分片开启时由
 * {@link NacosBackedShardResolver} 取代本实现（经 {@code ConditionalOnProperty} 互斥）。</p>
 */
public class AllOwnsShardResolver implements AgentShardResolver {

    @Override
    public boolean owns(String agentId) {
        return true;
    }

    @Override
    public String resolveOwnerInstanceKey(String agentId) {
        // 无 owner 信息：调用方 fail-open 本地兜底处理（不静默丢失请求、不 5xx）。
        return null;
    }
}
