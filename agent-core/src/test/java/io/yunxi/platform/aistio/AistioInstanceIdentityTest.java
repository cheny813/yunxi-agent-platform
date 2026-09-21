package io.yunxi.platform.aistio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import io.yunxi.platform.aistio.AistioGrpcConnector.InstanceKeyConflictException;

/**
 * 实例身份约束回归测试（instanceKey 稳定唯一 + 并发写入拒绝启动）。
 *
 * <p>三条纪律：instanceKey 必须稳定且全局唯一（随机值会让冲突信号永远观测不到）；
 * 判据必须是「注册增量」而非「generation&gt;1」（本进程自身注册也会 +1，后者每重启必误报）；
 * 无基线时放行（探测失败不拦正常启动）。
 */
class AistioInstanceIdentityTest {

    @Test
    void derivesStableInstanceKeyFromAppHostPort() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "yunxi-agent-platform");
        env.setProperty("server.port", "40001");
        AistioIntegrationProperties props = new AistioIntegrationProperties();

        String key = new AistioGrpcConnector(props, null, null, env, null).resolveInstanceKey();

        assertTrue(key.startsWith("yunxi-agent-platform-"), "须以应用名开头: " + key);
        assertTrue(key.endsWith("-40001"), "须以端口结尾: " + key);
        // 稳定性：同一环境两次推导必须完全一致（随机 UUID 会破坏这一点）
        assertEquals(key, new AistioGrpcConnector(props, null, null, env, null).resolveInstanceKey());
    }

    @Test
    void explicitInstanceKeyWins() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "yunxi-agent-platform");
        env.setProperty("server.port", "40001");
        AistioIntegrationProperties props = new AistioIntegrationProperties();
        props.setInstanceKey("  yunxi-prod-node-7  ");

        assertEquals("yunxi-prod-node-7",
                new AistioGrpcConnector(props, null, null, env, null).resolveInstanceKey());
    }

    @Test
    void fallsBackToDefaultsWithoutEnvironment() {
        AistioIntegrationProperties props = new AistioIntegrationProperties();

        String key = new AistioGrpcConnector(props, null, null, null, null).resolveInstanceKey();

        assertTrue(key.startsWith("yunxi-agent-platform-"), key);
        assertTrue(key.endsWith("-8080"), key);
    }

    @Test
    void acceptsDeltaOfOne() {
        // 注册前 26、注册后 27：增量恰为 1，是本进程自身注册的 +1，不冲突
        AistioGrpcConnector.requireFreshGeneration(27L, "agent-a", "yunxi-1", 26L);
        // 首次注册：基线 0 → 1
        AistioGrpcConnector.requireFreshGeneration(1L, "agent-a", "yunxi-1", 0L);
    }

    @Test
    void acceptsHighGenerationWhenBaselineMovedByOne() {
        // 回归本次线上问题：generation 被历次重启顶到 27，只要增量是 1 就必须放行
        AistioGrpcConnector.requireFreshGeneration(27L, "agent-a", "yunxi-1", 26L);
        AistioGrpcConnector.requireFreshGeneration(100L, "agent-a", "yunxi-1", 99L);
    }

    @Test
    void acceptsWhenBaselineMissing() {
        // 首次注册或控制面不可达：无基线可依，放行（激进拦截代价高于漏判）
        AistioGrpcConnector.requireFreshGeneration(27L, "agent-a", "yunxi-1", null);
    }

    @Test
    void refusesWhenAnotherProcessWroteConcurrently() {
        // 注册前 26、注册后 28：期间有另一进程也写入了同一行，才算真冲突
        InstanceKeyConflictException e = assertThrows(InstanceKeyConflictException.class,
                () -> AistioGrpcConnector.requireFreshGeneration(28L, "agent-a", "yunxi-1", 26L));

        assertTrue(e.getMessage().contains("instanceKey 冲突"));
        assertTrue(e.getMessage().contains("agent-a"));
        assertTrue(e.getMessage().contains("yunxi-1"));
        assertTrue(e.getMessage().contains("delta=2"),
                "报错必须给出可执行的排查信息（增量多少，而非仅 generation 值）");
    }
}
