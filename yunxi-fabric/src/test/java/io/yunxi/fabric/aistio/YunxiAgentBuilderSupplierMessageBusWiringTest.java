package io.yunxi.fabric.aistio;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.bus.MessageBus;
import io.yunxi.capability.McpDirectory;
import io.yunxi.runtime.DistributedRuntimeConfig;
import io.yunxi.runtime.MuseToolRegistrar;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 装配路径测试：验证 {@link YunxiAgentBuilderSupplier} 在 cluster / standalone 两种拓扑下对跨实例
 * 事件总线 {@link MessageBus} 的接线行为，不依赖 Spring 容器或真实 Redis。
 *
 * <p>框架 {@code HarnessAgent.Builder#messageBus} 字段为包级可见，本测试经反射读取以断言接线结果；
 * 同理 {@code YunxiAgentBuilderSupplier#messageBusProvider} 经反射注入（模拟 Spring 容器对
 * {@code @Autowired ObjectProvider} 字段的装配），因为直接 {@code new} 构造时该字段为 null。</p>
 */
class YunxiAgentBuilderSupplierMessageBusWiringTest {

    private static Object readBuilderMessageBus(HarnessAgent.Builder builder) throws Exception {
        Field field = HarnessAgent.Builder.class.getDeclaredField("messageBus");
        field.setAccessible(true);
        return field.get(builder);
    }

    private static YunxiAgentBuilderSupplier supplierWith(ObjectProvider<MessageBus> provider) throws Exception {
        DistributedRuntimeConfig drc = mock(DistributedRuntimeConfig.class);
        McpDirectory mcp = mock(McpDirectory.class);
        when(mcp.all()).thenReturn(List.of());
        YunxiAgentBuilderSupplier supplier = new YunxiAgentBuilderSupplier(drc, mcp, List.of(), List.of());
        Field field = YunxiAgentBuilderSupplier.class.getDeclaredField("messageBusProvider");
        field.setAccessible(true);
        field.set(supplier, provider);
        return supplier;
    }

    @Test
    void wiresMessageBusWhenClusterBeanPresent() throws Exception {
        MessageBus bus = mock(MessageBus.class);
        ObjectProvider<MessageBus> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bus);

        HarnessAgent.Builder builder = supplierWith(provider).get();

        assertSame(bus, readBuilderMessageBus(builder),
                "cluster 模式：builder.messageBus 必须接入 RedisMessageBus");
    }

    @Test
    void skipsMessageBusWhenBeanAbsentFailOpen() throws Exception {
        ObjectProvider<MessageBus> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        HarnessAgent.Builder builder = supplierWith(provider).get();

        assertNull(readBuilderMessageBus(builder),
                "standalone / 总线 bean 缺失：builder.messageBus 必须保持 null（fail-open，不阻断布建）");
    }

    @Test
    void skipsMessageBusWhenProviderNull() throws Exception {
        // 直接 new 构造且未注入 ObjectProvider 字段时的兜底（集成测试路径）。
        DistributedRuntimeConfig drc = mock(DistributedRuntimeConfig.class);
        McpDirectory mcp = mock(McpDirectory.class);
        when(mcp.all()).thenReturn(List.of());
        YunxiAgentBuilderSupplier supplier = new YunxiAgentBuilderSupplier(drc, mcp, List.of(), List.of());

        HarnessAgent.Builder builder = supplier.get();

        assertNull(readBuilderMessageBus(builder),
                "未注入 ObjectProvider 时（new 构造）必须 fail-open 跳过");
    }
}
