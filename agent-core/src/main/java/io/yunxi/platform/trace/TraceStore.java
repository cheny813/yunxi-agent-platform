package io.yunxi.platform.trace;

import java.util.List;

import reactor.core.publisher.Flux;

/**
 * 推理轨迹存储。
 *
 * <p>承接轨迹快照的写入与读取。轨迹是产品侧资产：面向界面回放、离线评估与审计追溯，
 * 与链路级的运行指标互补，两者边界不重叠。</p>
 *
 * <p>写入是旁路行为，不参与执行决策；读取支持按轨迹标识取全量快照，也支持以流的形式
 * 持续订阅。实现需保证同一节点的快照按产生顺序返回。</p>
 *
 * @author yunxi-agent-platform
 */
public interface TraceStore {

    /**
     * 写入一个轨迹快照。
     *
     * <p>不阻塞调用方，且不应因存储故障影响执行主流程。</p>
     *
     * @param span 轨迹快照
     */
    void append(ReasoningSpan span);

    /**
     * 读取一个轨迹的全部快照，按产生顺序返回。
     *
     * @param traceId 轨迹标识
     * @return 快照列表；无记录时返回空列表
     */
    List<ReasoningSpan> read(String traceId);

    /**
     * 以流的形式订阅一个轨迹的快照。
     *
     * <p>已产生的快照先回放，随后持续接收新快照，用于界面实时渲染与长连接回放。</p>
     *
     * @param traceId 轨迹标识
     * @return 快照流
     */
    Flux<ReasoningSpan> stream(String traceId);

    /**
     * 清理一个轨迹的全部快照。
     *
     * @param traceId 轨迹标识
     */
    void clear(String traceId);

    /**
     * 枚举最近活跃的轨迹标识，按活跃时间由近到远返回。
     *
     * <p>轨迹键本身不携带技能/工具等语义，故本方法只提供「枚举」能力，不提供「反查」——
     * 需要按技能筛选消费方仍须逐个 {@link #read(String)} 后自行过滤。之所以只做枚举，
     * 是因为写入侧未维护任何倒排索引，而倒排索引属于独立的演进项，不在本接口职责内。</p>
     *
     * <p>返回数量受 {@code limit} 约束，避免全量载入。实现须保证不阻塞（Redis 实现用
     * {@code SCAN} 而非 {@code KEYS}）。</p>
     *
     * @param limit 最多返回的轨迹数；非正数时返回空列表
     * @return 轨迹标识列表；无记录时返回空列表
     */
    List<String> recentTraceIds(int limit);

    /**
     * 描述本存储实现指向的位置，供诊断接口与装配日志确认「是否命中了同一个实例」。
     *
     * <p>平台所有 Redis 使用方（会话存储、轨迹存储、画像、节流）共用同一全局连接工厂，
     * 但各 {@code RedisTemplate} 对象独立，名字不同极易被误判为两套库。返回实际端点
     * （如 {@code 127.0.0.1:6379}）可让运维在运行时接口上直接比对轨迹库与预期实例是否一致，
     * 避免把应用连接到的 Redis 与 docker 容器 {@code yunxi-redis} 当成两个独立库去查。</p>
     *
     * @return 存储端点描述；内存实现返回内存标识，便于与 Redis 端点区分
     */
    String storeEndpoint();
}
