package io.yunxi.observability;

import java.util.List;

/**
 * 可观测平面。轨迹存储接口。运行时写入、智能平面只读读取。yunxi 的 TraceStore 刻意与任何框架命名空间的存储区分开，
 * 使产品平面拥有自己的观测 Schema。
 */
public interface TraceStore {

    /** 写入一条轨迹记录（按 traceId 幂等覆盖）。 */
    void record(TraceRecord record);

    /** 按 traceId 读取单条轨迹；不存在时返回 {@code null}。 */
    TraceRecord read(String traceId);

    /** 最近的轨迹 id，新的在前，最多 {@code limit} 条。 */
    List<String> recentTraceIds(int limit);
}
