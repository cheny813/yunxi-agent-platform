package io.yunxi.app.im;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.runtime.Json;
import io.agentscope.extensions.channel.weixin.WeixinInboxClaim;
import io.agentscope.extensions.channel.weixin.WeixinInboxMessage;
import io.agentscope.extensions.channel.weixin.WeixinLease;
import io.agentscope.extensions.channel.weixin.WeixinStateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 微信（iLink）渠道的持久化状态存储，实现 AgentScope-Java {@link WeixinStateStore} 接口。
 *
 * <p>框架默认 {@code WeixinStateStore.inMemory()} 把 cursor / lease / inbox / 对端 context token
 * 全存进程内，重启即丢、且跨实例不共享（单消费者保障仅限单 JVM）。本实现把这些状态落到平台全局
 * Redis（连接参数走 {@code spring.data.redis.*}，复用全局 {@code RedisConnectionFactory}，不私建连接），
 * 使重启可恢复消费位点与租约、并支撑多实例共享同一 iLink 账号的消费状态。</p>
 *
 * <p>状态机语义严格对齐框架 {@code InMemoryWeixinStateStore}：lease 以 {@code (holderId, generation)}
 * 单调版本号 + 到期时间戳做 fence；inbox 以接收顺序保序、按 messageId 去重；claim 带 claimId + 到期时间；
 * 完成 / 放弃清空 payload 与 claimId，失败仅释放 claim 以便重试。所有可变操作加 {@code synchronized}
 * （与框架 InMemory 同构，单实例语义；跨实例互斥由 Redis 共享状态 + lease holderId 提供基础保障）。</p>
 *
 * <p>仅当 {@code yunxi.im.weixin.state-store=redis} 时注册为本 Bean；其余情况 ImChannelGatewayConfig
 * 回退到框架 {@code WeixinStateStore.inMemory()}。语义验证见 RedisWeixinStateStoreIntegrationTest
 * （需共享 Redis，默认 @Disabled 不进 CI）。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.im.weixin.state-store", havingValue = "redis")
public class RedisWeixinStateStore implements WeixinStateStore {

