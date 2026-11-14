package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * 分片协作语义单测：用 mock 模拟 N=2（双实例一致性哈希环，无 Nacos 在线依赖），
 * 锁定分片路由在 N=2 下的核心契约：
 * ① 每个 agent 恰好一个实例 owns（构建总数 = 全量、无重复、无遗漏）；
 * ② non-owned 请求在 remote 视角经转发抵达 owner（返回 owner body），owner 视角本地处理（null）。
 *
 * <p>真·多实例 e2e（任务 15，需 Nacos 在线 N≥2 部署）属环境门控，另走，不在本单测覆盖。</p>
 */
class ShardN2CooperationTest {

    private static final String SERVICE = "yunxi-runtime";

    private NacosBackedShardResolver resolverFor(String instanceKey, Instance... instances) throws Exception {
        NamingService naming = mock(NamingService.class);
        when(naming.getAllInstances(SERVICE)).thenReturn(List.of(instances));
        InstanceIdentity identity = mock(InstanceIdentity.class);
        when(identity.instanceKey()).thenReturn(instanceKey);
        return new NacosBackedShardResolver(naming, identity, SERVICE, 160);
    }

    @Test
    void n2PartitionIsCompleteAndDisjoint() throws Exception {
        Instance i1 = mock(Instance.class);
        when(i1.getInstanceId()).thenReturn("inst-1");
        Instance i2 = mock(Instance.class);
        when(i2.getInstanceId()).thenReturn("inst-2");

        NacosBackedShardResolver r1 = resolverFor("inst-1", i1, i2);
        NacosBackedShardResolver r2 = resolverFor("inst-2", i1, i2);

        int agents = 5000;
        int ownedBy1 = 0;
        int bothOwn = 0;
        int neither = 0;
        for (int i = 0; i < agents; i++) {
            String a = "agent-" + i;
            boolean o1 = r1.owns(a);
            boolean o2 = r2.owns(a);
            if (o1) {
                ownedBy1++;
            }
            if (o1 && o2) {
                bothOwn++;
            }
            if (!o1 && !o2) {
                neither++;
            }
        }
        // 完备且互斥：每 agent 恰好一个实例 owns → 两实例构建集合并集 = 全集、交集为空
        assertEquals(0, bothOwn, "no agent should be owned by both instances");
        assertEquals(0, neither, "every agent must be owned by exactly one instance (total builds = full set)");
        // 一致性哈希环对 N=2 应接近 50/50（非取模全局重排）
        double ratio = (double) ownedBy1 / agents;
        assertTrue(ratio > 0.35 && ratio < 0.65, "partition should be roughly balanced, got " + ratio);
    }

    @Test
    void nonOwnedForwardsToOwner_ownedHandledLocally() throws Exception {
        Instance i1 = mock(Instance.class);
        when(i1.getInstanceId()).thenReturn("inst-1");
        Instance i2 = mock(Instance.class);
        when(i2.getInstanceId()).thenReturn("inst-2");

        NacosBackedShardResolver r1 = resolverFor("inst-1", i1, i2);
        NacosBackedShardResolver r2 = resolverFor("inst-2", i1, i2);

        // 找一个被 inst-1 owns、故 inst-2 不 owns 的 agent（一致性哈希互斥保证）
        String a = findOwnedBy(r1, "inst-1");
        assertTrue(r1.owns(a));
        assertFalse(r2.owns(a));
        assertEquals("inst-1", r2.resolveOwnerInstanceKey(a));

        // inst-2 视角：non-owned → 转发至 owner(inst-1) baseUrl，返回 owner body
        NamingService naming = mock(NamingService.class);
        Instance ownerInst = new Instance();
        ownerInst.setInstanceId("inst-1");
        Map<String, String> meta = new HashMap<>();
        meta.put("baseUrl", "http://inst-1:8080");
        ownerInst.setMetadata(meta);
        when(naming.getAllInstances(SERVICE)).thenReturn(List.of(ownerInst, i2));
        RestTemplate rest = mock(RestTemplate.class);
        ResponseEntity<String> ownerResp = ResponseEntity.ok("owner-body");
        when(rest.exchange(eq("http://inst-1:8080/chat"), any(), any(), eq(String.class))).thenReturn(ownerResp);

        AgentForwardingRouter router2 = new AgentForwardingRouter(r2, naming, SERVICE, rest);
        ResponseEntity<String> forwarded = router2.forwardIfNeeded(a, "/chat", HttpMethod.POST, "{}", new HttpHeaders());
        assertEquals(HttpStatus.OK, forwarded.getStatusCode());
        assertEquals("owner-body", forwarded.getBody());

        // inst-1 视角：owned → 本地处理（返回 null，不转发）
        AgentForwardingRouter router1 = new AgentForwardingRouter(r1, naming, SERVICE, rest);
        ResponseEntity<String> local = router1.forwardIfNeeded(a, "/chat", HttpMethod.POST, "{}", new HttpHeaders());
        assertNull(local);
    }

    private String findOwnedBy(NacosBackedShardResolver resolver, String target) {
        for (int i = 0; i < 100_000; i++) {
            String candidate = "agent-" + i;
            if (target.equals(resolver.resolveOwnerInstanceKey(candidate))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no agent owned by " + target);
    }
}
