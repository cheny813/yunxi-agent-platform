package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.channel.gitlab.GitLabChannelRegistry;
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
 * GitLab 渠道启用冒烟测试（仿 GitHubEnableSmokeTest / WeComEnableSmokeTest / FeishuEnableSmokeTest）：
 * 仅验证 buildGitLab → GitLabChannel.fromProperties → GatewayBootstrap.channel(...) → start() 全链路接线可在
 * 2.1.0-BETA1 core 下跑通，不触达真实 GitLab API（token / webhookToken 均为假值，api-base 指向本地死端口，
 * 启动期 bot 身份解析 GET /user 快速失败、best-effort 仅告警）。
 *
 * 关键断言信号（与 GitHub / 企微 / 飞书同构范式）：未启用时 webhook 探测为 404（未知 channel），启用注册后
 * 携带错误 X-Gitlab-Token 的探测翻转回 401（框架头比对验签），证明 GitLabChannel 已注册进 GitLabChannelRegistry、
 * GitLabWebhookController 端点已暴露且验签闸门生效。
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // SecurityConfig 拒绝空口令启动，复用与 ProtocolIngressE2eTest 相同的测试口令。
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        // 启用 GitLab 渠道（其余 IM 关闭；chatui 默认开启不触达执行路径）。
        "yunxi.im.gitlab.enabled=true",
        "yunxi.im.gitlab.channel-id=gitlab-smoke",
        "yunxi.im.gitlab.token=smoke-token",
        "yunxi.im.gitlab.webhook-token=smoke-webhook-token",
        // 指向本地死端口：启动期 bot 身份解析 GET /user 连接拒绝快速失败，不触达真实 gitlab.com。
        "yunxi.im.gitlab.api-base=http://localhost:9999"
})
class GitLabEnableSmokeTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void cleanup() {
        // GitLabChannelRegistry 为进程级静态单例，清理避免污染同 JVM 后续测试。
        GitLabChannelRegistry.instance().unregister("gitlab-smoke");
    }

    @Test
    @DisplayName("GitLab 渠道启用：buildGitLab 接线 + 注册 + webhook 验签闸门生效")
    void gitlab_channel_enabled_buildsAndRegisters() {
        // 1) 渠道已注册进框架 GitLabChannelRegistry（证明 fromProperties + gateway 接线成功）。
        assertThat(GitLabChannelRegistry.instance().get("gitlab-smoke"))
                .as("GitLabChannel 应已注册进 GitLabChannelRegistry（channelId=gitlab-smoke）")
                .isNotNull();

        // 2) 携带错误 X-Gitlab-Token 的 webhook 探测被框架头比对验签拦截为 401（注册后闸门生效）。
        String url = "http://localhost:" + port + "/api/channels/gitlab/gitlab-smoke/webhook";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Gitlab-Token", "wrong-token");
        headers.set("X-Gitlab-Event", "ping");
        String body = "{\"object_kind\":\"ping\"}";
        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("已注册的 GitLab 渠道，错误 X-Gitlab-Token webhook 应被验签拦截为 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
