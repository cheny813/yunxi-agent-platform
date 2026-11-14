package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.channel.github.GitHubChannelRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import io.yunxi.app.YunxiApplication;

/**
 * GitHub 渠道启用冒烟测试（仿 WeComEnableSmokeTest / FeishuEnableSmokeTest）：
 * 仅验证 buildGitHub → GitHubChannel.fromProperties → GatewayBootstrap.channel(...) → start() 全链路接线可在
 * 2.1.0-BETA1 core 下跑通，不触达真实 GitHub API（token / webhookSecret 均为假值，api-base 指向本地死端口，
 * 启动期 bot 身份解析 GET /user 快速失败、best-effort 仅告警）。
 *
 * 关键断言信号（与企微 / 飞书同构范式）：未启用时 webhook 探测为 404（未知 channel），启用注册后
 * 携带错误签名的探测翻转回 401（框架 HmacSHA256 验签），证明 GitHubChannel 已注册进 GitHubChannelRegistry、
 * GitHubWebhookController 端点已暴露且验签闸门生效。
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // SecurityConfig 拒绝空口令启动，复用与 ProtocolIngressE2eTest 相同的测试口令。
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        // 启用 GitHub 渠道（其余 IM 关闭；chatui 默认开启不触达执行路径）。
        "yunxi.im.github.enabled=true",
        "yunxi.im.github.channel-id=github-smoke",
        "yunxi.im.github.token=smoke-token",
        "yunxi.im.github.webhook-secret=smoke-secret",
        // 指向本地死端口：启动期 bot 身份解析 GET /user 快速失败，不触达真实 api.github.com。
        "yunxi.im.github.api-base=http://localhost:9999"
})
class GitHubEnableSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void cleanup() {
        // GitHubChannelRegistry 为进程级静态单例，清理避免污染同 JVM 后续测试。
        GitHubChannelRegistry.instance().unregister("github-smoke");
    }

    @Test
    @DisplayName("GitHub 渠道启用：buildGitHub 接线 + 注册 + webhook 验签闸门生效")
    void github_channel_enabled_buildsAndRegisters() {
        // 1) 渠道已注册进框架 GitHubChannelRegistry（证明 fromProperties + gateway 接线成功）。
        assertThat(GitHubChannelRegistry.instance().get("github-smoke"))
                .as("GitHubChannel 应已注册进 GitHubChannelRegistry（channelId=github-smoke）")
                .isNotNull();

        // 2) 携带错误签名的 webhook 探测被框架 HmacSHA256 验签拦截为 401（注册后闸门生效）。
        String url = "http://localhost:" + port + "/api/channels/github/github-smoke/webhook";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Hub-Signature-256", "sha256=invalid");
        headers.set("X-GitHub-Event", "ping");
        headers.set("X-GitHub-Delivery", "smoke-delivery");
        String body = "{\"zen\":\"smoke\"}";
        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("已注册的 GitHub 渠道，错误签名 webhook 应被验签拦截为 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
