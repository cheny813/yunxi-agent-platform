package io.yunxi.app.im;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextResolver;
import io.agentscope.harness.agent.gateway.channel.ChannelRuntimeContextRequest;
import org.springframework.stereotype.Component;

/**
 * IM 渠道身份富化解析器：实现框架 ChannelRuntimeContextResolver，
 * 替代 yunxi 原有 ProtocolUserIdResolver + 各 RuntimeContextEnricher 的散点逻辑，
 * 作为网关统一入口之上的产品级 RuntimeContext 富化收口点。
 *
 * <p>当前 yunxi 业务级 RuntimeContext 富化器清单为空（RuntimeConfiguration.runtimeContextEnrichers() 默认 List.of()），
 * 故此处透传 callerContext；网关随后基于入站消息的 userId / sessionId / outboundAddress 覆盖身份字段。
 * 后续若 RuntimeContextEnrichers 填充业务富化器，在此统一收口，不再于各协议散点实现。</p>
 */
@Component
public class YunxiChannelRuntimeContextResolver implements ChannelRuntimeContextResolver {

    @Override
    public RuntimeContext resolve(ChannelRuntimeContextRequest request) {
        return request.callerContext();
    }
}
