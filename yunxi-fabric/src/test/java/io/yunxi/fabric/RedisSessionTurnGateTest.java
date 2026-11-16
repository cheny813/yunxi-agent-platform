package io.yunxi.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

import io.agentscope.harness.agent.gateway.TurnBusyException;
import io.agentscope.harness.agent.gateway.TurnLease;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * {@link RedisSessionTurnGate} 跨实例轮次闸门：NX+PX 抢占、CAS 释放（避免误删他人锁）、
 * 锁存在性探测、以及超时拒绝并发入站。使用 mock StringRedisTemplate 纯单元验证，不依赖真实 Redis。
 */
class RedisSessionTurnGateTest {

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redis() {
        StringRedisTemplate tpl = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(tpl.opsForValue()).thenReturn(ops);
        return tpl;
    }

    @Test
    void acquireReturnsLeaseWhenLockFree() throws InterruptedException, TurnBusyException {
        StringRedisTemplate redis = redis();
        ValueOperations<String, String> ops = redis.opsForValue();
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        RedisSessionTurnGate gate = new RedisSessionTurnGate(redis, "p:");

        TurnLease lease = gate.acquire("k");

        assertNotNull(lease);
        verify(ops).setIfAbsent(eq("p:turn:k"), anyString(), any(Duration.class));
        gate.shutdown();
    }

    @Test
    void isRunningReflectsLockPresence() {
        StringRedisTemplate redis = redis();
        when(redis.hasKey("p:turn:k")).thenReturn(true).thenReturn(false);
        RedisSessionTurnGate gate = new RedisSessionTurnGate(redis, "p:");

        assertTrue(gate.isRunning("k"));
        assertFalse(gate.isRunning("k"));
        gate.shutdown();
    }

    @Test
    void closeReleasesLeaseThroughSingleScriptCall() throws InterruptedException, TurnBusyException {
        StringRedisTemplate redis = redis();
        ValueOperations<String, String> ops = redis.opsForValue();
        ArgumentCaptor<String> tokenCap = ArgumentCaptor.forClass(String.class);
        when(ops.setIfAbsent(eq("p:turn:k"), tokenCap.capture(), any(Duration.class))).thenReturn(true);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisSessionTurnGate gate = new RedisSessionTurnGate(redis, "p:");

        TurnLease lease = gate.acquire("k"); // 捕获本 lease 实际持有的随机 token
        lease.close();

        // 释放必须是一次原子脚本调用（脚本内比对 token 后再删），不能退化为先读后删。
        verify(redis).execute(any(RedisScript.class), eq(List.of("p:turn:k")), eq(tokenCap.getValue()));
        verify(redis, never()).delete(anyString());
        verify(ops, never()).get(anyString());
        gate.shutdown();
    }

    @Test
    void acquireThrowsTurnBusyAfterTimeout() {
        StringRedisTemplate redis = redis();
        ValueOperations<String, String> ops = redis.opsForValue();
        // 永远抢占失败，触发超时拒绝并发入站。
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        RedisSessionTurnGate gate = new RedisSessionTurnGate(redis, "p:");

        assertThrows(TurnBusyException.class, () -> gate.acquire("k"));
        gate.shutdown();
    }
}
