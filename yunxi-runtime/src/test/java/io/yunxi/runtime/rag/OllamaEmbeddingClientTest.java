package io.yunxi.runtime.rag;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OllamaEmbeddingClient 测试：验证请求体与响应解析。 */
class OllamaEmbeddingClientTest {

    @SuppressWarnings("unchecked")
    @Test
    void embed_postsToOllamaApiAndParsesVector() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn("{\"embedding\":[0.1,0.2,0.3,0.4]}");
        when(resp.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OllamaEmbeddingClient client =
                new OllamaEmbeddingClient(http, "http://localhost:11434", "bge-m3");

        float[] v = client.embed("hello");

        assertEquals(4, v.length);
        assertEquals(0.2f, v[1], 1e-6f);
    }

    @SuppressWarnings("unchecked")
    @Test
    void embed_requestTargetsApiEmbeddings() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> resp = mock(HttpResponse.class);
        when(resp.body()).thenReturn("{\"embedding\":[0.0,0.0]}");
        when(resp.statusCode()).thenReturn(200);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resp);

        OllamaEmbeddingClient client =
                new OllamaEmbeddingClient(http, "http://ollama:11434", "bge-m3");

        org.mockito.ArgumentCaptor<HttpRequest> captor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        client.embed("query");
        org.mockito.Mockito.verify(http).send(captor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals("http://ollama:11434/api/embeddings", captor.getValue().uri().toString());
    }
}
