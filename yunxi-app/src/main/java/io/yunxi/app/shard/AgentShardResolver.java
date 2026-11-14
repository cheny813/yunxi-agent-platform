package io.yunxi.app.shard;

/**
 * 分片归属解析器：给定 agentId 返回 owner 物理实例身份（instanceKey），
 * 由一致性哈希环派生。框架无此原语，yunxi 产品平面自持。
 */
public interface AgentShardResolver {

    /** 本实例是否拥有该 agent（哈希环归属 == 本实例 instanceKey）。 */
    boolean owns(String agentId);

    /**
     * 解析 owner 实例身份（instanceKey）。
     *
     * @return owner 物理 instanceKey；无法判定（fail-open 由调用方兜底）时返回 null
     */
    String resolveOwnerInstanceKey(String agentId);
}
