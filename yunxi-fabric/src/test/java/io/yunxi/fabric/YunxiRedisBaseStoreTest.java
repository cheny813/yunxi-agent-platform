package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

// Behavior test for YunxiRedisBaseStore over a faked StringRedisTemplate. The real storage semantics
// (Hash value+version, ZSet index, Lua CAS) are simulated in-memory so we verify correctness without a
// live Redis. Satisfies spec requirement "baseStore ... StoreItem 往返一致、putIfVersion 原子 CAS 语义正确".
class YunxiRedisBaseStoreTest {

    private static final List<String> NS = List.of("agent", "sess-1");

    @Test
    void putThenGetRoundTripsValueAndVersion() {
        Context ctx = new Context();
        YunxiRedisBaseStore store = ctx.store();

        Map<String, Object> value = new HashMap<>();
        value.put("k", "v");
        store.put(NS, "chunk-a", value);

        StoreItem item = store.get(NS, "chunk-a");
        assertEquals("chunk-a", item.key());
        assertEquals(1L, item.version());
        assertEquals("v", item.value().get("k"));
    }

    @Test
    void putIfVersionSucceedsOnMatchAndBumpsVersion() {
        Context ctx = new Context();
        YunxiRedisBaseStore store = ctx.store();

        Map<String, Object> v1 = Map.of("n", 1);
        store.put(NS, "c", v1); // version 1

        Map<String, Object> v2 = Map.of("n", 2);
        assertTrue(store.putIfVersion(NS, "c", v2, 1L), "CAS must succeed when expectedVersion matches current");
        assertEquals(2L, store.get(NS, "c").version());
        assertEquals(2, store.get(NS, "c").value().get("n"));
    }

    @Test
    void putIfVersionFailsOnMismatchAndKeepsVersion() {
        Context ctx = new Context();
        YunxiRedisBaseStore store = ctx.store();

        store.put(NS, "c", Map.of("n", 1)); // version 1

        assertFalse(store.putIfVersion(NS, "c", Map.of("n", 9), 5L), "CAS must fail when expectedVersion mismatches");
        assertEquals(1L, store.get(NS, "c").version());
        assertEquals(1, store.get(NS, "c").value().get("n"));
    }

    @Test
    void searchListsKeysAndDeleteClears() {
        Context ctx = new Context();
        YunxiRedisBaseStore store = ctx.store();

        store.put(NS, "c1", Map.of("x", 1));
        store.put(NS, "c2", Map.of("x", 2));

        List<StoreItem> found = store.search(NS, 10, 0);
        assertEquals(2, found.size());
        Set<String> keys = new HashSet<>();
        for (StoreItem it : found) {
            keys.add(it.key());
        }
        assertTrue(keys.contains("c1"));
        assertTrue(keys.contains("c2"));

        store.delete(NS, "c1");
        assertNull(store.get(NS, "c1"));
        assertEquals(1, store.search(NS, 10, 0).size());
    }

    /** In-memory fake of the Redis Hash + ZSet semantics driven by the store's Lua scripts. */
    private static final class Context implements Answer<Object> {
        final Map<String, Map<String, String>> hashStore = new HashMap<>();
        final Map<String, Set<String>> indexStore = new HashMap<>();
        final StringRedisTemplate template = mock(StringRedisTemplate.class);

        Context() {
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            ZSetOperations<String, String> zsetOps = mock(ZSetOperations.class);
            when(template.opsForHash()).thenReturn(hashOps);
            when(template.opsForZSet()).thenReturn(zsetOps);
            when(hashOps.entries(any())).thenAnswer(inv -> hashStore.get(inv.getArgument(0)));
            when(zsetOps.rangeByLex(any(), any(Range.class)))
                    .thenAnswer(inv -> new HashSet<>(indexStore.getOrDefault(inv.getArgument(0), Set.of())));
            when(template.execute(any(), anyList(), any(Object[].class))).thenAnswer(this);
        }

        YunxiRedisBaseStore store() {
            return new YunxiRedisBaseStore(template, "yunxi:store:");
        }

        @Override
        public Object answer(InvocationOnMock inv) {
            Object[] raw = inv.getArguments();
            RedisScript<?> script = (RedisScript<?>) raw[0];
            List<String> keys = (List<String>) raw[1];
            Object[] args =
                    (raw.length >= 3 && raw[2] instanceof Object[])
                            ? (Object[]) raw[2]
                            : Arrays.copyOfRange(raw, 2, raw.length);
            String itemKey = keys.get(0);
            String idxKey = keys.get(1);
            if (Long.class.equals(script.getResultType())) {
                hashStore.remove(itemKey);
                String key = (String) args[0];
                indexStore.getOrDefault(idxKey, Set.of()).remove(key);
                return 1L;
            }
            String json = (String) args[0];
            String key = (String) args[1];
            String curStr = hashStore.containsKey(itemKey) ? hashStore.get(itemKey).get("version") : null;
            long cur = curStr == null ? 0L : Long.parseLong(curStr);
            if (args.length == 3) {
                long expected = Long.parseLong((String) args[2]);
                if (cur != expected) {
                    return "0";
                }
            }
            long v = cur + 1;
            Map<String, String> h = new HashMap<>();
            h.put("value", json);
            h.put("version", String.valueOf(v));
            hashStore.put(itemKey, h);
            indexStore.computeIfAbsent(idxKey, k -> new HashSet<>()).add(key);
            return String.valueOf(v);
        }
    }
}
