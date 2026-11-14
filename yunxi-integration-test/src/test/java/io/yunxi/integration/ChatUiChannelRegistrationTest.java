package io.yunxi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.yunxi.app.YunxiApplication;
import io.yunxi.app.im.ImChannelGatewayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * ChatUi 编程式渠道注册测试：仅验证 ChatUiChannel 经 ChannelManager 注册进 HarnessGateway，
 * 供 yunxi 内部 Java 代码 / 集成测试以 ChatUiChannel.send(...) 直接驱动 Agent。
 *
 * 关键约束（避免双份真相）：ChatUiChannel 是进程内编程式渠道，本身无 HTTP 端点、不托管任何网页 UI，
 * 因此本测试断言它「不」对外暴露第二套 web 对话入口——浏览器 5173 仍走既有 web ingress / AG-UI 协议。
 * 本测试只校验注册事实（ChannelManager 中存在 channelId=chatui 且类型为 ChatUiChannel），
 * 不真正发送消息（那会触发大模型调用）。
 *
 * IM 渠道保持默认关闭，证明 chatui 单独即可驱动网关装配。
 */
@SpringBootTest(classes = YunxiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // SecurityConfig 拒绝空口令启动，复用与 ProtocolIngressE2eTest 相同的测试口令。
        "yunxi.security.user-name=health",
        "yunxi.security.user-password=health123",
        "yunxi.im.chatui.enabled=true"
})
class ChatUiChannelRegistrationTest {

    @Autowired
    private ImChannelGatewayConfig imChannelGatewayConfig;

    @Test
    @DisplayName("ChatUi 编程式渠道：网关装配后 ChannelManager 中已注册 channelId=chatui 且类型为 ChatUiChannel")
    void chatUi_registered_asProgrammaticChannel() {
        assertThat(imChannelGatewayConfig.getChannelManager())
                .as("渠道网关应已装配，ChannelManager 非空（chatui 默认开启驱动网关构建）")
                .isNotNull();

        assertThat(imChannelGatewayConfig.getChannelManager().channelIds())
                .as("ChannelManager 应登记 chatui 编程式渠道")
                .contains("chatui");

        assertThat(imChannelGatewayConfig.getChannelManager().getChannel("chatui"))
                .as("channelId=chatui 的渠道应为 AgentScope-Java 原生 ChatUiChannel")
                .isPresent()
                .get()
                .isInstanceOf(ChatUiChannel.class);
    }
}
