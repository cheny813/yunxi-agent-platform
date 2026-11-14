package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.fabric.RedisDistributedRuntimeConfig;
import io.yunxi.fabric.RedisSandboxExecutionGuard;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 验证集群模式开关：{@code yunxi.cluster.mode=cluster} 时运行时平面装配 Redis 分布式后端，
 * 启用跨实例 CAS 与会话轮次跨副本互斥。装配纯配置驱动，无需改程序源码。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret",
        "yunxi.cluster.mode=cluster"
})
class ClusterModeSwitchTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void clusterModeRegistersRedisBackend() {
        DistributedRuntimeConfig config = context.getBean(DistributedRuntimeConfig.class);
        assertNotNull(config, "cluster 模式必须注册 DistributedRuntimeConfig");
        assertTrue(config instanceof RedisDistributedRuntimeConfig,
                "cluster 模式应装配 RedisDistributedRuntimeConfig 以启用跨实例 CAS，实际：" + config.getClass().getName());
    }

    @Test
    void clusterModeSandboxGuardIsRedisBacked() {
        DistributedRuntimeConfig config = context.getBean(DistributedRuntimeConfig.class);
        SandboxExecutionGuard guard = config.sandboxExecutionGuard();
        assertNotNull(guard, "cluster 模式必须注册 sandboxExecutionGuard");
        assertTrue(guard instanceof RedisSandboxExecutionGuard,
                "cluster 模式 sandboxExecutionGuard 必须是跨实例 Redis 锁，实际：" + guard.getClass().getName());
    }
}