    private static final long RETENTION_MS = Duration.ofDays(7).toMillis();
    private static final String PREFIX = "yunxi:weixin:";
    private static final String CURSOR = ":cursor";
    private static final String LEASE = ":lease";
    private static final String CONTEXTS = ":contexts";
    private static final String INBOX_ORDER = ":inbox:order";
    private static final String INBOX_MSG = ":inbox:msg";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 由 Spring 注入平台全局 RedisConnectionFactory（spring.data.redis.* 提供）。 */
    public RedisWeixinStateStore(RedisConnectionFactory connectionFactory) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.mapper = Json.objectMapper();
        this.clock = Clock.systemUTC();
    }

    /** 测试用构造器：直接注入已配置的 StringRedisTemplate。 */
    RedisWeixinStateStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.mapper = Json.objectMapper();
        this.clock = Clock.systemUTC();
    }

    private String cursorKey(String a) {
        return PREFIX + a + CURSOR;
    }

    private String leaseKey(String a) {
        return PREFIX + a + LEASE;
    }

    private String ctxKey(String a) {
        return PREFIX + a + CONTEXTS;
    }

    private String orderKey(String a) {
        return PREFIX + a + INBOX_ORDER;
    }

    private String msgKey(String a) {
        return PREFIX + a + INBOX_MSG;
    }

    private LeaseState readLease(String a) {
        String json = redis.opsForValue().get(leaseKey(a));
        return json == null ? null : readJson(json, LeaseState.class);
    }

    private void writeLease(String a, LeaseState ls) {
        redis.opsForValue().set(leaseKey(a), writeJson(ls));
    }

    private MessageState readMsg(String a, String mid) {
        Object v = redis.opsForHash().get(msgKey(a), mid);
        return v == null ? null : readJson((String) v, MessageState.class);
    }

    private void writeMsg(String a, String mid, MessageState ms) {
        redis.opsForHash().put(msgKey(a), mid, writeJson(ms));
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("微信状态反序列化失败: " + json, e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("微信状态序列化失败: " + o, e);
        }
    }

    @Override
    public synchronized String loadCursor(String accountId) {
        String c = redis.opsForValue().get(cursorKey(accountId));
        return c == null ? "" : c;
    }

    @Override
    public synchronized String loadContextToken(String accountId, String peerId) {
        Object t = redis.opsForHash().get(ctxKey(accountId), peerId);
        return t == null ? null : (String) t;
    }

    @Override
    public synchronized boolean saveContextToken(
            String accountId, WeixinLease lease, String peerId, String token) {
        if (!isLeaseCurrent(accountId, lease)) {
            return false;
        }
        if (peerId != null && token != null && !token.isBlank()) {
            redis.opsForHash().put(ctxKey(accountId), peerId, token);
        }
        return true;
    }

    @Override
    public synchronized Optional<WeixinLease> acquireLease(
            String accountId, String holderId, long leaseMs) {
        if (holderId == null || holderId.isBlank() || leaseMs <= 0) {
            throw new IllegalArgumentException("holderId 与正 leaseMs 必填");
        }
        long now = clock.millis();
        LeaseState ls = readLease(accountId);
        if (ls != null && ls.expiresAt > now) {
            if (!ls.holderId.equals(holderId)) {
                return Optional.empty();
            }
            // 同 holder 续用：generation 不变，仅续期
        } else {
            long gen = ls == null ? 0 : ls.generation;
            ls = new LeaseState(holderId, gen + 1, now);
        }
        ls.expiresAt = now + leaseMs;
        writeLease(accountId, ls);
        return Optional.of(new WeixinLease(ls.holderId, ls.generation));
    }

    @Override
    public synchronized boolean renewLease(String accountId, WeixinLease lease, long leaseMs) {
        if (leaseMs <= 0) {
            throw new IllegalArgumentException("leaseMs 必须为正");
        }
        if (!isLeaseCurrent(accountId, lease)) {
            return false;
        }
        LeaseState ls = readLease(accountId);
        ls.expiresAt = clock.millis() + leaseMs;
        writeLease(accountId, ls);
        return true;
    }

    @Override
    public synchronized boolean isLeaseCurrent(String accountId, WeixinLease lease) {
        LeaseState ls = readLease(accountId);
        return ls != null
                && lease != null
                && lease.holderId().equals(ls.holderId)
                && lease.generation() == ls.generation
                && ls.expiresAt > clock.millis();
    }

    @Override
    public synchronized void releaseLease(String accountId, WeixinLease lease) {
        LeaseState ls = readLease(accountId);
        if (ls != null
                && lease != null
                && lease.holderId().equals(ls.holderId)
                && lease.generation() == ls.generation) {
            ls.expiresAt = 0;
            writeLease(accountId, ls);
        }
    }

    @Override
    public synchronized boolean acceptBatch(
            String accountId, WeixinLease lease, String nextCursor,
            List<WeixinInboxMessage> messages) {
        if (!isLeaseCurrent(accountId, lease)) {
            return false;
        }
        for (WeixinInboxMessage message : messages) {
            if (!redis.opsForHash().hasKey(msgKey(accountId), message.messageId())) {
                writeMsg(accountId, message.messageId(), new MessageState(message.payload()));
                redis.opsForList().rightPush(orderKey(accountId), message.messageId());
            }
        }
        if (nextCursor != null && !nextCursor.isBlank()) {
            redis.opsForValue().set(cursorKey(accountId), nextCursor);
        }
        prune(accountId);
        return true;
    }

    @Override
    public synchronized List<WeixinInboxClaim> claimMessages(
            String accountId, WeixinLease lease, int limit, long claimMs) {
        if (limit <= 0 || claimMs <= 0) {
            throw new IllegalArgumentException("claim 边界须为正");
        }
        if (!isLeaseCurrent(accountId, lease)) {
            return List.of();
        }
        List<String> order = redis.opsForList().range(orderKey(accountId), 0, -1);
        if (order == null || order.isEmpty()) {
            return List.of();
        }
        List<WeixinInboxClaim> claims = new ArrayList<>();
        for (String mid : order) {
            MessageState m = readMsg(accountId, mid);
            if (m == null || m.completed || m.abandoned) {
                continue;
            }
            WeixinLease mLease = m.lease();
            if (mLease != null
                    && mLease.holderId().equals(lease.holderId())
                    && mLease.generation() == lease.generation()
                    && m.claimUntil > clock.millis()) {
                break; // 当前 lease 已 claim 且未过期：保持接收顺序，不再领新消息
            }
            m.leaseHolderId = lease.holderId();
            m.leaseGeneration = lease.generation();
            m.claimId = UUID.randomUUID().toString();
            m.claimUntil = clock.millis() + claimMs;
            writeMsg(accountId, mid, m);
            claims.add(new WeixinInboxClaim(mid, m.payload, m.claimId));
            if (claims.size() >= limit) {
                break;
            }
        }
        return List.copyOf(claims);
    }

    @Override
    public synchronized boolean completeMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        MessageState m = validClaim(accountId, lease, claim);
        if (m == null) {
            return false;
        }
        m.completed = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        writeMsg(accountId, claim.messageId(), m);
        return true;
    }

    @Override
    public synchronized boolean failMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        MessageState m = validClaim(accountId, lease, claim);
        if (m == null) {
            return false;
        }
        m.claimUntil = 0;
        m.claimId = null;
        writeMsg(accountId, claim.messageId(), m);
        return true;
    }

    @Override
    public synchronized boolean abandonMessage(
            String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        MessageState m = validClaim(accountId, lease, claim);
        if (m == null) {
            return false;
        }
        m.abandoned = true;
        m.completedAt = clock.millis();
        m.payload = null;
        m.claimId = null;
        writeMsg(accountId, claim.messageId(), m);
        return true;
    }

    @Override
    public synchronized void removeAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 必填");
        }
        redis.delete(List.of(
                cursorKey(accountId),
                leaseKey(accountId),
                ctxKey(accountId),
                orderKey(accountId),
                msgKey(accountId)));
    }

    /** 清当前账号过期 tombstone（Redis 持久化无全局 account map，不做跨账号空账号清理）。 */
    private void prune(String accountId) {
        List<String> order = redis.opsForList().range(orderKey(accountId), 0, -1);
        if (order == null) {
            return;
        }
        long now = clock.millis();
        for (String mid : order) {
            MessageState m = readMsg(accountId, mid);
            if (m != null
                    && (m.completed || m.abandoned)
                    && m.completedAt < now - RETENTION_MS) {
                redis.opsForHash().delete(msgKey(accountId), mid);
                redis.opsForList().remove(orderKey(accountId), 1, mid);
            }
        }
    }

    private MessageState validClaim(String accountId, WeixinLease lease, WeixinInboxClaim claim) {
        if (!isLeaseCurrent(accountId, lease)) {
            return null;
        }
        MessageState m = readMsg(accountId, claim.messageId());
        return m != null
                        && !m.completed
                        && lease.equals(m.lease())
                        && claim.claimId().equals(m.claimId)
                        && m.claimUntil > clock.millis()
                ? m
                : null;
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class LeaseState {
        String holderId;
        long generation;
        long expiresAt;

        LeaseState() {
        }

        LeaseState(String holderId, long generation, long expiresAt) {
            this.holderId = holderId;
            this.generation = generation;
            this.expiresAt = expiresAt;
        }
    }

    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    private static final class MessageState {
        String payload;
        String leaseHolderId;
        long leaseGeneration;
        String claimId;
        long claimUntil;
        boolean completed;
        boolean abandoned;
        long completedAt;

        MessageState() {
        }

        MessageState(String payload) {
            this.payload = payload;
        }

        WeixinLease lease() {
            return leaseHolderId == null ? null : new WeixinLease(leaseHolderId, leaseGeneration);
        }
    }
}
