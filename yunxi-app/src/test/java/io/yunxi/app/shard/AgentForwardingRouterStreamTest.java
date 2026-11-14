package io.yunxi.app.shard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

/**
 * {@link AgentForwardingRouter#forwardStreamTo} 在 non-owned agentId 时，抽取 owner 的 SSE 原始字节、raw 透传写入给定
 * {@link OutputStream} 并返回 {@code true}；owned / 无 owner 信息返回 {@code false}（本地兜底）。body 经
 * {@link java.util.function.Supplier} 惰性获取，仅在实际转发时读取。
 */
@ExtendWith(MockitoExtension.class)
class AgentForwardingRouterStreamTest {

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
    @SuppressWarnings("unchecked")
    void nonOwnedPumpsOwnerSseIntoOutputStreamRaw() throws Exception {
        when(resolver.owns("agent-x")).thenReturn(false);
        when(resolver.resolveOwnerInstanceKey("agent-x")).thenReturn("owner-2");
        Instance owner = new Instance();
        owner.setInstanceId("owner-2");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("baseUrl", "http://10.0.0.2:8080");
        owner.setMetadata(metadata);
        when(namingService.getAllInstances("yunxi-runtime")).thenReturn(List.of(owner));

        String sse = "data: {\"type\":\"text\"}\n\ndata: {\"type\":\"done\"}\n\n";
        byte[] sseBytes = sse.getBytes(StandardCharsets.UTF_8);

        AtomicReference<String> capturedUrl = new AtomicReference<>();
        AtomicReference<String> consumedBody = new AtomicReference<>();
        when(forwardingClient.execute(any(String.class), eq(HttpMethod.POST), any(RequestCallback.class),
                any(ResponseExtractor.class))).thenAnswer(inv -> {
            capturedUrl.set(inv.getArgument(0));
            ResponseExtractor<Void> extractor = inv.getArgument(3);
            ClientHttpResponse response = mockSseResponse(sseBytes);
            extractor.extractData(response);
            return null;
        });

        CapturingOutputStream out = new CapturingOutputStream();
        boolean forwarded = router.forwardStreamTo(out, "agent-x", "/agui/run", HttpMethod.POST,
                () -> {
                    consumedBody.set("request-body");
                    return "request-body";
                }, new HttpHeaders());

        assertTrue(forwarded, "non-owned 应返回 true");
        assertEquals("http://10.0.0.2:8080/agui/run", capturedUrl.get());
        assertArrayEquals(sseBytes, out.toByteArray(), "SSE 应 raw 透传，逐字节一致，无 data: 二次封装");
        assertEquals("request-body", consumedBody.get(), "实际转发时应惰性读取 body");
    }

    @Test
    void ownedReturnsFalseLocal() {
        when(resolver.owns("agent-x")).thenReturn(true);

        CapturingOutputStream out = new CapturingOutputStream();
        boolean forwarded = router.forwardStreamTo(out, "agent-x", "/agui/run", HttpMethod.POST,
                () -> "body", new HttpHeaders());

        assertFalse(forwarded, "owned 应返回 false，本地处理且未写入 out");
        assertTrue(out.size() == 0, "owned 不应写入输出流");
        verifyNoInteractions(forwardingClient, namingService);
    }

    @Test
    void noOwnerInfoReturnsFalse() {
        when(resolver.resolveOwnerInstanceKey("agent-x")).thenReturn(null);

        CapturingOutputStream out = new CapturingOutputStream();
        boolean forwarded = router.forwardStreamTo(out, "agent-x", "/agui/run", HttpMethod.POST,
                () -> "body", new HttpHeaders());

        assertFalse(forwarded, "无 owner 信息应 fail-open 本地兜底，返回 false");
        verifyNoInteractions(forwardingClient, namingService);
    }

    private static ClientHttpResponse mockSseResponse(byte[] body) throws IOException {
        ClientHttpResponse response = org.mockito.Mockito.mock(ClientHttpResponse.class);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(body));
        return response;
    }

    /** 记录写入字节的测试输出流。 */
    static class CapturingOutputStream extends OutputStream {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            bos.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            bos.write(b, off, len);
        }

        byte[] toByteArray() {
            return bos.toByteArray();
        }

        int size() {
            return bos.size();
        }
    }
}
