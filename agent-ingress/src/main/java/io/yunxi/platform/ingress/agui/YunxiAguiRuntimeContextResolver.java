package io.yunxi.platform.ingress.agui;

import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.yunxi.platform.agent.runtime.YunxiRuntimeContextBuilder;
import io.yunxi.platform.security.auth.SecurityContext;
import org.springframework.stereotype.Component;

/**
 * AG-UI 协议身份解析器：作为产品平面适配层，把边缘鉴权解析出的调用方身份（userId）注入到每次 AG-UI
 * 请求的 {@link RuntimeContext} 中，使挂在 Agent 上的框架 Middleware（内容安全 / 审计 / 画像 / 历史 /
 * 链路追踪 / 人机交互）对 AG-UI 协议也能按身份统一生效。
 *
 * <p>这是框架 {@code AguiRuntimeContextResolver} 扩展点（{@code AguiRequestProcessor} 在每次请求处理时调用
 * {@code resolve(request)} 并将结果并入 Agent 运行时上下文）的 yunxi 落地实现。AG-UI 原生协议不产出
 * 业务身份，故由本解析器从边缘身份（{@link SecurityContext}）补齐 userId；协议适配器自身携带的运行时元数据
 * 优先级更高，仅在适配器未设置时取此处的值。
 */
@Component
public class YunxiAguiRuntimeContextResolver implements AguiRuntimeContextResolver {

    private final SecurityContext securityContext;

    public YunxiAguiRuntimeContextResolver(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    @Override
    public RuntimeContext resolve(AguiRuntimeContextRequest<?> request) {
        return YunxiRuntimeContextBuilder.build(securityContext.getCurrentUserId(), null, false, null, null, null);
    }
}
