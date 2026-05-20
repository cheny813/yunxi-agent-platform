package io.yunxi.platform.gateway.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 网关管理 API 认证过滤器
 * <p>
 * 保护 {@code /api/gateway/**} 管理端点，要求请求携带有效的管理 Token。
 * 支持两种认证方式：
 * <ul>
 * <li>{@code X-Gateway-Admin-Token} 请求头</li>
 * <li>{@code Authorization: Bearer xxx} 请求头</li>
 * </ul>
 * </p>
 * <p>
 * 配置项：{@code gateway.admin-token}，为空则不启用认证（向后兼容）。
 * </p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class GatewayAdminAuthFilter implements Filter {

    private static final String ADMIN_TOKEN_HEADER = "X-Gateway-Admin-Token";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${gateway.admin-token:}")
    private String adminToken;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestPath = httpRequest.getRequestURI();

        // 仅保护 /api/gateway/** 管理端点
        if (!requestPath.startsWith("/api/gateway/")) {
            chain.doFilter(request, response);
            return;
        }

        // health 端点不需要认证
        if (requestPath.equals("/api/gateway/health")) {
            chain.doFilter(request, response);
            return;
        }

        // 未配置管理 Token 则不启用认证（向后兼容）
        if (adminToken == null || adminToken.isBlank()) {
            log.warn("网关管理 API 未配置 admin-token，管理端点无认证保护！请设置 gateway.admin-token");
            chain.doFilter(request, response);
            return;
        }

        // 验证 Token
        String providedToken = extractToken(httpRequest);
        if (providedToken != null && adminToken.equals(providedToken)) {
            chain.doFilter(request, response);
            return;
        }

        // 认证失败
        log.warn("网关管理 API 认证失败，路径: {}, 远程地址: {}", requestPath, httpRequest.getRemoteAddr());
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Valid admin token required. Use X-Gateway-Admin-Token or Authorization: Bearer <token>\"}");
    }

    /**
     * 从请求中提取 Token
     */
    private String extractToken(HttpServletRequest request) {
        // 优先检查专用 Header
        String token = request.getHeader(ADMIN_TOKEN_HEADER);
        if (token != null && !token.isBlank()) {
            return token.trim();
        }

        // 检查 Authorization Bearer
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }

        return null;
    }
}
