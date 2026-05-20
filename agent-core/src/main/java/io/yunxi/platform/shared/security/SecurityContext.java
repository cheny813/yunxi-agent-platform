package io.yunxi.platform.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;

/**
 * 安全上下文工具类
 *
 * <p>
 * 提供统一的用户认证信息提取接口，支持多种用户信息来源：
 * <ul>
 * <li>请求头 X-User-Id（当前使用的方式）</li>
 * <li>请求属性（从拦截器传递）</li>
 * <li>ThreadLocal（便于在任意位置获取）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>设计原则</b>:
 * <ul>
 * <li>向下兼容：保留 "anonymous-user" 作为默认值</li>
 * <li>可扩展性：预留 Spring Security / JWT 集成点</li>
 * <li>线程安全：使用 ThreadLocal 存储用户上下文</li>
 * </ul>
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class SecurityContext {

    /**
     * 请求头名称：用户ID
     */
    private static final String HEADER_USER_ID = "X-User-Id";

    /**
     * 请求头名称：用户名
     */
    private static final String HEADER_USERNAME = "X-Username";

    /**
     * 请求头名称：用户角色
     */
    private static final String HEADER_USER_ROLES = "X-User-Roles";

    /**
     * 请求属性名称：用户信息
     */
    private static final String ATTR_USER_INFO = "USER_INFO";

    /**
     * 默认用户ID（未认证用户）
     */
    private static final String DEFAULT_USER_ID = "anonymous-user";

    /**
     * ThreadLocal 存储当前线程的用户上下文（用于异步任务等场景）
     */
    private static final ThreadLocal<UserInfo> currentUser = new ThreadLocal<>();

    /**
     * JWT 签名密钥（可选配置）
     */
    @Value("${jwt.secret:#{null}}")
    private String jwtSecret;

    /**
     * JWT 请求头名称（默认 Authorization）
     */
    @Value("${jwt.header:Authorization}")
    private String jwtHeader;

    /**
     * JWT Token 前缀（默认 Bearer ）
     */
    @Value("${jwt.prefix:Bearer }")
    private String jwtPrefix;

    /**
     * Spring Security 是否可用的标志（延迟检测）
     */
    private Boolean springSecurityAvailable = null;

    /**
     * 用户信息实体
     */
    public static class UserInfo {
        private final String userId;
        private final String username;
        private final String[] roles;

        public UserInfo(String userId, String username, String[] roles) {
            this.userId = userId;
            this.username = username;
            this.roles = roles;
        }

        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String[] getRoles() {
            return roles;
        }
    }

    /**
     * 获取当前用户ID
     *
     * <p>
     * 按以下优先级获取用户ID：
     * <ol>
     * <li>ThreadLocal（最高优先级，用于异步任务）</li>
     * <li>请求属性（由拦截器设置）</li>
     * <li>Spring Security 上下文（如果可用）</li>
     * <li>JWT Token（如果配置了密钥）</li>
     * <li>请求头 X-User-Id</li>
     * <li>默认值 "anonymous-user"</li>
     * </ol>
     * </p>
     *
     * @return 用户ID
     */
    public String getCurrentUserId() {
        // 1. 优先从 ThreadLocal 获取（用于异步任务等场景）
        UserInfo userInfo = currentUser.get();
        if (userInfo != null && userInfo.getUserId() != null) {
            log.debug("从 ThreadLocal 获取用户ID: {}", userInfo.getUserId());
            return userInfo.getUserId();
        }

        // 2. 从请求上下文获取
        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            // 2.1 从请求属性获取（由拦截器设置）
            UserInfo attrUserInfo = (UserInfo) request.getAttribute(ATTR_USER_INFO);
            if (attrUserInfo != null && attrUserInfo.getUserId() != null) {
                log.debug("从请求属性获取用户ID: {}", attrUserInfo.getUserId());
                return attrUserInfo.getUserId();
            }

            // 2.2 从 Spring Security 获取（如果可用）
            String springSecurityUserId = getUserIdFromSpringSecurity();
            if (springSecurityUserId != null) {
                log.debug("从 Spring Security 获取用户ID: {}", springSecurityUserId);
                return springSecurityUserId;
            }

            // 2.3 从 JWT Token 获取（如果配置了密钥）
            String jwtUserId = getUserIdFromJwtToken(request);
            if (jwtUserId != null) {
                log.debug("从 JWT Token 获取用户ID: {}", jwtUserId);
                return jwtUserId;
            }

            // 2.4 从请求头获取（当前主要方式）
            String userId = request.getHeader(HEADER_USER_ID);
            if (userId != null && !userId.isEmpty()) {
                log.debug("从请求头获取用户ID: {}", userId);
                return userId;
            }
        }

        // 3. 返回默认值
        log.debug("未获取到用户ID，使用默认值: {}", DEFAULT_USER_ID);
        return DEFAULT_USER_ID;
    }

    /**
     * 获取当前用户名
     *
     * @return 用户名（如果未设置返回用户ID）
     */
    public String getCurrentUsername() {
        UserInfo userInfo = currentUser.get();
        if (userInfo != null && userInfo.getUsername() != null) {
            return userInfo.getUsername();
        }

        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            UserInfo attrUserInfo = (UserInfo) request.getAttribute(ATTR_USER_INFO);
            if (attrUserInfo != null && attrUserInfo.getUsername() != null) {
                return attrUserInfo.getUsername();
            }

            String username = request.getHeader(HEADER_USERNAME);
            if (username != null && !username.isEmpty()) {
                return username;
            }
        }

        // 默认返回用户ID
        return getCurrentUserId();
    }

    /**
     * 获取当前用户角色
     *
     * @return 用户角色数组（如果未设置返回默认角色 ["user"]）
     */
    public String[] getCurrentUserRoles() {
        UserInfo userInfo = currentUser.get();
        if (userInfo != null && userInfo.getRoles() != null) {
            return userInfo.getRoles();
        }

        HttpServletRequest request = getCurrentRequest();
        if (request != null) {
            UserInfo attrUserInfo = (UserInfo) request.getAttribute(ATTR_USER_INFO);
            if (attrUserInfo != null && attrUserInfo.getRoles() != null) {
                return attrUserInfo.getRoles();
            }

            String rolesHeader = request.getHeader(HEADER_USER_ROLES);
            if (rolesHeader != null && !rolesHeader.isEmpty()) {
                return rolesHeader.split(",");
            }
        }

        // 默认角色
        return new String[]{"user"};
    }

    /**
     * 判断当前用户是否已认证
     *
     * @return true=已认证, false=未认证（匿名用户）
     */
    public boolean isAuthenticated() {
        String userId = getCurrentUserId();
        return !DEFAULT_USER_ID.equals(userId);
    }

    /**
     * 设置当前线程的用户上下文（用于异步任务）
     *
     * <p>
     * 使用示例：
     * <pre>
     * // 在主线程设置
     * UserInfo userInfo = new UserInfo("user123", "张三", new String[]{"admin", "user"});
     * securityContext.setCurrentUser(userInfo);
     *
     * // 在异步任务中使用
     * asyncTask(() -> {
     *     String userId = securityContext.getCurrentUserId(); // 返回 "user123"
     * });
     *
     * // 清理上下文
     * securityContext.clearCurrentUser();
     * </pre>
     * </p>
     *
     * @param userInfo 用户信息
     */
    public void setCurrentUser(UserInfo userInfo) {
        currentUser.set(userInfo);
        log.debug("设置当前线程用户上下文: userId={}", userInfo.getUserId());
    }

    /**
     * 清除当前线程的用户上下文
     *
     * <p>
     * <b>重要</b>：异步任务完成后必须调用此方法清理 ThreadLocal，防止内存泄漏。
     * </p>
     */
    public void clearCurrentUser() {
        currentUser.remove();
        log.debug("清除当前线程用户上下文");
    }

    /**
     * 设置请求属性中的用户信息（由拦截器调用）
     *
     * @param request   HTTP 请求
     * @param userInfo  用户信息
     */
    public void setRequestUserInfo(HttpServletRequest request, UserInfo userInfo) {
        if (request != null && userInfo != null) {
            request.setAttribute(ATTR_USER_INFO, userInfo);
            log.debug("设置请求属性用户信息: userId={}", userInfo.getUserId());
        }
    }

    /**
     * 获取当前 HTTP 请求
     *
     * @return 当前请求（如果在 Web 上下文中）
     */
    private HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs != null ? attrs.getRequest() : null;
        } catch (Exception e) {
            log.debug("无法获取当前请求: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Spring Security 集成 ====================

    /**
     * 从 Spring Security 上下文获取用户ID
     *
     * <p>
     * 使用条件检测自动判断 Spring Security 是否可用：
     * <ul>
     * <li>如果类路径中有 SecurityContextHolder，返回用户ID</li>
     * <li>如果类路径中没有，返回 null（降级到其他方式）</li>
     * </ul>
     * </p>
     *
     * <p>
     * <b>配置要求</b>：无需配置，自动检测。如需启用，添加依赖：
     * <pre>
     * &lt;dependency&gt;
     *     &lt;groupId&gt;org.springframework.boot&lt;/groupId&gt;
     *     &lt;artifactId&gt;spring-boot-starter-security&lt;/artifactId&gt;
     * &lt;/dependency&gt;
     * </pre>
     * </p>
     *
     * @return 用户ID（如果 Spring Security 可用且已认证）
     */
    protected String getUserIdFromSpringSecurity() {
        // 延迟检测：只在第一次调用时检测 Spring Security 是否可用
        if (springSecurityAvailable == null) {
            try {
                Class.forName("org.springframework.security.core.context.SecurityContextHolder");
                springSecurityAvailable = true;
                log.info("检测到 Spring Security 可用，已启用 SecurityContext 集成");
            } catch (ClassNotFoundException e) {
                springSecurityAvailable = false;
                log.debug("Spring Security 不可用，降级到请求头/JWT 认证");
            }
        }

        // 如果 Spring Security 不可用，直接返回
        if (!springSecurityAvailable) {
            return null;
        }

        // 调用 Spring Security 获取用户信息（使用反射避免编译时依赖）
        try {
            Class<?> holderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object context = holderClass.getMethod("getContext").invoke(null);
            Object authentication = context.getClass().getMethod("getAuthentication").invoke(context);

            if (authentication != null) {
                Boolean isAuthenticated = (Boolean) authentication.getClass().getMethod("isAuthenticated").invoke(authentication);
                Object principal = authentication.getClass().getMethod("getPrincipal").invoke(authentication);

                if (isAuthenticated && !"anonymousUser".equals(String.valueOf(principal))) {
                    return (String) authentication.getClass().getMethod("getName").invoke(authentication);
                }
            }
        } catch (Exception e) {
            log.debug("从 Spring Security 获取用户ID失败: {}", e.getMessage());
        }

        return null;
    }

    // ==================== JWT Token 集成 ====================

    /**
     * 从 JWT Token 获取用户ID（从请求头自动提取）
     *
     * <p>
     * <b>配置要求</b>：在 application.yml 中配置：
     * <pre>
     * jwt:
     *   secret: your-secret-key-at-least-256-bits  # 必需，签名密钥
     *   header: Authorization                       # 可选，默认 Authorization
     *   prefix: "Bearer "                           # 可选，默认 "Bearer "
     * </pre>
     * </p>
     *
     * <p>
     * <b>Token 格式</b>：
     * <ul>
     * <li>标准格式：Authorization: Bearer &lt;jwt-token&gt;</li>
     * <li>自定义格式：通过 jwt.header 和 jwt.prefix 配置</li>
     * </ul>
     * </p>
     *
     * @param request HTTP 请求
     * @return 用户ID（如果 JWT 有效）
     */
    protected String getUserIdFromJwtToken(HttpServletRequest request) {
        // 未配置密钥，跳过 JWT 解析
        if (jwtSecret == null || jwtSecret.isEmpty()) {
            return null;
        }

        try {
            // 从请求头提取 Token
            String authHeader = request.getHeader(jwtHeader);
            if (authHeader == null || authHeader.isEmpty()) {
                return null;
            }

            // 移除前缀（如 "Bearer "）
            String token = authHeader;
            if (jwtPrefix != null && !jwtPrefix.isEmpty() && authHeader.startsWith(jwtPrefix)) {
                token = authHeader.substring(jwtPrefix.length());
            }

            // 解析 JWT
            return parseJwtToken(token);

        } catch (Exception e) {
            log.debug("JWT Token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 JWT Token 获取用户ID
     *
     * @param token JWT Token（纯 token，不含前缀）
     * @return 用户ID（sub 声明）
     */
    private String parseJwtToken(String token) {
        try {
            // 使用密钥解析 Token
            Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));

            Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // 获取用户ID（优先使用 sub 声明，其次 userId 声明）
            String userId = claims.getSubject();
            if (userId == null) {
                userId = claims.get("userId", String.class);
            }

            if (userId != null) {
                log.debug("JWT 解析成功: userId={}, issuedAt={}", userId, claims.getIssuedAt());
            }

            return userId;

        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("JWT Token 已过期: {}", e.getMessage());
            return null;
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.warn("JWT Token 签名无效: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("JWT Token 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证 JWT Token 是否有效
     *
     * @param token JWT Token
     * @return true=有效, false=无效或已过期
     */
    public boolean validateJwtToken(String token) {
        if (jwtSecret == null || jwtSecret.isEmpty() || token == null) {
            return false;
        }

        try {
            Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Jwts.parser().setSigningKey(key).build().parseClaimsJws(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT Token 验证失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 从 JWT Token 提取所有声明
     *
     * @param token JWT Token
     * @return Claims 对象（包含所有声明），失败返回 null
     */
    public Claims getJwtClaims(String token) {
        if (jwtSecret == null || jwtSecret.isEmpty() || token == null) {
            return null;
        }

        try {
            Key key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
            return Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (Exception e) {
            log.debug("获取 JWT Claims 失败: {}", e.getMessage());
            return null;
        }
    }
}
