package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.extensions.channel.feishu.FeishuChannelRegistry;
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
 * 飞书启用冒烟测试：用假凭证（不入仓库）把 yunxi.im.feishu.enabled=true，
 * 验证启用路径（buildFeishu 映射 至 FeishuChannel.fromProperties 至 GatewayBootstrap 启动
 * 至渠道注册进 FeishuChannelRegistry）在真实 Spring 上下文里完整跑通、不抛异常。
 *
 * <p>可观测信号：回调端点从「未注册 channel 的 404」变为「已注册但签名错误的 401」。
 * 这证明 (a) FeishuCrypto 用 encrypt-key 构造成功（buildFeishu 的 rawProperties 映射正确）、
 * (b) GatewayBootstrap 成功启动并调用 FeishuChannel.start() 完成注册。
 * 不涉及真实大模型与真实飞书服务端。
 *
 * <p>使用独立 channelId=feishu-smoke，避免与 ProtocolIngressE2eTest 的未启用断言（channelId=feishu）互相污染；
 * 进程级 FeishuChannelRegistry 是静态单例，故 AfterAll 显式反注册，保持 JVM 干净。</p>
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // SecurityConfig 拒绝空口令启动，复用与 ProtocolIngressE2eTest 相同的测试口令。
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        "yunxi.im.feishu.enabled=true",
        "yunxi.im.feishu.channel-id=feishu-smoke",
        "yunxi.im.feishu.app-id=smokeAppId",
        "yunxi.im.feishu.app-secret=smokeAppSecret",
        "yunxi.im.feishu.encrypt-key=smokeEncryptKey",
        "yunxi.im.feishu.verification-token=smokeToken"
})
class FeishuEnableSmokeTest {

    private static final String CHANNEL_ID = "feishu-smoke";

    @LocalServerPort
    private int port;

    @AfterAll
    static void unregisterChannel() {
        // 防御性清理：进程级静态 registry 跨测试类共享，反注册本测试独占的 channelId。
        FeishuChannelRegistry.instance().unregister(CHANNEL_ID);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @Test
    @DisplayName("飞书启用：网关启动后渠道已注册，回调验签失败返回 401（POST dispatch）")
    void feishu_enabled_channelRegistered_dispatch_returnsUnauthorized() {
        TestRestTemplate anon = new TestRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 配了 encrypt-key → 渠道 isEncrypted()=true，控制器先验 X-Lark-Signature；非法签名即 401。
        headers.set("X-Lark-Signature", "probe");
        headers.set("X-Lark-Request-Timestamp", "1");
        headers.set("X-Lark-Request-Nonce", "1");
        String body = "{\"encrypt\":\"probe\"}";
        String url = baseUrl() + "/api/channels/feishu/" + CHANNEL_ID + "/callback";
        ResponseEntity<String> resp = anon.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        // 404 = 渠道未注册（启用或接线失败）；401 = 已注册但签名错误（启用且接线成功）。
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
