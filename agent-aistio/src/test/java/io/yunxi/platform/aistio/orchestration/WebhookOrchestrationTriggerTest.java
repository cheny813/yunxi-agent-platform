package io.yunxi.platform.aistio.orchestration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import io.yunxi.platform.aistio.AistioIntegrationProperties.TriggerConfig;
import io.yunxi.platform.aistio.AistioIntegrationProperties.TriggerConfig.AutomationBinding;

class WebhookOrchestrationTriggerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer server;
    private static int port;
    /** 服务端记录到的请求（path / 头 / 体），用于断言契约 */
    private static final List<Recorded> requests = new ArrayList<>();
    private static int responseStatus = 200;
    private static String responseBody = "{\"status\":\"queued\",\"delivery\":{\"id\":\"del-1\",\"runId\":\"arun-1\"}}";

    record Recorded(String path, String secret, String idempotencyKey, String body) {
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/hooks/v1/automations/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            requests.add(new Recorded(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Automation-Secret"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    body));
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void reset() {
        requests.clear();
        responseStatus = 200;
        responseBody = "{\"status\":\"queued\",\"delivery\":{\"id\":\"del-1\",\"runId\":\"arun-1\"}}";
    }

    private static AistioIntegrationProperties props() {
        AistioIntegrationProperties props = new AistioIntegrationProperties();
        props.setControlPlaneUrl("http://127.0.0.1:" + port);
        TriggerConfig config = new TriggerConfig();
        config.setEnabled(true);
        AutomationBinding binding = new AutomationBinding();
        binding.setAutomationId("auto-1");
        binding.setTriggerId("trig-1");
        binding.setSecret("sec-1");
        config.getAutomations().put("purchase-review", binding);
        props.setTrigger(config);
        return props;
    }

    @Test
    void triggersWebhookAndReturnsReceipt() throws Exception {
        WebhookOrchestrationTrigger trigger = new WebhookOrchestrationTrigger(props());

        TriggerReceipt receipt = trigger.start("purchase-review", Map.of("amount", 1200), "biz-1").block();

        assertEquals(1, requests.size());
        Recorded request = requests.get(0);
        assertEquals("/hooks/v1/automations/auto-1/trig-1", request.path());
        assertEquals("sec-1", request.secret());
        assertNotNull(request.idempotencyKey());

        assertNotNull(receipt);
        assertEquals("del-1", receipt.deliveryId());
        assertEquals("arun-1", receipt.automationRunId());
        assertEquals("queued", receipt.status());
        assertNotNull(receipt.correlationId());

        JsonNode body = MAPPER.readTree(request.body());
        assertEquals(1200, body.path("amount").asInt());
        assertEquals(receipt.correlationId(), body.path("_yunxi").path("correlationId").asText());
        assertEquals("biz-1", body.path("_yunxi").path("bizRequestId").asText());
    }

    @Test
    void reusesSameIdempotencyKeyForSameBusinessRequest() {
        WebhookOrchestrationTrigger trigger = new WebhookOrchestrationTrigger(props());

        trigger.start("purchase-review", Map.of("x", 1), "biz-same").block();
        trigger.start("purchase-review", Map.of("x", 1), "biz-same").block();
        trigger.start("purchase-review", Map.of("x", 1), "biz-other").block();

        assertEquals(3, requests.size());
        assertEquals(requests.get(0).idempotencyKey(), requests.get(1).idempotencyKey(),
                "同一 bizRequestId 重试必须复用同一个 Idempotency-Key");
        assertFalse(requests.get(2).idempotencyKey().equals(requests.get(0).idempotencyKey()),
                "不同业务请求必须使用不同的 Idempotency-Key");
    }

    @Test
    void mapsHttpErrorsToActionableMessages() {
        WebhookOrchestrationTrigger trigger = new WebhookOrchestrationTrigger(props());

        responseStatus = 401;
        assertThrows(IllegalStateException.class,
                () -> trigger.start("purchase-review", Map.of(), "biz-401").block());

        responseStatus = 404;
        IllegalStateException notFound = assertThrows(IllegalStateException.class,
                () -> trigger.start("purchase-review", Map.of(), "biz-404").block());
        assertTrue(notFound.getMessage().contains("automation"));

        responseStatus = 429;
        IllegalStateException limited = assertThrows(IllegalStateException.class,
                () -> trigger.start("purchase-review", Map.of(), "biz-429").block());
        assertTrue(limited.getMessage().contains("限流"));
    }

    @Test
    void rejectsUnknownDefinitionKeyAndBlankBusinessRequestId() {
        WebhookOrchestrationTrigger trigger = new WebhookOrchestrationTrigger(props());

        assertThrows(IllegalArgumentException.class,
                () -> trigger.start("unknown-key", Map.of(), "biz-x").block());
        assertThrows(IllegalArgumentException.class,
                () -> trigger.start("purchase-review", Map.of(), "  ").block());
        assertEquals(0, requests.size(), "参数校验失败不应打到服务端");
    }

    @Test
    void capabilityReportsAvailableWhenProbeReturnsExpected400() {
        responseStatus = 400;
        responseBody = "{\"error\":\"invalid triggerId\"}";

        OrchestrationCapability capability = new WebhookOrchestrationTrigger(props()).capability();

        assertTrue(capability.available());
        assertTrue(capability.problems().get(0).contains("可达"));
        // 探测必须用恒不存在的 triggerId，绝不触发真实流程
        assertTrue(requests.get(0).path().endsWith("yunxi-probe-nonexistent"));
    }

    @Test
    void capabilityReportsUnavailableOnBadSecret() {
        responseStatus = 401;

        OrchestrationCapability capability = new WebhookOrchestrationTrigger(props()).capability();

        assertFalse(capability.available());
        assertTrue(capability.problems().get(0).contains("secret"));
    }

    @Test
    void capabilityIsUnavailableWhenNotConfigured() {
        AistioIntegrationProperties props = new AistioIntegrationProperties();

        OrchestrationCapability capability = new WebhookOrchestrationTrigger(props).capability();

        assertFalse(capability.available());
        assertEquals(0, requests.size(), "未配置时不应发起任何探测请求");
    }
}
