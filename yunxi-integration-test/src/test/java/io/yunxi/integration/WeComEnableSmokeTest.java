package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.channel.wecom.WeComChannelRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 企业微信启用冒烟测试：用假凭证（不入仓库）把 yunxi.im.wecom.enabled=true，
 * 验证启用路径（buildWeCom 映射 至 WeComChannel.fromProperties 至 GatewayBootstrap 启动
 * 至渠道注册进 WeComChannelRegistry）在真实 Spring 上下文里完整跑通、不抛异常。
 *
 * 可观测信号：回调端点从「未注册 channel 的 404」变为「已注册但签名错误的 401」。
 * 这证明 (a) WeComCrypto 用 43 位 encodingAesKey 构造成功（buildWeCom 的 rawProperties 映射正确）、
 * (b) GatewayBootstrap 成功启动并调用 WeComChannel.start() 完成注册。不涉及真实大模型与真实企业微信服务端。
 *
 * 使用独立 channelId=wecom-smoke，避免与 ProtocolIngressE2eTest 的未启用断言（channelId=wecom）互相污染；
 * 进程级 WeComChannelRegistry 是静态单例，故 AfterAll 显式反注册，保持 JVM 干净。
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // SecurityConfig 拒绝空口令启动，复用与 ProtocolIngressE2eTest 相同的测试口令。
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        "yunxi.im.wecom.enabled=true",
        "yunxi.im.wecom.channel-id=wecom-smoke",
        "yunxi.im.wecom.corpid=smokeCorpId",
        "yunxi.im.wecom.corpsecret=smokeSecret",
        "yunxi.im.wecom.agentid=1000002",
        "yunxi.im.wecom.token=smokeToken",
        "yunxi.im.wecom.aes-key=abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQ"
})
class WeComEnableSmokeTest {

    private static final String CHANNEL_ID = "wecom-smoke";

    @LocalServerPort
    private int port;

    @AfterAll
    static void unregisterChannel() {
        // 防御性清理：进程级静态 registry 跨测试类共享，反注册本测试独占的 channelId。
        WeComChannelRegistry.instance().unregister(CHANNEL_ID);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("企微启用：网关启动后渠道已注册，回调验签失败返回 401（POST dispatch）")
    void wecom_enabled_channelRegistered_dispatch_returnsUnauthorized() {
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        String body = "<xml><Encrypt>probe</Encrypt></xml>";
        String url = baseUrl() + "/api/channels/wecom/" + CHANNEL_ID + "/callback"
                + "?msg_signature=probe&timestamp=1&nonce=1";
        ResponseEntity<String> resp = anon.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        // 404 = 渠道未注册（启用或接线失败）；401 = 已注册但签名错误（启用且接线成功）。
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("企微启用：网关启动后渠道已注册，URL 校验握手验签失败返回 401（GET verify）")
    void wecom_enabled_channelRegistered_verify_returnsUnauthorized() {
        TestRestTemplate anon = new TestRestTemplate();
        String url = baseUrl() + "/api/channels/wecom/" + CHANNEL_ID + "/callback"
                + "?msg_signature=probe&timestamp=1&nonce=1&echostr=probe";
        ResponseEntity<String> resp = anon.getForEntity(url, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
