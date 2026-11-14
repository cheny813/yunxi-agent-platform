package io.yunxi.app.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.extensions.channel.weixin.WeixinInboxClaim;
import io.agentscope.extensions.channel.weixin.WeixinInboxMessage;
import io.agentscope.extensions.channel.weixin.WeixinLease;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 微信（iLink）Redis 持久化状态存储集成测试骨架（默认禁用，需外部 Redis）。
 *
 * <p>直接驱动 {@link RedisWeixinStateStore}（复用平台全局 Redis 连接，状态机语义严格对齐框架
 * {@code InMemoryWeixinStateStore}）：lease 以 (holderId, generation) 单调版本号 + 到期时间戳做 fence、
 * inbox 接收顺序保序 + messageId 去重、claim 带 claimId + 到期、完成 / 放弃清 payload、失败释放 claim 重试。
 * 验证持久化后端与进程内实现行为一致。默认 {@link Disabled} 不进 CI，避免把外部中间件依赖引入自测环。</p>
 *
 * <p>运行时前置（本机或可达的 Redis，连接参数 {@code REDIS_HOST / REDIS_PORT}）：
 * <pre>
 *   mvn -o -pl yunxi-app test -Dtest=RedisWeixinStateStoreIntegrationTest \
 *       -DREDIS_HOST=127.0.0.1 -DREDIS_PORT=6379
 * </pre></p>
 */
@Disabled("需要本机/可达的 Redis 服务（REDIS_HOST/REDIS_PORT），默认禁用以避免环境依赖进入 CI")
public class RedisWeixinStateStoreIntegrationTest {

    private RedisWeixinStateStore newStore() {
        String host = System.getProperty("REDIS_HOST", "127.0.0.1");
        int port = Integer.getInteger("REDIS_PORT", 6379);
        LettuceConnectionFactory cf = new LettuceConnectionFactory(host, port);
        String pwd = System.getProperty("REDIS_PASSWORD", "");
        if (!pwd.isBlank()) {
            cf.setPassword(pwd);
        }
        cf.afterPropertiesSet();
        return new RedisWeixinStateStore(new StringRedisTemplate(cf));
    }

    @Test
    void leaseFenceAndInboxRoundTrip() {
        RedisWeixinStateStore store = newStore();
        String acc = "it-wx-" + System.nanoTime();
        // 首次 acquire：新 tenant
        WeixinLease lease = store.acquireLease(acc, "holderA", 1000).orElseThrow();
        assertTrue(lease.generation() > 0);
        assertTrue(store.isLeaseCurrent(acc, lease));

        // 同 holder 续用：generation 不变
        WeixinLease renewed = store.acquireLease(acc, "holderA", 1000).orElseThrow();
        assertEquals(lease.generation(), renewed.generation());

        // 异 holder 竞争：被 fence，返回 empty
        assertTrue(store.acquireLease(acc, "holderB", 1000).isEmpty());

        // 入站 batch 去重 + cursor 推进
        store.acceptBatch(acc, lease, "cursor-1", List.of(new WeixinInboxMessage("m1", "p1")));
        store.acceptBatch(acc, lease, "cursor-2", List.of(new WeixinInboxMessage("m1", "p1")));
        assertEquals("cursor-2", store.loadCursor(acc));
        List<WeixinInboxClaim> claims = store.claimMessages(acc, lease, 10, 5000);
        assertEquals(1, claims.size());
        WeixinInboxClaim claim = claims.get(0);
        assertEquals("m1", claim.messageId());

        // 完成后再 claim 无消息
        assertTrue(store.completeMessage(acc, lease, claim));
        assertTrue(store.claimMessages(acc, lease, 10, 5000).isEmpty());

        store.removeAccount(acc);
        assertFalse(store.isLeaseCurrent(acc, lease));
    }

    @Test
    void invalidClaimRejected() {
        RedisWeixinStateStore store = newStore();
        String acc = "it-wx-" + System.nanoTime();
        WeixinLease lease = store.acquireLease(acc, "holderA", 1000).orElseThrow();
        store.acceptBatch(acc, lease, "c", List.of(new WeixinInboxMessage("m1", "p1")));
        WeixinInboxClaim claim = store.claimMessages(acc, lease, 10, 5000).get(0);
        // 用错误 generation 的 lease 完成应失败（lease fence）
        WeixinLease other = new WeixinLease("holderA", lease.generation() + 1);
        assertFalse(store.completeMessage(acc, other, claim));
        store.removeAccount(acc);
    }

    @Test
    void removeAccountRequiresNonBlank() {
        RedisWeixinStateStore store = newStore();
        assertThrows(IllegalArgumentException.class, () -> store.removeAccount(" "));
    }
}
