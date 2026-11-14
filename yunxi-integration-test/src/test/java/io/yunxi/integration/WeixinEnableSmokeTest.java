package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.gateway.ChannelManager;
import io.yunxi.app.im.ImChannelGatewayConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import io.yunxi.app.YunxiApplication;

/**
 * 微信（iLink）渠道启用冒烟测试（仿 GitHubEnableSmokeTest / WeComEnableSmokeTest）：
 * 仅验证 buildWeixin → WeixinChannel.create（6 参，含持久化状态存储注入）→ GatewayBootstrap.channel(...)
 * → start() 全链路接线在 2.0.4 / 2.1.0-BETA1 下跑通，不触达真实 iLink 服务端
 * （botToken 为假值、base-url 指向本地死端口，长轮询连接快速失败、指数退避 best-effort 仅告警）。
 *
 * 关键断言信号：框架无 WeixinChannelRegistry 进程级单例（与 GitHub / 企业微信 / 飞书不同），微信渠道注册进
 * GatewayBootstrap 内部的 ChannelManager。故断言 ImChannelGatewayConfig.getChannelManager() 非空且
 * getChannel("weixin-smoke").isPresent()（证明 WeixinChannel 已注册进 ChannelManager、网关启动成功、create 6 参接线通过）。
 *
 * 微信为长轮询模型（持续后台线程），@AfterEach 显式 stopAll 停 ChannelManager 下所有渠道轮询，避免 surefire 进程挂起。
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        "yunxi.im.weixin.enabled=true",
        "yunxi.im.weixin.channel-id=weixin-smoke",
        "yunxi.im.weixin.account-id=weixin-smoke",
        "yunxi.im.weixin.bot-token=smoke-bot-token",
        // 指向本地死端口：长轮询连接快速拒绝，不触达真实 iLink 服务端。
        "yunxi.im.weixin.base-url=http://localhost:9"
})
class WeixinEnableSmokeTest {

    @Autowired
    private ImChannelGatewayConfig config;

    @AfterEach
    void stopGateway() {
        // 微信是长轮询模型，显式停止 ChannelManager 下所有渠道轮询，避免后台线程使 surefire 进程挂起。
        ChannelManager cm = config.getChannelManager();
        if (cm != null) {
            try {
                cm.stopAll();
            } catch (Exception ignored) {
                // 停止失败不阻断测试判定
            }
        }
    }

    @Test
    @DisplayName("微信（iLink）渠道启用：buildWeixin 接线 + 注册进 ChannelManager + 网关启动成功")
    void weixin_channel_enabled_buildsAndRegisters() {
        ChannelManager cm = config.getChannelManager();
        assertThat(cm)
                .as("HarnessGateway 应已启动，ChannelManager 非空（证明 GatewayBootstrap.start 成功）")
                .isNotNull();
        assertThat(cm.getChannel("weixin-smoke"))
                .as("WeixinChannel 应已注册进 ChannelManager（channelId=weixin-smoke，证明 create 6 参接线通过）")
                .isPresent();
    }
}
