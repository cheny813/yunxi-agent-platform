package io.yunxi.runtime.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 自然键 / 存储主键派生正确性：幂等 upsert + 跨租户隔离 + 分隔符歧义无碰撞。 */
class DocumentChunkTest {

    @Test
    void sameNaturalKeyDerivesSameStorageKey_idempotentUpsert() {
        String a = DocumentChunk.storageKey("t1", "col", "u", "c1");
        String b = DocumentChunk.storageKey("t1", "col", "u", "c1");
        assertEquals(a, b, "相同自然键必须派生相同存储主键（幂等 upsert，消除集群写分叉）");
    }

    @Test
    void differentTenantDerivesDifferentStorageKey_crossTenantIsolation() {
        String a = DocumentChunk.storageKey("tenant-A", "col", "u", "c1");
        String b = DocumentChunk.storageKey("tenant-B", "col", "u", "c1");
        assertNotEquals(a, b, "不同租户必须派生不同存储主键，从主键层面杜绝跨租户覆盖");
    }

    @Test
    void delimiterAmbiguityDoesNotCollide() {
        // 含 '#' 的分量不得与另一组分量产生相同自然键 / 存储主键（W5 跨租户覆盖隐患）。
        String a = DocumentChunk.storageKey("a", "b#c", "d", "e");
        String b = DocumentChunk.storageKey("a#b", "c", "d", "e");
        assertNotEquals(a, b, "长度前缀编码须消除 '#' 歧义，避免自然键碰撞");

        // 含 '|' 的分量同样无歧义。
        String x = DocumentChunk.storageKey("t", "col|a", "u", "c1");
        String y = DocumentChunk.storageKey("t|col", "a", "u", "c1");
        assertNotEquals(x, y, "长度前缀编码须消除 '|' 歧义，避免自然键碰撞");
    }

    @Test
    void nullTenantEncodesDeterministically_singleTenantFallback() {
        String a = DocumentChunk.storageKey(null, "col", "u", "c1");
        String b = DocumentChunk.storageKey(null, "col", "u", "c1");
        assertEquals(a, b, "null 租户（单实例 / 单租户回落）须确定性编码");
    }
}
