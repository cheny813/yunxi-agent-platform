package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 契约锁定：一致性哈希环在实例数 2→3 时仅约 1/3 归属迁移（而非全局重排）。
 * 误实现（如取模全局重算或随机 shuffle）会使迁移比例接近 1.0，被本测试拒绝。
 */
class ConsistentHashRingStabilityTest {

    @Test
    void ring_migrationBoundedOnInstanceChange() throws Exception {
        InstanceIdentity identity = mock(InstanceIdentity.class);
        when(identity.instanceKey()).thenReturn("inst-1");

        List<String> agents = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            agents.add("agent-" + i);
        }

        Instance i1 = mock(Instance.class);
        when(i1.getInstanceId()).thenReturn("inst-1");
        Instance i2 = mock(Instance.class);
        when(i2.getInstanceId()).thenReturn("inst-2");
        Instance i3 = mock(Instance.class);
        when(i3.getInstanceId()).thenReturn("inst-3");

        NamingService n2 = mock(NamingService.class);
        when(n2.getAllInstances("svc")).thenReturn(List.of(i1, i2));
        NacosBackedShardResolver r2 = new NacosBackedShardResolver(n2, identity, "svc", 200);

        NamingService n3 = mock(NamingService.class);
        when(n3.getAllInstances("svc")).thenReturn(List.of(i1, i2, i3));
        NacosBackedShardResolver r3 = new NacosBackedShardResolver(n3, identity, "svc", 200);

        int changed = 0;
        for (String a : agents) {
            if (r2.owns(a) != r3.owns(a)) {
                changed++;
            }
        }
        double ratio = (double) changed / agents.size();
        assertTrue(ratio > 0.1 && ratio < 0.6,
                "migration ratio should be bounded (~1/3 for 2->3 nodes), got " + ratio);
    }
}
