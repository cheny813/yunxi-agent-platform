package io.yunxi.platform.security.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 边缘鉴权过滤器（身份解析唯一来源）。
 *
 * <p>在 Spring MVC 拦截器之前执行（servlet 过滤器先于 HandlerInterceptor），取代原
 * {@code AuthInterceptor} 的可伪造头信任模型，对受保护端点做真实鉴权：
 * <ul>
 *   <li>公开路径（探活）：直接放行，匿名可读。</li>
 *   <li>内部令牌路径（{@code /agentscope/**}、{@code /api/internal/**}）：校验
 *       {@code X-Builder-Internal-Token} 是否等于 {@code yunxi.aistio.internal-token}（与 aistio
 *       客户端共用同一配置），不匹配拒绝（闭环「从不校验内部令牌」的缺口）。</li>
 *   <li>受保护路径（{@code /api/**} 其余）：须持 {@code Authorization: Bearer <jwt>}，经
 *       {@link JwtVerifier} 验签；失败/缺失在 enforce 模式下返回 401。</li>
 * </ul>
 * 验证后的身份写入请求属性（{@link SecurityContext#setRequestUserInfo}），读取优先级高于可伪造头，
 * 使 {@code SecurityContext} 的角色守卫基于已验证身份。</p>
 *
 * <p>运行模式 {@code yunxi.security.mode}：
 * <ul>
 *   <li>{@code enforce}（默认）：受保护路径缺有效凭据即 401。</li>
 *   <li>{@code dev-trust}：受保护路径回退为读 {@code X-User-Id}/{@code X-User-Roles} 头（不验证），
 *       向后兼容本地无 IdP 开发，启动打印告警。</li>
 *   <li>安全兜底：enforce 且既无 JWT 验签能力也无内部令牌时，自动降级 dev-trust 并 ERROR 告警，
 *       避免未配置部署整站 401。</li>
 * </ul>
 */
@Slf4j
@Component
public class EdgeAuthenticationFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final String[] PUBLIC_PATTERNS = {
            "/api/health/**", "/actuator/**", "/agentscope/health", "/health", "/error"
    };
    private static final String[] INTERNAL_PATTERNS = {
            "/agentscope/**", "/api/internal/**"
    };
    private static final String[] PROTECTED_PATTERNS = {
            "/api/**"
    };

    /** 鉴权模式：enforce / dev-trust。 */
    @Value("${yunxi.security.mode:enforce}")
    private String mode;

    /** 内部令牌头名。 */
    @Value("${yunxi.security.internal-token-header:X-Builder-Internal-Token}")
    private String internalTokenHeader;

    /** 内部令牌值（与 aistio 客户端共用 yunxi.aistio.internal-token）。 */
    @Value("${yunxi.aistio.internal-token:}")
    private String internalToken;

    private final SecurityContext securityContext;
    private final JwtVerifier jwtVerifier;

    public EdgeAuthenticationFilter(SecurityContext securityContext, JwtVerifier jwtVerifier) {
        this.securityContext = securityContext;
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (matchesAny(uri, PUBLIC_PATTERNS)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (matchesAny(uri, INTERNAL_PATTERNS)) {
            if (!authenticateInternal(request, response)) {
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (matchesAny(uri, PROTECTED_PATTERNS)) {
            if (!authenticateProtected(request, response)) {
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        // 其余路径（根、静态资源、非 /api 的 websocket 升级等）放行，不强制 JWT。
        filterChain.doFilter(request, response);
    }

    private boolean authenticateInternal(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (internalToken == null || internalToken.isEmpty()) {
            // 未配置内部令牌：enforce 下拒绝（避免数据面裸奔）；dev-trust 下放行以兼容本地无 IdP 联调。
            if (isDevTrust()) {
                securityContext.setRequestUserInfo(request,
                        new SecurityContext.UserInfo("internal-service", "internal-service", new String[] { "INTERNAL" }));
                return true;
            }
            log.warn("内部令牌未配置（yunxi.aistio.internal-token 为空），拒绝数据面调用 {}", request.getRequestURI());
            sendUnauthorized(response, "internal token not configured");
            return false;
        }
        String presented = request.getHeader(internalTokenHeader);
        if (presented == null || !constantTimeEquals(presented, internalToken)) {
            log.warn("内部令牌不匹配，拒绝数据面调用 {}", request.getRequestURI());
            sendUnauthorized(response, "invalid internal token");
            return false;
        }
        securityContext.setRequestUserInfo(request,
                new SecurityContext.UserInfo("internal-service", "internal-service", new String[] { "INTERNAL" }));
        return true;
    }

    private boolean authenticateProtected(HttpServletRequest request, HttpServletResponse response) throws IOException {
        boolean devTrust = isDevTrust();
        String authHeader = request.getHeader("Authorization");
        Optional<SecurityContext.UserInfo> verified = jwtVerifier.verify(authHeader);
        if (verified.isPresent()) {
            securityContext.setRequestUserInfo(request, verified.get());
            return true;
        }
        if (!devTrust) {
            log.warn("受保护端点缺少有效 Bearer 令牌，拒绝 {}", request.getRequestURI());
            sendUnauthorized(response, "missing or invalid bearer token");
            return false;
        }
        // dev-trust：回退为读头（不验证），向后兼容
        String userId = request.getHeader("X-User-Id");
        if (userId != null && !userId.isEmpty()) {
            String roles = request.getHeader("X-User-Roles");
            String[] roleArray = roles != null ? roles.split(",") : new String[] { "user" };
            securityContext.setRequestUserInfo(request,
                    new SecurityContext.UserInfo(userId, userId, roleArray));
        }
        return true;
    }

    private boolean isDevTrust() {
        if ("dev-trust".equalsIgnoreCase(mode.trim())) {
            return true;
        }
        if ("enforce".equalsIgnoreCase(mode.trim())
                && !jwtVerifier.isVerificationAvailable()
                && (internalToken == null || internalToken.isEmpty())) {
            log.error("安全配置缺失：mode=enforce 但未配置 jwt.secret/jwt.jwks-uri 且未配置内部令牌，"
                    + "自动降级为 dev-trust（头信任，存在身份伪造风险）。生产环境必须配置 JWT 或内部令牌。");
            return true;
        }
        return false;
    }

    private boolean matchesAny(String uri, String[] patterns) {
        for (String p : patterns) {
            if (MATCHER.match(p, uri)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ba.length; i++) {
            result |= ba[i] ^ bb[i];
        }
        return result == 0;
    }

    private static void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
