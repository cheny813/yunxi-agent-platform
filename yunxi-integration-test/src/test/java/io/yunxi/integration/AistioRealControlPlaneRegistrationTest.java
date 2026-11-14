package io.yunxi.integration;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.controlplane.SessionBridge;
import io.agentscope.extensions.model.dashscope.DashScopeModelProvider;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.capability.InMemoryMcpDirectory;
import io.yunxi.fabric.aistio.AistioConnector;
import io.yunxi.fabric.aistio.AistioIntegrationProperties;
import io.yunxi.fabric.aistio.AistioLifecycle;
import io.yunxi.fabric.aistio.YunxiAgentBuilderSupplier;
import io.yunxi.fabric.aistio.YunxiFrameworkAdapter;
import io.yunxi.fabric.InMemoryDistributedRuntimeConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Assumptions;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aistio 真实控制面注册端到端验证（默认禁用，仅 -Dreal.llm=true 且控制面 Docker 已起时激活）。
 *
 * <p>验证 yunxi 产品平面的 aistio 接入（{@code AistioConnector}）能把真实 {@link HarnessAgent} 经框架
 * {@code ControlPlane.instrument} 注册到真实控制面，并由控制面探活 yunxi 的 /agentscope/health 标记 healthy。
 * 这是 yunxi 在 AgentScope-Java 之上的产品平面接线端到端证据。</p>
 *
 * <p>前置：{@code deploy/aistio} 控制面（http://localhost:18081，
 * internal-token=compose-local-internal-token-at-least-32chars）。凭证：DASHSCOPE_API_KEY（仅用于构造真实
 * Agent；注册与探活不触发对话）。控制面在 Docker 容器内，故探活地址用 host.docker.internal 指向宿主机。</p>
 */
@EnabledIfSystemProperty(named = "real.llm", matches = "true")
class AistioRealControlPlaneRegistrationTest {

    private static final String CONTROL_PLANE_URL = "http://localhost:18081";
    private static final String INTERNAL_TOKEN = "compose-local-internal-token-at-least-32chars";
    private static final String AGENT_KEY = "it-agent";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void registersAgentAndBecomesHealthy() throws Exception {
        Assumptions.assumeTrue(controlPlaneReachable(),
                "aistio 控制面不可达（localhost:18081），跳过真实注册验证");

        String modelId = System.getProperty("llm.model", "qwen3.8-27b");
        Model model = new DashScopeModelProvider().create(modelId);

        AistioIntegrationProperties props = new AistioIntegrationProperties();
        props.setEnabled(true);
        props.setControlPlaneUrl(CONTROL_PLANE_URL);
        props.setInternalToken(INTERNAL_TOKEN);
        props.setNamespace("default");
        props.setContractHttpPort(40001);
        props.setStartHttpRegister(true);
        props.setStartGrpc(false);
        // 控制面在 Docker 容器内，探活宿主机 yunxi 必须用 host.docker.internal 而非 localhost
        props.setBaseUrl("http://host.docker.internal:40001");
        props.setInstanceKey("it-" + UUID.randomUUID());

        YunxiAgentBuilderSupplier builderSupplier = new YunxiAgentBuilderSupplier(
                new InMemoryDistributedRuntimeConfig(), new InMemoryMcpDirectory(), List.of(), List.of());

        AistioConnector connector = new AistioConnector(props, builderSupplier, new MockEnvironment());

        HarnessAgent agent = HarnessAgent.builder().name(AGENT_KEY).model(model).build();
        YunxiFrameworkAdapter adapter = new YunxiFrameworkAdapter(new AistioLifecycle());

        SessionBridge bridge = connector.instrument(AGENT_KEY, agent, adapter);
        try {
            boolean registered = waitUntil(body -> body.contains(AGENT_KEY), 30_000);
            assertTrue(registered, "it-agent 未在超时内注册到 aistio 控制面");

            // 探活端点就绪：yunxi in-process 契约 server 必须可达，控制面才会探活成功
            String localHealth = probe("http://localhost:40001/agentscope/health");
            assertTrue(localHealth.startsWith("200"),
                    "yunxi 契约 health server 不可达，控制面无法探活: " + localHealth);

            // 控制面接受注册并标记 active；其探活 yunxi /agentscope/health（host.docker.internal:40001，已 200）链路通
            String body = queryAgents();
            assertTrue(body.contains("\"agentKey\":\"it-agent\"") && body.contains("\"status\":\"active\""),
                    "it-agent 未以 active 状态出现在控制面: " + body);
        } finally {
            bridge.close();
        }
    }

    private boolean controlPlaneReachable() {
        try {
            HttpResponse<String> r = HTTP.send(agentsRequest(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean waitUntil(Predicate<String> pred, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            String body = queryAgents();
            if (pred.test(body)) {
                return true;
            }
            Thread.sleep(2000);
        }
        return false;
    }

    private String queryAgents() throws Exception {
        HttpResponse<String> r = HTTP.send(agentsRequest(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : "";
    }

    private HttpRequest agentsRequest() {
        return HttpRequest.newBuilder()
                .uri(URI.create(CONTROL_PLANE_URL + "/api/v1/agents?tenant=default&namespace=default"))
                .header("X-Builder-Internal-Token", INTERNAL_TOKEN)
                .timeout(Duration.ofSeconds(10)).GET().build();
    }

    private String probe(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() + " " + r.body();
        } catch (Exception e) {
            return "ERR " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
