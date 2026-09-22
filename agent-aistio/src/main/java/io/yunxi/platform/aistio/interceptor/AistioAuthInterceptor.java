package io.yunxi.platform.aistio.interceptor;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.yunxi.platform.aistio.AistioIntegrationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * aistio 契约端点鉴权拦截器。
 *
 * <p>当配置了 {@code yunxi.aistio.internal-token} 时，要求请求携带匹配的令牌头；未配置则开放
 * （此时应依赖网络层访问控制来保障安全）。校验顺序：优先 {@code X-Builder-Internal-Token}
 * （新版 aistio prober 发送的头，对应 aistio 的 BUILDER_INTERNAL_TOKEN），兼容旧版
 * {@code X-Aistio-Token}。健康检查 {@code /agentscope/health} 排除在鉴权之外（aistio 默认探测路径）。</p>
 */
@Component
@RequiredArgsConstructor
public class AistioAuthInterceptor implements HandlerInterceptor {

    private final AistioIntegrationProperties properties;

    private static final String NEW_TOKEN_HEADER = "X-Builder-Internal-Token";
    private static final String LEGACY_TOKEN_HEADER = "X-Aistio-Token";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String expected = properties.getInternalToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String provided = request.getHeader(NEW_TOKEN_HEADER);
        if (provided == null) {
            provided = request.getHeader(LEGACY_TOKEN_HEADER);
        }
        if (expected.equals(provided)) {
            return true;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        return false;
    }
}
