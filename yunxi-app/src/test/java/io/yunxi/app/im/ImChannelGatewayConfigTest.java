package io.yunxi.app.im;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.Channel;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** 渠道装配失败隔离：单渠道构造异常只跳过该渠道，不中止其余渠道装配、不冒泡到上下文刷新。 */
class ImChannelGatewayConfigTest {

    @Test
    void failedChannelIsSkippedAndLaterChannelsStillRegister() {
        GatewayBootstrap.Builder builder = GatewayBootstrap.builder();
        AtomicBoolean firstSuccess = new AtomicBoolean(false);
        AtomicBoolean secondSuccess = new AtomicBoolean(false);

        ImChannelGatewayConfig.addChannel(
                builder, "dingtalk", () -> { throw new IllegalStateException("bad credentials"); },
                () -> firstSuccess.set(true));
        ImChannelGatewayConfig.addChannel(
                builder, "chatui", () -> org.mockito.Mockito.mock(Channel.class),
                () -> secondSuccess.set(true));

        assertFalse(firstSuccess.get(), "失败渠道不得执行成功回调");
        assertTrue(secondSuccess.get(), "失败渠道之后的其他渠道必须继续装配");
    }

    @Test
    void successfulChannelRunsCallbackAndRegisters() {
        GatewayBootstrap.Builder builder = GatewayBootstrap.builder();
        AtomicBoolean success = new AtomicBoolean(false);

        ImChannelGatewayConfig.addChannel(
                builder, "chatui", () -> org.mockito.Mockito.mock(Channel.class),
                () -> success.set(true));

        assertTrue(success.get(), "成功渠道必须执行成功回调");
    }
}
