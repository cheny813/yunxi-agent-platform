package io.yunxi.platform.ingress.controller.channel;

import io.yunxi.platform.integration.channel.ChannelStatusRegistry;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IM 渠道运行状态 REST 端点（统一接入网关收编入口）。
 *
 * <p>暴露路径 /api/channels，返回当前已注册/运行的 IM 渠道清单，用于健康巡检与端到端验证。
 * 渠道总开关关闭时返回空清单。该端点经 core 的 WebMvcConfig 认证/限流拦截器白名单放行
 * （与 /api/health 同源）；拦截器注册在共享应用上下文，与控制器所在模块无关。</p>
 *
 * <p>迁移说明：原位于 agent-core 的 io.yunxi.platform.integration.channel.ChannelEndpoint，因违反
 * 「agent-core 禁止声明 @RestController」架构不变量（GatewayArchitectureTest）而迁至本统一网关模块。
 * 仅委托调用 core 的 ChannelStatusRegistry（yunxi 产品平面侧唯一真相源），不引入新依赖、不造环。</p>
 */
@RestController
public class ChannelEndpoint {

    private final ChannelStatusRegistry registry;

    public ChannelEndpoint(ChannelStatusRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/api/channels")
    public Map<String, Object> channels() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean hasChannels = registry != null && !registry.snapshot().isEmpty();
        result.put("enabled", hasChannels);
        result.put("channels", registry == null ? Map.of() : registry.snapshot().values());
        return result;
    }
}
