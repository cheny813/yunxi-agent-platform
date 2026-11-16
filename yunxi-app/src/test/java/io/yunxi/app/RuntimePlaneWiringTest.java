package io.yunxi.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yunxi.fabric.InMemoryDistributedRuntimeConfig;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.MuseToolRegistrar;
import io.yunxi.intelligence.muse.sandbox.DockerSandboxRunner;
import io.yunxi.intelligence.muse.sandbox.LocalSubprocessSandboxRunner;
import io.yunxi.intelligence.muse.sandbox.SandboxRunner;
import io.yunxi.fabric.RedisSandboxExecutionGuard;
import io.yunxi.runtime.rag.DocumentChunkStore;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.yunxi.runtime.rag.RagToolRegistrar;
import io.yunxi.app.shard.AgentShardResolver;
import io.yunxi.observability.MetricRegistry;
import io.yunxi.observability.MicrometerMetricRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 端到端验证：运行时平面（io.yunxi.runtime / io.yunxi.intelligence）经 {@code YunxiApplication} 的统一组件扫描后，
 * 各功能组件被注册并接入协议无关中间件链，且功能开关语义保持。
 *
 * <p>本测试启动完整应用上下文，是对「组件实际被加载、而非仅编译通过」的真实验证。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
class RuntimePlaneWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void metricRegistryIsBridgedToMicrometer() {
        MetricRegistry registry = context.getBean(MetricRegistry.class);
        assertTrue(registry instanceof MicrometerMetricRegistry,
                "生产装配的 MetricRegistry 应桥接到 Micrometer，而非进程内内存实现");

        io.micrometer.core.instrument.MeterRegistry meters =
                context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        registry.incrementCounter("yunxi.wiring.probe");
        assertEquals(1.0d, meters.get("yunxi.wiring.probe").counter().count(),
                "经 MetricRegistry 记录的度量应真实进入 Micrometer registry（可被导出）");

        assertTrue(context.getEnvironment()
                        .getProperty("management.endpoints.web.exposure.include", "")
                        .contains("metrics"),
                "配置片段应把只读 metrics 端点纳入暴露清单");
        assertEquals("false", context.getEnvironment().getProperty("management.otlp.metrics.export.enabled"),
                "OTLP 指标导出默认关闭，未部署 Collector 时无外发流量");
    }

    @Test
    void ragEnabledByDefaultRegistersStoreAndToolRegistrar() {
        assertTrue(context.getBeanNamesForType(DocumentChunkStore.class).length > 0,
                "RAG 默认启用，应注册 DocumentChunkStore 实现（共享存储后端）");
        assertTrue(context.getBeanNamesForType(RagToolRegistrar.class).length > 0,
                "RAG 默认启用，应注册 RagToolRegistrar");
    }

    @Test
    void ragEnabledByDefaultRegistersToolRegistrar() {
        assertTrue(context.getBeanNamesForType(RagToolRegistrar.class).length > 0,
                "RAG 默认启用，应注册 RagToolRegistrar");
    }

    @Test
    void museEnabledByDefaultRegistersToolRegistrar() {
        assertTrue(context.getBeanNamesForType(MuseToolRegistrar.class).length > 0,
                "MUSE 默认开启，应注册 MuseToolRegistrar 实现");
    }

    @Test
    void shardDisabledByDefaultRegistersAllOwnsResolver() {
        // 分片默认关闭（yunxi.shard.enabled 缺失 → matchIfMissing），应装配全量兜底解析器（owns 永真），
        // 保证 AgentProvisioner 始终注入一个 AgentShardResolver；NacosBackedShardResolver 不应在上下文内。
        assertTrue(context.getBeanNamesForType(AgentShardResolver.class).length == 1,
                "分片关闭时应恰好存在一个默认 AgentShardResolver");
        AgentShardResolver resolver = context.getBean(AgentShardResolver.class);
        assertTrue(resolver.owns("any-agent-id"), "默认解析器应 owns 全部 agent（fail-open 全量）");
    }

    @Test
    void standaloneModeRegistersInMemoryBackendByDefault() {
        // 缺省（未设 yunxi.cluster.mode）应等价于 standalone：装配内存兜底后端，
        // 保证无 Redis 的演示 / 开发环境零配置可启动；生产显式设 cluster 即切 Redis。
        DistributedRuntimeConfig config = context.getBean(DistributedRuntimeConfig.class);
        assertTrue(config instanceof InMemoryDistributedRuntimeConfig,
                "缺省/standalone 模式应装配 InMemoryDistributedRuntimeConfig（演示/开发零配置），实际：" + config.getClass().getName());
    }

    @Test
    void standaloneModeActivatesLocalSandboxByDefault() {
        // 缺省（未设 yunxi.cluster.mode）应等价于 standalone：装配本机子进程沙箱，
        // 保证无 docker 的开发 / 演示环境零配置可启动；生产设 cluster 即切 Docker 强隔离。
        SandboxRunner runner = context.getBean(SandboxRunner.class);
        assertTrue(runner instanceof LocalSubprocessSandboxRunner,
                "缺省/standalone 模式应装配 LocalSubprocessSandboxRunner（开发零配置），实际：" + runner.getClass().getName());
        assertTrue(!(runner instanceof DockerSandboxRunner),
                "缺省/standalone 模式不得默认启用 Docker 沙箱");
    }

    @Test
    void standaloneModeSandboxGuardIsInProcess() {
        // 缺省（未设 yunxi.cluster.mode）应等价于 standalone：沙箱守卫为进程内信号量，非 Redis 跨实例锁。
        DistributedRuntimeConfig config = context.getBean(DistributedRuntimeConfig.class);
        SandboxExecutionGuard guard = config.sandboxExecutionGuard();
        assertTrue(!(guard instanceof RedisSandboxExecutionGuard),
                "缺省/standalone 模式 sandboxExecutionGuard 应为进程内信号量（非 Redis），实际：" + guard.getClass().getName());
    }
}
