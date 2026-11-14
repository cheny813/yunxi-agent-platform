package io.yunxi.observability;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 测试与单实例演示用的内存版 {@link TraceStore}。保留插入顺序，使 {@link #recentTraceIds(int)} 稳定且可复现。
 */
public class InMemoryTraceStore implements TraceStore {

    private final Map<String, TraceRecord> store = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> order = new ConcurrentLinkedDeque<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /** 写入轨迹并按插入顺序追加到活跃序列表，便于按时间回放。同 traceId 幂等覆盖，不重复入序。 */
    @Override
    public void record(TraceRecord record) {
        store.put(record.traceId(), record);
        if (seen.add(record.traceId())) {
            order.addLast(record.traceId());
        }
    }

    /** 按 traceId 读取；不存在返回 {@code null}。 */
    @Override
    public TraceRecord read(String traceId) {
        return store.get(traceId);
    }

    /** 按插入顺序（新→旧）返回最近 {@code limit} 条轨迹 id；{@code limit<=0} 返回空列表。 */
    @Override
    public List<String> recentTraceIds(int limit) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = order.descendingIterator(); it.hasNext();) {
            if (out.size() >= limit) {
                break;
            }
            out.add(it.next());
        }
        return out;
    }
}
