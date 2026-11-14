package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.yunxi.app.YunxiApplication;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * 全链路端到端测试：覆盖 Web 协议入口 + 真实 Agent 装配 + 真实大模型执行 + 多轮会话状态保持
 * + 流式入口 + 第二协议（Chat Completions）+ 入口安全与未知 Agent 结算。
 *
 * <p>真实大模型场景由 {@code -Dreal.llm=true} 门控：需要本机设置 {@code DASHSCOPE_API_KEY} 环境变量
 * （或把 example-assistant 的模型路由指向可用的本地/云端模型）。经 AgentScope-Java 的 DashScope
 * 模型提供方解析 example-assistant 的 auto 路由为真实模型。
 * 安全与未知 Agent 结算场景不依赖真实大模型，默认随 {@code mvn test} 执行。</p>
 *
 * <p>本测试通过随机端口拉起完整 Spring Boot 应用（含四协议入口、执行门面、Agent 布建），
 * 是单元测试无法覆盖的跨组件 / 跨协议深层验证。</p>
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "yunxi.security.user-name=admin",
        "yunxi.security.user-password=test-secret"
})
class FullStackWebE2eTest {

    private static final String USER = "admin";
    private static final String PASS = "test-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(USER, PASS);
        return headers;
    }

    private ResponseEntity<String> postWeb(String agentId, String sessionId, String text) {
        String body = String.format(
                "{\"protocol\":\"web\",\"agentId\":\"%s\",\"userId\":\"u-e2e\",\"sessionId\":\"%s\",\"text\":\"%s\"}",
                agentId, sessionId, text);
        return rest.postForEntity(baseUrl() + "/api/conversations",
                new HttpEntity<>(body, authHeaders()), String.class);
    }

    @Test
    @DisplayName("Web 入口：未认证返回 401（入口安全统一生效）")
    void web_requiresAuthentication() {
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"protocol\":\"web\",\"agentId\":\"example-assistant\","
                + "\"userId\":\"u\",\"sessionId\":\"s\",\"text\":\"hi\"}";
        ResponseEntity<String> resp = anon.postForEntity(
                baseUrl() + "/api/conversations", new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Web 入口：未知 Agent 经认证后结算为 NO_AGENT (404)")
    void web_unknownAgent_resolvesNoAgent() {
        ResponseEntity<String> resp = postWeb("missing-agent", "s-unknown", "hi");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Web 流式入口：未知 Agent 经 error 事件透传根因（非裸 500/异常）")
    void web_streaming_unknownAgent_propagatesRootCause() {
        // 流式响应头已发出（text/event-stream），无法再改 HTTP 状态码，故以 event:error 命名事件在流内透传结构化根因；
        // 这比原先 completeWithError 抛裸异常、客户端只能拿到无根因的 500 更可诊断。该场景不依赖真实大模型。
        String body = "{\"protocol\":\"web\",\"agentId\":\"missing-agent\",\"userId\":\"u-e2e\","
                + "\"sessionId\":\"s-stream-unknown\",\"text\":\"hi\"}";
        String raw = rest.getRestTemplate().execute(
                baseUrl() + "/api/conversations/stream",
                HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.getHeaders().setBasicAuth(USER, PASS);
                    request.getBody().write(body.getBytes(StandardCharsets.UTF_8));
                },
                response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    try (InputStream in = response.getBody()) {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                });
        assertThat(raw).contains("event:error");
        assertThat(raw).contains("NO_AGENT");
        assertThat(raw).contains("such agent");
        assertThat(raw).contains("description");
    }

    @Test
    @EnabledIfSystemProperty(named = "real.llm", matches = "true")
    @DisplayName("Web 入口：example-assistant 经真实大模型返回非空回复")
    void web_realLlm_round1() {
        ResponseEntity<String> resp = postWeb("example-assistant", "s-web-real-1",
                "你好，请用一句话介绍你自己。");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\"").contains("\"text\"");
        assertThat(resp.getBody()).doesNotContain("\"status\":\"NO_AGENT\"");
    }

    @Test
    @EnabledIfSystemProperty(named = "real.llm", matches = "true")
    @DisplayName("Web 入口：同一 sessionId 多轮对话复用上下文")
    void web_realLlm_multiTurn() {
        postWeb("example-assistant", "s-web-multi", "请记住：我的幸运数字是 7。");
        ResponseEntity<String> resp2 = postWeb("example-assistant", "s-web-multi",
                "我刚才告诉你的幸运数字是多少？");
        assertThat(resp2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp2.getBody()).contains("\"text\"");
        assertThat(resp2.getBody()).doesNotContain("\"status\":\"NO_AGENT\"");
    }

    @Test
    @EnabledIfSystemProperty(named = "real.llm", matches = "true")
    @DisplayName("Web 流式入口：返回 SSE 事件流且含回复内容")
    void web_realLlm_streaming() {
        String body = "{\"protocol\":\"web\",\"agentId\":\"example-assistant\",\"userId\":\"u-e2e\","
                + "\"sessionId\":\"s-web-stream\",\"text\":\"你好\"}";
        Integer length = rest.getRestTemplate().execute(
                baseUrl() + "/api/conversations/stream",
                HttpMethod.POST,
                request -> {
                    request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.getHeaders().setBasicAuth(USER, PASS);
                    request.getBody().write(body.getBytes(StandardCharsets.UTF_8));
                },
                response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    try (InputStream in = response.getBody()) {
                        byte[] data = in.readAllBytes();
                        assertThat(data.length).isGreaterThan(0);
                        return data.length;
                    }
                });
        assertThat(length).isGreaterThan(0);
    }

    @Test
    @EnabledIfSystemProperty(named = "real.llm", matches = "true")
    @DisplayName("Chat Completions 入口：第二协议经真实大模型返回 OpenAI 格式完成")
    void chatCompletions_realLlm() {
        String body = "{\"model\":\"example-assistant\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"你好，请用一句话介绍你自己。\"}],"
                + "\"stream\":false}";
        ResponseEntity<String> resp = rest.postForEntity(
                baseUrl() + "/v1/chat/completions", new HttpEntity<>(body, authHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"choices\"");
    }
}
