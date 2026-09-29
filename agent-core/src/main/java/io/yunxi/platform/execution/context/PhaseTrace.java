package io.yunxi.platform.execution.context;

import java.util.List;

/**
 * 阶段轨迹快照（驻留 agent-core 的数据契约）。
 *
 * <p>仅含阶段序列与时间戳，供入口网关的状态查询使用；具体聚合逻辑位于 agent-execution 的实现侧。</p>
 *
 * @param trace     阶段序列（按发生先后）
 * @param timestamp 最近一次更新时间戳（毫秒）
 * @author yunxi-agent-platform
 */
public record PhaseTrace(List<String> trace, long timestamp) {

    /** 空轨迹常量，避免调用方判空。 */
    public static final PhaseTrace EMPTY = new PhaseTrace(List.of(), 0L);
}
