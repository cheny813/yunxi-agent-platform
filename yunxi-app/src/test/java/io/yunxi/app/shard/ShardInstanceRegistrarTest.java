package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ShardInstanceRegistrar} 契约测试：本实例向 Nacos 注册时携带 {@code baseUrl} metadata（供跨实例转发寻址），
 * 进程退出 {@code destroy} 触发 {@code deregisterInstance} 优雅下线。
 */
class ShardInstanceRegistrarTest {

    @Test
    void registersWithBaseUrlMetadata() throws Exception {
        NamingService naming = mock(NamingService.class);
        InstanceIdentity identity = mock(InstanceIdentity.class);
        when(identity.instanceKey()).thenReturn("inst-1");

        ShardInstanceRegistrar registrar =
                new ShardInstanceRegistrar(naming, identity, "yunxi-runtime", "http://inst-1:8080", "inst-1-ip", 8080);
        registrar.register();

        ArgumentCaptor<Instance> cap = ArgumentCaptor.forClass(Instance.class);
        verify(naming).registerInstance(eq("yunxi-runtime"), cap.capture());
        Instance inst = cap.getValue();
        assertEquals("inst-1-ip", inst.getIp());
        assertEquals(8080, inst.getPort());
        assertEquals("inst-1", inst.getInstanceId());
        assertEquals("http://inst-1:8080", inst.getMetadata().get("baseUrl"));
    }

    @Test
    void deregisterOnDestroy() throws Exception {
        NamingService naming = mock(NamingService.class);
        InstanceIdentity identity = mock(InstanceIdentity.class);
        when(identity.instanceKey()).thenReturn("inst-1");

        ShardInstanceRegistrar registrar =
                new ShardInstanceRegistrar(naming, identity, "yunxi-runtime", "http://inst-1:8080", "inst-1-ip", 8080);
        registrar.destroy();

        verify(naming).deregisterInstance("yunxi-runtime", "inst-1-ip", 8080);
    }
}
