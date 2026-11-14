package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link NacosBackedShardResolver}：
 * 断言一致性哈希环归属：本实例仅拥有落在自身 instanceKey 的 agent，non-owned 归属其他实例。
 */
class NacosBackedShardResolverTest {

    @Test
    void owns_onlyForLocalInstance() throws Exception {
        NamingService naming = mock(NamingService.class);
        Instance inst1 = mock(Instance.class);
        when(inst1.getInstanceId()).thenReturn("inst-1");
        Instance inst2 = mock(Instance.class);
        when(inst2.getInstanceId()).thenReturn("inst-2");
        when(naming.getAllInstances("yunxi-runtime")).thenReturn(List.of(inst1, inst2));

        InstanceIdentity identity = mock(InstanceIdentity.class);
        when(identity.instanceKey()).thenReturn("inst-1");

        NacosBackedShardResolver resolver =
                new NacosBackedShardResolver(naming, identity, "yunxi-runtime", 160);

        String owned = findAgentOwnedBy(resolver, "inst-1");
        assertTrue(resolver.owns(owned));
        assertEquals("inst-1", resolver.resolveOwnerInstanceKey(owned));

        String remote = findAgentOwnedBy(resolver, "inst-2");
        assertFalse(resolver.owns(remote));
        assertEquals("inst-2", resolver.resolveOwnerInstanceKey(remote));
    }

    private String findAgentOwnedBy(NacosBackedShardResolver resolver, String target) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = "agent-" + i;
            if (target.equals(resolver.resolveOwnerInstanceKey(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no agent owned by " + target);
    }
}
