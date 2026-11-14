package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * {@link AgentForwardingRouter} 在 non-owned agentId 时，经 Nacos 实例 metadata 取得 owner
 * {@code baseUrl} 并 HTTP 转发；owned / 无 owner 信息 / owner 不可达 均 fail-open 本地兜底（返回 null）。
 */
@ExtendWith(MockitoExtension.class)
class AgentForwardingRouterTest {

    @Mock
    private AgentShardResolver resolver;
    @Mock
    private NamingService namingService;
    @Mock
    private RestTemplate forwardingClient;

    private AgentForwardingRouter router;

    @BeforeEach
    void setUp() {
        router = new AgentForwardingRouter(resolver, namingService, "yunxi-runtime", forwardingClient);
    }

    @Test
    void nonOwnedForwardsToOwnerBaseUrlFromMetadata() throws Exception {
        when(resolver.owns("agent-x")).thenReturn(false);
        when(resolver.resolveOwnerInstanceKey("agent-x")).thenReturn("owner-2");

        Instance owner = new Instance();
        owner.setInstanceId("owner-2");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("baseUrl", "http://10.0.0.2:8080");
        owner.setMetadata(metadata);
        when(namingService.getAllInstances("yunxi-runtime")).thenReturn(List.of(owner));

        ResponseEntity<String> ownerResponse = ResponseEntity.ok("forwarded-body");
        when(forwardingClient.exchange(
                eq("http://10.0.0.2:8080/chat"), any(), any(), eq(String.class)))
                .thenReturn(ownerResponse);

        ResponseEntity<String> result = router.forwardIfNeeded(
                "agent-x", "/chat", HttpMethod.POST, "{}", new HttpHeaders());

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("forwarded-body", result.getBody());
    }

    @Test
    void ownedReturnsNullForLocalHandling() {
        when(resolver.owns("agent-x")).thenReturn(true);

        ResponseEntity<String> result = router.forwardIfNeeded(
                "agent-x", "/chat", HttpMethod.POST, "{}", new HttpHeaders());

        assertNull(result, "owned 应在本地处理，返回 null");
        verifyNoInteractions(forwardingClient, namingService);
    }

    @Test
    void noOwnerInfoFailOpenLocal() {
        when(resolver.resolveOwnerInstanceKey("agent-x")).thenReturn(null);

        ResponseEntity<String> result = router.forwardIfNeeded(
                "agent-x", "/chat", HttpMethod.POST, "{}", new HttpHeaders());

        assertNull(result, "无 owner 信息应 fail-open 本地兜底");
        verifyNoInteractions(forwardingClient, namingService);
    }

    @Test
    void ownerUnreachableFailOpenLocal() throws Exception {
        when(resolver.owns("agent-x")).thenReturn(false);
        when(resolver.resolveOwnerInstanceKey("agent-x")).thenReturn("owner-2");
        when(namingService.getAllInstances(any())).thenThrow(new NacosException());

        ResponseEntity<String> result = router.forwardIfNeeded(
                "agent-x", "/chat", HttpMethod.POST, "{}", new HttpHeaders());

        assertNull(result, "owner 不可达应 fail-open 本地兜底，不 5xx");
    }
}
