package io.yunxi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryAuditSink} 行为测试：审计事件按发送顺序累积，
 * 以及 {@code all()} 返回不可变快照。
 *
 * <p>本类使用进程内实现，无外部依赖，属于纯单元测试。
 */
class InMemoryAuditSinkTest {

    private static AuditEvent event(String type) {
        return new AuditEvent(type, "actor-1", Instant.now(), Map.of());
    }

    @Test
    void emitAppendsInOrder() {
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.emit(event("t1"));
        sink.emit(event("t2"));
        List<AuditEvent> all = sink.all();
        assertEquals(2, all.size());
        assertEquals("t1", all.get(0).type());
        assertEquals("t2", all.get(1).type());
    }

    @Test
    void allReturnsImmutableSnapshot() {
        // all() 返回 List.copyOf 产出的不可变列表，调用方不可就地修改。
        InMemoryAuditSink sink = new InMemoryAuditSink();
        sink.emit(event("t1"));
        List<AuditEvent> all = sink.all();
        assertThrows(UnsupportedOperationException.class,
                () -> all.add(event("x")));
    }
}
