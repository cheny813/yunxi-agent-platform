package io.yunxi.runtime.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 基于本机/远端 Ollama 的嵌入客户端（默认 bge-m3）。
 *
 * <p>通过 Ollama {@code /api/embeddings} 接口将文本映射为稠密向量；请求体与响应均走 JSON。
 * 这是 yunxi 产品平面的嵌入能力（框架 2.0.0 已移除 rag 包，向量/嵌入路径由产品平面自持）。
 */
@Component
@ConditionalOnProperty(name = "yunxi.rag.enabled", havingValue = "true", matchIfMissing = true)
public class OllamaEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);

    private final HttpClient http;
    private final String endpoint;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public OllamaEmbeddingClient(RagProperties properties) {
        RagProperties.Embedding e = properties.getEmbedding();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.endpoint = String.format("http://%s:%d/api/embeddings", e.getHost(), e.getPort());
        this.model = e.getModel();
        log.info("[RAG] Ollama 嵌入客户端初始化: endpoint={}, model={}", endpoint, model);
    }

    /** 测试与生产共用的构造器。 */
    public OllamaEmbeddingClient(HttpClient http, String baseUrl, String model) {
        this.http = http;
        this.endpoint = baseUrl.endsWith("/") ? baseUrl + "api/embeddings" : baseUrl + "/api/embeddings";
        this.model = model;
    }

    @Override
    public float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(
                    java.util.Map.of("model", model, "prompt", text));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException(
                        "Ollama 嵌入请求失败: " + resp.statusCode() + " " + resp.body());
            }
            return parse(resp.body());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Ollama 嵌入调用异常", ex);
        }
    }

    private float[] parse(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode arr = root.get("embedding");
            if (arr == null || !arr.isArray()) {
                throw new IllegalStateException("Ollama 响应缺少 embedding 数组: " + json);
            }
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            return v;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("解析 Ollama 嵌入响应失败: " + json, ex);
        }
    }
}
