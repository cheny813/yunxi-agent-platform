package io.yunxi.platform.aistio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * aistio 控制面 HTTP 客户端统一封装（适配层）。
 *
 * <p>所有 aistio 控制面地址、路径、鉴权头、超时与重试策略集中在本类，<b>凭据一律作为方法参数传入</b>，
 * 不固化为共享状态——这是禁用协议渗入产品平面的落点。
 * 此前控制面 HTTP 存在三套客户端（框架内网令牌 {@code ControlPlaneHttpClient} / {@code RestTemplate}+PAT /
 * JDK {@code HttpClient}+secret），各自硬编码 URL 与重试策略，现已收敛为本类的两个入口：
 * {@link #pushOrchestrationDefinition(String, String, String, Object, String)}（PAT）与
 * {@link #triggerAutomation(String, String, String, String, java.util.Map)}（webhook secret）。</p>
 *
 * <p>注：框架 {@code ControlPlaneHttpClient}（internal-token）承载实例自注册与心跳，由 AgentScope-Java 框架
 * 内部持有，属于运行时边界之外的注册链路，不纳入本类（框架修改铁律：增强不修改框架本体）。本类只收口
 * yunxi 产品平面自有的两路控制面调用，超时与重试统一，避免三套客户端各写各的。</p>
 */
public class AistioControlPlaneClient {

    private static final Logger log = LoggerFactory.getLogger(AistioControlPlaneClient.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_PUSH_ATTEMPTS = 3;
    private static final long PUSH_RETRY_DELAY_MS = 10_000L;

    private final String controlPlaneUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public AistioControlPlaneClient(String controlPlaneUrl, ObjectMapper mapper) {
        this.controlPlaneUrl = trimSlash(controlPlaneUrl);
        this.http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.mapper = mapper;
    }

    /**
     * 以控制台 PAT（{@code Authorization: Bearer}）把编排 definition 推送至控制面。
     * 凭据 pat 仅作参数传入，不在实例内留存。
     *
     * @throws IllegalStateException 重试 {@value #MAX_PUSH_ATTEMPTS} 次仍失败（调用方应降级为 WARN，不阻塞启动）
     */
    public void pushOrchestrationDefinition(String tenant, String namespace, String name,
            Object draftSpec, String pat) {
        String url = controlPlaneUrl + "/api/v1/orchestration-definitions";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant", tenant);
        body.put("namespace", namespace);
        body.put("name", name);
        body.put("draftSpec", draftSpec);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + pat)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("编排 definition 序列化失败: " + name, e);
        }
        for (int attempt = 1; attempt <= MAX_PUSH_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    log.info("已自动推送编排 definition 到 aistio 控制面: {} (name={})", url, name);
                    return;
                }
                log.warn("编排 definition 推送返回非成功状态码 {}: {} (name={})", resp.statusCode(), url, name);
            } catch (IOException e) {
                log.warn("编排 definition 推送失败（第 {} 次）: {} (name={})", attempt, e.getMessage(), name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("编排 definition 推送被中断（第 {} 次）: {} (name={})", attempt, e.getMessage(), name);
                return;
            }
            if (attempt < MAX_PUSH_ATTEMPTS) {
                try {
                    Thread.sleep(PUSH_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new IllegalStateException("编排 definition 推送在 " + MAX_PUSH_ATTEMPTS
                + " 次重试后仍失败，可稍后在 aistio 控制台手工导入: " + name);
    }

    /**
     * 以 webhook secret（{@code X-Automation-Secret}）触发 automation，返回原始响应。
     * 凭据 secret 与幂等键 idempotencyKey 仅作参数传入。
     */
    public HttpResponse<String> triggerAutomation(String automationId, String triggerId,
            String secret, String idempotencyKey, Map<String, Object> body) {
        String url = controlPlaneUrl + "/hooks/v1/automations/" + automationId + "/" + triggerId;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Automation-Secret", secret)
                    .header("Idempotency-Key", idempotencyKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("webhook 请求体序列化失败: " + automationId + "/" + triggerId, e);
        }
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException("webhook 请求 IO 失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webhook 请求被中断", e);
        }
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
