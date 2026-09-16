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
}
