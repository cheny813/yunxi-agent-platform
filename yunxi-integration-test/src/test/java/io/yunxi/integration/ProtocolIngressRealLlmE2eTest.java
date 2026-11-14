package io.yunxi.integration;

import io.yunxi.app.YunxiApplication;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.TenantAgentAllocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 四协议「授权内放行 + 真实大模型执行」的端到端验证（真实环境）。
 *
 * <p>本类默认不随 {@code mvn test} 执行，仅在显式 {@code -Dreal.llm=true} 且环境变量
 * {@code DASHSCOPE_API_KEY} 存在时激活（与 {@code RuntimeAssemblyRealLlmE2eTest} 同一定门控惯例）：
 * 真实大模型调用涉及外部网络与配额，不应拖慢常规套件或破坏无 key 的 CI。</p>
 *
 * <p>覆盖内容：为 {@code test-tenant} 在 {@link ConfigCenter} 注入「允许 example-assistant」的分配，
 * 经 Chat Completions 协议入口（带 {@code X-Tenant-Id} 头）发起一次真实对话，断言：
 * ① 授权闸门放行（非 404）；② 真实大模型返回了非空内容（端到端执行链路打通）。
 * 这补齐了 Step A（确定性、仅验授权闸门/拒绝）未触达的「授权内真实执行」路径。</p>
 *
 * <p>注：web / A2A / AG-UI 的「授权内真实执行」遵循同一门控套路，可类比扩展；本类先以 Chat Completions
 * 这一最干净的 HTTP 执行入口给出可实跑的范本。</p>
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret",
        "yunxi.tenant.enabled=true"
})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "real.llm", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProtocolIngressRealLlmE2eTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConfigCenter configCenter;

    @Value("${yunxi.security.user-name}")
    private String userName;

    @Value("${yunxi.security.user-password}")
    private String userPassword;

    @BeforeAll
    static void requireLlmKey() {
        Assumptions.assumeTrue(
                System.getenv("DASHSCOPE_API_KEY") != null,
                "未设置 DASHSCOPE_API_KEY，跳过真实大模型端到端测试（需 -Dreal.llm=true 且环境变量就绪）");
    }

    @AfterAll
    void restoreAllocation() {
        // 复原为空分配，避免影响同 JVM 内其他测试（如 ProtocolIngressE2eTest 用 tenant-a 做 fail-open）。
        configCenter.registerTenantAllocation(new TenantAgentAllocation(Map.of(), Set.of()));
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders authHeaders() {
        String token = Base64.getEncoder().encodeToString(
                (userName + ":" + userPassword).getBytes(StandardCharsets.UTF_8));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + token);
        return headers;
    }

    @Test
    @DisplayName("真实 LLM：test-tenant 分配 {example-assistant} 时，经 Chat Completions 请求真实对话 → 200 且返回非空内容")
    void chatCompletions_realLlm_allowedTenant_returnsCompletion() {
        // 授权内 Agent：通过闸门后真实调用 DashScope，验证「分配允许 + 端到端执行」在集成上下文真实生效。
        configCenter.registerTenantAllocation(
                new TenantAgentAllocation(Map.of("test-tenant", Set.of("example-assistant")), Set.of()));
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", "test-tenant");
        String body = "{\"model\":\"example-assistant\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"请用一句话介绍你自己。\"}],\"stream\":false}";
        org.springframework.http.ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/v1/chat/completions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        // 授权内 → 不应被拒绝（非 404）；真实大模型返回 → 200 且响应含 choices/content（非空执行结果）。
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body1 = resp.getBody();
        assertThat(body1).isNotNull();
        assertThat(body1).contains("choices");
        assertThat(body1).contains("content");
    }

    @Test
    @DisplayName("真实 LLM：test-tenant 分配 []（allow-none）时，经 Chat Completions 请求 → 404 NO_AGENT（拒绝优先于执行）")
    void chatCompletions_realLlm_deniedTenant_rejected() {
        // 即使真实环境有 key，越权请求也必须在触达大模型前被授权闸门拒绝（证明拒绝语义在真实上下文恒成立）。
        configCenter.registerTenantAllocation(
                new TenantAgentAllocation(Map.of("test-tenant", Set.of()), Set.of()));
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", "test-tenant");
        String body = "{\"model\":\"example-assistant\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        org.springframework.http.ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/v1/chat/completions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
