package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import io.yunxi.app.YunxiApplication;
import io.yunxi.config.ConfigCenter;
import io.yunxi.config.TenantAgentAllocation;

import java.util.Map;
import java.util.Set;

/**
 * 四协议入口端到端测试：验证 Web / Chat Completions / A2A 三类协议的 HTTP 入口
 * 统一受 Basic Auth 保护、已知 Agent 可达、未知 Agent 结算为 404。
 *
 * <p>AG-UI 协议入口为框架 AguiRestController 暴露的 SSE 端点（POST /agui/run/{agentId}，默认
 * path-prefix=/agui）；其安全与身份由 YunxiAguiRuntimeContextResolver / YunxiGuardedAguiAgentAdapter 承接。
 * 本测试仅断言该端点的未认证 401（认证闸门），不发起完整 SSE 交互（后者需专门 AG-UI 客户端且涉及真实大模型），
 * 据此收口第四协议的「可达性 + 认证」基线。其余三类协议的已知/未知可达性已在上方断言。
 * 本测试不依赖真实大模型（AgentCard 元数据与认证闸门均不涉及执行），默认随 {@code mvn test} 执行。</p>
 *
 * <p>租户授权否定测试需要解析租户：默认 {@code yunxi.tenant.enabled=false}（装饰器 pass-through，fail-open），
 * 故本类显式开启 {@code yunxi.tenant.enabled=true} 使 {@code X-Tenant-Id} 头参与解析，方能触发「按租户隔离的
 * 授权拒绝」。注入的分配经 {@code InMemoryConfigCenter}（与四协议生产路径同源的单一 ConfigCenter Bean），
 * 验证 Chat Completions / A2A 入口在解析租户后真实调用 {@code TenantAgentAllocationService.assertAllowed}，
 * 越权（租户有记录但其目标 Agent 不在集合内）即结算 404；授权内 Agent 放行。
 * web 协议入口即 IngressGateway → DefaultExecutionFacade（由 yunxi-ingress 暴露，非本类 HTTP 端点），
 * 其授权否定由单元 {@code DefaultExecutionFacadeTest} 覆盖；AG-UI 入口需 SSE + 真实大模型，集成层不可行，
 * 由 {@code YunxiGuardedAguiAgentAdapterTest} 覆盖——均不在本类谎报覆盖。</p>
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret",
        "yunxi.tenant.enabled=true"
})
class ProtocolIngressE2eTest {

    private static final String USER = "admin";
    private static final String PASS = "test-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ConfigCenter configCenter;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(USER, PASS);
        return headers;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    @DisplayName("Chat Completions 入口：未认证返回 401（四协议安全统一覆盖）")
    void chatCompletions_requiresAuth() {
        TestRestTemplate anon = new TestRestTemplate();
        String body = "{\"model\":\"example-assistant\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/v1/chat/completions", new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("A2A AgentCard 入口：未认证返回 401（四协议安全统一覆盖）")
    void a2a_card_requiresAuth() {
        TestRestTemplate anon = new TestRestTemplate();
        ResponseEntity<String> resp = anon.getForEntity(
                baseUrl() + "/a2a/example-assistant/.well-known/agent.json", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("A2A 已知 Agent 的 AgentCard：经认证后可达 (200)")
    void a2a_knownAgent_cardReachable() {
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/a2a/example-assistant/.well-known/agent.json",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("A2A 未知 Agent 的 AgentCard：经认证后结算为 404")
    void a2a_unknownAgent_notFound() {
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/a2a/missing-agent/.well-known/agent.json",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("安全正向：/actuator/health 无需认证即可访问 (200)")
    void health_openWithoutAuth() {
        // SecurityConfig 仅白名单 /actuator/health（其余均需认证）；该白名单是安全基线，须有正向断言防回归。
        ResponseEntity<String> resp = rest.getForEntity(baseUrl() + "/actuator/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("status");
    }

    @Test
    @DisplayName("admin 运维平面：/actuator/agentscope-agents 未认证返回 401（admin 端点受 SecurityConfig 保护）")
    void adminAgents_requiresAuth() {
        // 框架 admin 运维平面由 agentscope.admin.enabled=true 开启；其盘点端点经 SecurityConfig 的
        // anyRequest().authenticated() 要求认证（仅 /actuator/health 白名单）。断言 admin 端点受保护、未授权不可达。
        TestRestTemplate anon = new TestRestTemplate();
        ResponseEntity<String> resp = anon.getForEntity(
                baseUrl() + "/actuator/agentscope-agents", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("admin 运维平面：/actuator/agentscope-agents 经认证后返回已注册 agent inventory (200)")
    void adminAgents_listsRegisteredAgents() {
        // AgentProvisioner 在布建期把 HarnessAgent 注册进框架 AgentRegistry；该端点读取 AgentRegistry
        // 返回全局 agent inventory。断言认证后可访问且响应包含已布建的 agent（证明注册链路真实生效、admin 端点真实加载）。
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/actuator/agentscope-agents",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("example-assistant");
    }

    @Test
    @DisplayName("Chat Completions 入口：未知 model 经认证后结算为 NO_AGENT (404)")
    void chatCompletions_unknownModel_notFound() {
        // 与 web 入口一致：未解析到目标 Agent 即 NO_AGENT (404)，且不触发真实大模型调用，确定性可执行。
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"model\":\"missing-model\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/v1/chat/completions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("AG-UI 入口：未认证请求必须返回 401（四协议认证基线收口）")
    void agui_endpoint_requiresAuth() {
        // 框架 AguiRestController 暴露 POST /agui/run/{agentId}（默认 path-prefix=/agui）；
        // 认证由 SecurityConfig 的 anyRequest().authenticated() 在抵达控制器前拦截，故未携带凭据即 401，
        // 与请求体格式无关。仅断言认证闸门（不发起完整 SSE 交互），收口第四协议的「可达性 + 认证」基线。
        TestRestTemplate anon = new TestRestTemplate();
        String body = "{\"threadId\":\"t1\",\"runId\":\"r1\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/agui/run/example-assistant",
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("IM 钉钉回调入口：未带合法签名返回 401（框架 DingTalkCallbackController 验签闸门）")
    void dingtalk_callback_requiresValidSignature() {
        // 钉钉回调端点由 SecurityConfig 放行 permitAll（真实性由框架 DingTalkCallbackCrypto 验签保证，
        // 非法签名 / 未知 channel 控制器均返回 401，不挂 HTTP Basic）。本测试不启用真实钉钉渠道，
        // 故 channel 未注册，未带签名的探测请求即被控制器判为 401，断言验签闸门存在。
        TestRestTemplate anon = new TestRestTemplate();
        String body = "{\"encrypt\":\"\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/api/channels/dingtalk/dingtalk/callback",
                new HttpEntity<>(body, jsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("IM 企业微信回调入口：未注册 channel 返回 404（框架 WeComCallbackController 闸门）")
    void wecom_callback_unknownChannel_notFound() {
        // 企微回调端点由 SecurityConfig 放行 permitAll（真实性由框架 WeComCrypto 验签保证）。
        // 本测试不启用真实企业微信渠道，故 channel 未注册，携带 query 参数与 XML 体的探测请求
        // 被控制器判为 404（未知 channel）；签名不匹配则为 401。断言企微回调闸门存在且端点已暴露。
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        String body = "<xml><Encrypt>probe</Encrypt></xml>";
        String url = baseUrl() + "/api/channels/wecom/wecom/callback"
                + "?msg_signature=probe&timestamp=1&nonce=1";
        ResponseEntity<String> resp = anon.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IM 飞书回调入口：未注册 channel 返回 404（框架 FeishuCallbackController 闸门）")
    void feishu_callback_unknownChannel_notFound() {
        // 飞书回调端点由 SecurityConfig 放行 permitAll（真实性由框架 FeishuCrypto 验签保证）。
        // 本测试不启用真实飞书渠道，故 channel 未注册，携带 JSON 体与 Lark 签名头的探测请求
        // 被控制器判为 404（未知 channel）；签名不匹配则为 401。断言飞书回调闸门存在且端点已暴露。
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Lark-Signature", "probe");
        headers.set("X-Lark-Request-Timestamp", "1");
        headers.set("X-Lark-Request-Nonce", "1");
        String body = "{\"encrypt\":\"probe\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/api/channels/feishu/feishu/callback",
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IM GitHub webhook 入口：未注册 channel 返回 404（框架 GitHubWebhookController 闸门）")
    void github_callback_unknownChannel_notFound() {
        // GitHub webhook 端点由 SecurityConfig 放行 permitAll（真实性由框架 HmacSHA256 验签保证）。
        // 本测试不启用真实 GitHub 渠道，故 channel 未注册，携带 JSON 体与签名头的探测请求
        // 被控制器判为 404（未知 channel）；签名不匹配则为 401。断言 GitHub webhook 闸门存在且端点已暴露。
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=probe");
        headers.set("X-GitHub-Event", "ping");
        headers.set("X-GitHub-Delivery", "probe");
        String body = "{\"zen\":\"probe\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/api/channels/github/github/webhook",
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("IM GitLab webhook 入口：未注册 channel 返回 404（框架 GitLabWebhookController 闸门）")
    void gitlab_callback_unknownChannel_notFound() {
        // GitLab webhook 端点由 SecurityConfig 放行 permitAll（真实性由框架 X-Gitlab-Token 头比对验签保证）。
        // 本测试不启用真实 GitLab 渠道，故 channel 未注册，携带 JSON 体与 X-Gitlab-Token 头的探测请求
        // 被控制器判为 404（未知 channel）；令牌不匹配则为 401。断言 GitLab webhook 闸门存在且端点已暴露。
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Gitlab-Token", "probe");
        headers.set("X-Gitlab-Event", "ping");
        String body = "{\"object_kind\":\"ping\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/api/channels/gitlab/gitlab/webhook",
                new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("per-tenant 列示端点：未认证返回 401（受 SecurityConfig 保护，与四协议认证基线一致）")
    void tenantAllocationAdmin_requiresAuth() {
        // 产品平面新增 GET /v1/admin/tenants/{tenantId}/agents（per-tenant 列示）。
        // 该端点与框架 admin 平面同源受 SecurityConfig 的 anyRequest().authenticated() 保护，
        // 未携带凭据即 401；断言认证闸门存在且端点已暴露（证明新端点与既有四协议入口共存、未改变安全基线）。
        TestRestTemplate anon = new TestRestTemplate();
        ResponseEntity<String> resp = anon.getForEntity(
                baseUrl() + "/v1/admin/tenants/tenant-a/agents", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("per-tenant 列示端点：经认证后返回被授权 Agent 身份列表 (200，fail-open 含全量目录)")
    void tenantAllocationAdmin_listsAgents() {
        // 端点组装「分配集合 ∩ 当前 Agent 定义目录」；tenant-a 在测试中无分配记录，按 fail-open 返回全量目录。
        // 断言认证后可访问、响应包含已布建的 agent（证明端点真实加载、与四协议入口共存无回归）。
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/v1/admin/tenants/tenant-a/agents",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("example-assistant");
    }

    // ---- 四协议授权否定 / 肯定测试（集成层，确定性，无需真实大模型）----
    // 生产路径：四协议入口解析租户后经 TenantAgentAllocationService.assertAllowed 拦截越权请求（404 NO_AGENT）。
    // 以下经 ConfigCenter Bean 注入同一语义的租户分配，断言 Chat Completions / A2A 入口在集成上下文真实触发该闸门。

    @Test
    @DisplayName("四协议授权：test-tenant 分配 []（allow-none）时，经 Chat Completions 请求 example-assistant → 404 NO_AGENT")
    void chatCompletions_tenantAllocationDenies() {
        // 租户"有记录但集合为空"→ 任何 Agent 均不在集合内 → 越权拒绝。
        configCenter.registerTenantAllocation(
                new TenantAgentAllocation(Map.of("test-tenant", Set.of()), Set.of()));
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Tenant-Id", "test-tenant");
        String body = "{\"model\":\"example-assistant\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/v1/chat/completions",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("四协议授权：test-tenant 分配 [example-assistant] 时，经 A2A 请求其 AgentCard → 200（授权内放行，不触 LLM）")
    void a2a_tenantAllocationAllows() {
        // 租户"有记录且目标 Agent 在集合内"→ 通过授权闸门；AgentCard 为元数据、不触大模型，故确定性 200。
        configCenter.registerTenantAllocation(
                new TenantAgentAllocation(Map.of("test-tenant", Set.of("example-assistant")), Set.of()));
        HttpHeaders headers = authHeaders();
        headers.set("X-Tenant-Id", "test-tenant");
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/a2a/example-assistant/.well-known/agent.json",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("四协议授权：test-tenant 分配 []（allow-none）时，经 A2A 请求 example-assistant 的 AgentCard → 404 NO_AGENT")
    void a2a_tenantAllocationDenies() {
        // 与 Chat Completions 同源：租户有记录但集合为空 → 越权拒绝（404）。
        configCenter.registerTenantAllocation(
                new TenantAgentAllocation(Map.of("test-tenant", Set.of()), Set.of()));
        HttpHeaders headers = authHeaders();
        headers.set("X-Tenant-Id", "test-tenant");
        ResponseEntity<String> resp = rest.exchange(
                baseUrl() + "/a2a/example-assistant/.well-known/agent.json",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
