package io.yunxi.platform.intent.classify;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.yunxi.platform.intent.Intent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LlmResultCache} 单元测试。
 *
 * <p>覆盖 key 构造（domain|归一化 query|version）、TTL 惰性过期、热度 TopN 与
 * max-size 超限裁剪。</p>
 */
@DisplayName("LlmResultCache 缓存单元测试")
class LlmResultCacheTest {

    private static Intent intent(String id) {
        return new Intent(id, "意图-" + id, 0.8, Intent.MATCHED_LLM);
    }

    @Test
    @DisplayName("put/get 命中；未命中返回 null")
    void putAndGet() {
        LlmResultCache cache = new LlmResultCache(Duration.ofDays(7), 100);
        cache.put("base|query|1", intent("a"));

        assertThat(cache.get("base|query|1").intentId()).isEqualTo("a");
        assertThat(cache.get("base|query|2")).isNull();
    }

    @Test
    @DisplayName("key 归一化：大小写/空白折叠，同一查询命中同一缓存")
    void keyNormalized() {
        assertThat(LlmResultCache.key("base", " 我的订单  到哪了 ", 1L))
                .isEqualTo(LlmResultCache.key("base", "我的订单 到哪了", 1L));
    }

    @Test
    @DisplayName("key 含白名单版本：规则版本变化即失效")
    void keyVersioned() {
        assertThat(LlmResultCache.key("base", "query", 1L))
                .isNotEqualTo(LlmResultCache.key("base", "query", 2L));
    }

    @Test
    @DisplayName("TTL 惰性过期：过期后返回 null")
    void ttlExpired() throws InterruptedException {
        LlmResultCache cache = new LlmResultCache(Duration.ofMillis(1), 100);
        cache.put("k", intent("a"));
        Thread.sleep(10);

        assertThat(cache.get("k")).isNull();
    }

    @Test
    @DisplayName("热度 TopN：命中次数多者优先")
    void hotEntriesTopN() {
        LlmResultCache cache = new LlmResultCache(Duration.ofDays(7), 100);
        cache.put("hot", intent("hot"));
        cache.put("cold", intent("cold"));
        cache.get("hot");
        cache.get("hot");
        cache.get("cold");

        List<Intent> top1 = cache.hotEntries(null, 1);
        assertThat(top1).hasSize(1);
        assertThat(top1.get(0).intentId()).isEqualTo("hot");
    }

    @Test
    @DisplayName("hotEntries 按域过滤：key 前缀 domain| 生效")
    void hotEntriesByDomain() {
        LlmResultCache cache = new LlmResultCache(Duration.ofDays(7), 100);
        cache.put("base|query-a|1", intent("base-a"));
        cache.put("nutrition|query-b|1", intent("nutrition-b"));

        List<Intent> nutrition = cache.hotEntries("nutrition", 10);
        assertThat(nutrition).hasSize(1);
        assertThat(nutrition.get(0).intentId()).isEqualTo("nutrition-b");

        assertThat(cache.hotEntries("base", 10)).hasSize(1);
        // 全域
        assertThat(cache.hotEntries(null, 10)).hasSize(2);
    }

    @Test
    @DisplayName("max-size 超限裁剪：容量维持不膨胀")
    void maxSizeEvict() {
        LlmResultCache cache = new LlmResultCache(Duration.ofDays(7), 1);
        cache.put("k1", intent("a"));
        cache.put("k2", intent("b"));
        cache.put("k3", intent("c"));

        assertThat(cache.hotEntries(null, 10)).hasSize(1);
    }
}
