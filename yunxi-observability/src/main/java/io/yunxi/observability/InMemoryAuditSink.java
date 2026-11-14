package io.yunxi.observability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试与演示用的内存版 {@link AuditSink}。
 */
public class InMemoryAuditSink implements AuditSink {

    private final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());

    /** 将事件追加进进程内有序列表（线程安全，供测试断言）。 */
    @Override
    public void emit(AuditEvent event) {
        events.add(event);
    }

    /** 返回当前累积的全部审计事件（不可变快照）。 */
    public List<AuditEvent> all() {
        return List.copyOf(events);
    }
}
