package io.yunxi.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link InMemoryTraceStore} 行为测试：轨迹写入 / 读取 / 幂等覆盖，
 * 以及 {@code recentTraceIds} 以「新→旧」顺序返回最近 {@code limit} 条。
 *
 * <p>本类使用进程内实现，无外部依赖，属于纯单元测试。
 */
class InMemoryTraceStoreTest {

    private static TraceRecord rec(String traceId, String userId) {
        return new TraceRecord(traceId, userId, "session-1", Instant.now(), List.of());
    }

    @Test
    void recordThenReadReturnsSameRecord() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(rec("t1", "u1"));
        TraceRecord loaded = store.read("t1");
        assertEquals("t1", loaded.traceId());
        assertEquals("u1", loaded.userId());
    }

    @Test
    void readUnknownTraceIdReturnsNull() {
        assertNull(new InMemoryTraceStore().read("missing"));
    }

    @Test
    void recordOverwritesByIdempotent() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(new TraceRecord("t1", "u1", "s1", Instant.now(), List.of()));
        store.record(new TraceRecord("t1", "u2", "s2", Instant.now(), List.of()));
        // 同 traceId 必须幂等覆盖，读取返回最新一次写入。
        assertEquals("u2", store.read("t1").userId());
    }

    @Test
    void recentTraceIdsReturnsMostRecentFirstAndCapsAtLimit() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        for (int i = 1; i <= 5; i++) {
            store.record(rec("t" + i, "u"));
        }
        // 最近的 3 条须为新→旧且截断到 limit。
        assertEquals(List.of("t5", "t4", "t3"), store.recentTraceIds(3));
    }

    @Test
    void recentTraceIdsWhenFewerThanLimitReturnsAllNewestFirst() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(rec("t1", "u"));
        store.record(rec("t2", "u"));
        assertEquals(List.of("t2", "t1"), store.recentTraceIds(10));
    }

    @Test
    void recentTraceIdsWithZeroLimitReturnsEmpty() {
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(rec("t1", "u"));
        assertEquals(List.of(), store.recentTraceIds(0));
    }

    @Test
    void recentTraceIdsAfterOverwriteDoesNotDuplicate() {
        // 覆盖写入不应在活跃序中重复计数：最近查询返回唯一的最新 id。
        InMemoryTraceStore store = new InMemoryTraceStore();
        store.record(rec("t1", "u"));
        store.record(rec("t1", "u"));
        assertEquals(List.of("t1"), store.recentTraceIds(10));
    }
}
