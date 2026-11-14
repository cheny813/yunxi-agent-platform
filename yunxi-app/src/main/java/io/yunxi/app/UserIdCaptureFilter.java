package io.yunxi.app;

import io.yunxi.runtime.ProtocolUserIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 入口平面。在每个 HTTP 请求线程上解析并暂存 userId 到请求属性，
 * 供在异步线程执行的协议解析器（如 AG-UI 的 SseEmitter 线程池）安全读取，
 * 避免 Spring Security 的 ThreadLocal 上下文在异步线程丢失。
 *
 * <p>平台安全为无状态（不创建会话），身份仅存在于请求线程的 SecurityContext ThreadLocal；
 * AG-UI 控制器把请求处理提交到独立线程池，故必须在本过滤器（请求线程）先把 userId 落到
 * 请求对象属性，异步线程再读同一请求对象取回，做到无竞态、线程安全。
 *
 * <p>身份归一逻辑复用 {@link ProtocolUserIdResolver}（认证主体名 -> 请求头 X-Yunxi-User）。
 * 仅做传输层身份暂存。
 */
public class UserIdCaptureFilter extends OncePerRequestFilter {

    public static final String USER_ID_ATTR = "yunxi.userId";

    private final ProtocolUserIdResolver userIdResolver;

    /** 注入身份归一解析器。 */
    public UserIdCaptureFilter(ProtocolUserIdResolver userIdResolver) {
        this.userIdResolver = userIdResolver;
    }

    /** 在请求线程上解析 userId 并暂存到请求属性，供异步线程安全回读。 */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String headerUser = request.getHeader("X-Yunxi-User");
        String userId = userIdResolver.resolve(authentication, headerUser);
        if (userId != null) {
            request.setAttribute(USER_ID_ATTR, userId);
        }
        filterChain.doFilter(request, response);
    }
}
