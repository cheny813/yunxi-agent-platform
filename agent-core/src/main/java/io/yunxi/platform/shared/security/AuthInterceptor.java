package io.yunxi.platform.shared.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 用户认证拦截器
 *
 * <p>
 * 自动从请求头提取用户信息并设置到请求属性中，
 * 供后续的 {@link SecurityContext} 使用。
 * </p>
 *
 * <p>
 * <b>工作流程</b>:
 * <ol>
 * <li>从请求头读取 X-User-Id、X-Username、X-User-Roles</li>
 * <li>构建 UserInfo 对象并设置到请求属性</li>
 * <li>后续可通过 SecurityContext 统一获取用户信息</li>
 * </ol>
 * </p>
 *
 * <p>
 * <b>使用方式</b>:
 * <pre>
 * // 在 WebMvcConfigurer 中注册拦截器
 * &#64;Configuration
 * public class WebMvcConfig implements WebMvcConfigurer {
 *     &#64;Autowired
 *     private AuthInterceptor authInterceptor;
 *
 *     &#64;Override
 *     public void addInterceptors(InterceptorRegistry registry) {
 *         registry.addInterceptor(authInterceptor)
 *                 .addPathPatterns("/api/**");
 *     }
 * }
 * </pre>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 请求头名称：用户ID
     */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 请求头名称：用户名
     */
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    @Autowired
    private SecurityContext securityContext;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 从请求头提取用户信息
        String userId = request.getHeader(HEADER_USER_ID);
        String roles = request.getHeader(HEADER_USER_ROLES);

        // 如果请求头中有用户信息，设置到请求属性
        if (userId != null && !userId.isEmpty()) {
            String[] roleArray = roles != null ? roles.split(",") : new String[]{"user"};
            SecurityContext.UserInfo userInfo = new SecurityContext.UserInfo(userId, userId, roleArray);
            securityContext.setRequestUserInfo(request, userInfo);

            log.debug("请求拦截器提取用户信息: userId={}, uri={}", userId, request.getRequestURI());
        }

        return true;
    }
}
