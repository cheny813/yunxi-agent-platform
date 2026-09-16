package io.yunxi.platform.trace;

/**
 * 轨迹节点的快照阶段。
 *
 * <p>轨迹节点是流过管道的快照对象，同一节点的稳定标识会先后出现三种快照：开始、增量、关闭。
 * 消费方按稳定标识分组后即可还原出完整节点，流式输出与最终状态由同一序列表达。</p>
 *
 * @author yunxi-agent-platform
 */
public enum Snapshot {

    /** 开始：节点首次出现 */
    OPEN,

    /** 增量：节点产生内容片段 */
    DELTA,

    /** 关闭：节点终结，终态字段就绪 */
    CLOSED
}
