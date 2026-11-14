package io.yunxi.app.shard;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import io.yunxi.ingress.ShardForwarder;

/**
 * 跨实例转发路由器：四协议 ingress 入口对 non-owned {@code agentId} 经 HTTP 转发至 owner 实例
 * {@code baseUrl}（取自 Nacos 实例 {@code baseUrl} metadata，缺失推导 {@code http://{ip}:{port}}）；
 * owner == 本实例则本地处理。实现 {@link ShardForwarder} SPI（入口织面仅依赖该接口，避免模块环）。
 *
 * <p>fail-open：Nacos 不可达 / 实例列表空 / 无 owner 信息 / 转发异常 → 返回 {@code null}，
 * 由调用方本地兜底构建处理（不静默丢失请求、不 5xx）。</p>
 */
@Component
@ConditionalOnProperty(name = "yunxi.shard.enabled", havingValue = "true")
public class AgentForwardingRouter implements ShardForwarder {

    private final AgentShardResolver resolver;
    private final NamingService namingService;
    private final String serviceName;
    private final RestTemplate forwardingClient;

    public AgentForwardingRouter(AgentShardResolver resolver,
            NamingService namingService,
            @Value("${yunxi.shard.service-name:yunxi-runtime}") String serviceName,
            RestTemplate forwardingClient) {
        this.resolver = resolver;
        this.namingService = namingService;
        this.serviceName = serviceName;
        this.forwardingClient = forwardingClient;
    }

    /**
     * 若 {@code agentId} 非本实例拥有，转发至 owner {@code baseUrl} 并返回 owner 响应；否则返回 {@code null}
     * （调用方本地处理，含 fail-open 兜底）。
     */
    public ResponseEntity<String> forwardIfNeeded(String agentId, String path,
            HttpMethod method, String body, HttpHeaders headers) {
        if (resolver.owns(agentId)) {
            return null;
        }
        String ownerKey = resolver.resolveOwnerInstanceKey(agentId);
        if (ownerKey == null) {
            return null; // 无 owner 信息，fail-open 本地兜底
        }
        String baseUrl = resolveOwnerBaseUrl(ownerKey);
        if (baseUrl == null) {
            return null; // owner 不可达 / 未注册，fail-open 本地兜底
        }
        try {
            return forwardingClient.exchange(
                    baseUrl + path, method, new HttpEntity<>(body, headers), String.class);
        } catch (Exception e) {
            return null; // 转发异常，fail-open 本地兜底（不 5xx）
        }
    }

    /**
     * 流式（SSE）转发到给定 {@code OutputStream}：若 {@code agentId} 非本实例拥有，经 {@code forwardingClient} 抽取 owner 的
     * SSE 原始字节，逐块写入 {@code out}（raw 透传，不做 {@code data:} 二次封装，避免破坏 SSE 帧），返回 {@code true}；
     * 否则返回 {@code false}（调用方本地兜底）。转发过程中发生 I/O / 传输异常则抛出 RuntimeException（此时 {@code out}
     * 可能已部分写入，调用方不应再本地兜底）。
     *
     * <p>与 {@link #forwardIfNeeded} 同源：复用 {@code resolver} 分片真相、{@code resolveOwnerBaseUrl} owner 寻址、
     * {@code forwardingClient}（仅超时配置不同，流式读取不加 read-timeout 截断——由 owner 流关闭驱动完成）。
     * {@code body} 经 {@link Supplier} 惰性获取，仅在实际转发时读取，避免 owned 路径提前消费请求体。</p>
     */
    public boolean forwardStreamTo(OutputStream out, String agentId, String path, HttpMethod method,
            Supplier<String> bodySupplier, HttpHeaders headers) {
        if (resolver.owns(agentId)) {
            return false;
        }
        String ownerKey = resolver.resolveOwnerInstanceKey(agentId);
        if (ownerKey == null) {
            return false; // 无 owner 信息，fail-open 本地兜底
        }
        String baseUrl = resolveOwnerBaseUrl(ownerKey);
        if (baseUrl == null) {
            return false; // owner 不可达 / 未注册，fail-open 本地兜底
        }
        try {
            String body = bodySupplier.get();
            forwardingClient.execute(baseUrl + path, method,
                    request -> {
                        if (body != null && !body.isEmpty()) {
                            request.getBody().write(body.getBytes(StandardCharsets.UTF_8));
                        }
                        copyRequestHeaders(headers, request);
                    },
                    response -> {
                        try (InputStream in = response.getBody()) {
                            pumpResponseToStream(in, out);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    });
        } catch (RuntimeException e) {
            throw e; // 已可能部分写入 out，抛出交由调用方不兜底
        }
        return true;
    }

    private void pumpResponseToStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private void copyRequestHeaders(HttpHeaders source, org.springframework.http.client.ClientHttpRequest request) {
        HttpHeaders target = request.getHeaders();
        if (source == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String name = entry.getKey();
            if ("content-length".equalsIgnoreCase(name) || "transfer-encoding".equalsIgnoreCase(name)) {
                continue; // 跳过重算的 hop-by-hop 头
            }
            for (String value : entry.getValue()) {
                target.add(name, value);
            }
        }
    }

    private String resolveOwnerBaseUrl(String ownerKey) {
        try {
            List<Instance> instances = namingService.getAllInstances(serviceName);
            if (instances == null) {
                return null;
            }
            for (Instance inst : instances) {
                if (ownerKey.equals(inst.getInstanceId())) {
                    Map<String, String> metadata = inst.getMetadata();
                    if (metadata != null) {
                        String baseUrl = metadata.get("baseUrl");
                        if (baseUrl != null && !baseUrl.isBlank()) {
                            return baseUrl;
                        }
                        // 缺失 baseUrl：推导 http://{ip}:{port}
                        if (inst.getIp() != null) {
                            return "http://" + inst.getIp() + ":" + inst.getPort();
                        }
                    }
                    return null;
                }
            }
            return null; // 未找到 owner 实例，fail-open 本地兜底
        } catch (NacosException e) {
            return null; // Nacos 不可达，fail-open 本地兜底
        }
    }
}
