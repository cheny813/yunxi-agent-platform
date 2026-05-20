package io.yunxi.platform.infra.config;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.yunxi.platform.infra.network.IpDetectionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流拦截器
 *
 * <p>
 * 基于 IP 和用户 ID 进行限流控制，支持集群部署。
 * 使用 Resilience4j RateLimiter 实现。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

/** IP 检测服务 */
    @Autowired
    private ObjectProvider<IpDetectionService> ipDetectionServiceProvider;

    /** 按 IP 限流器缓存 */
    private final Map<String, RateLimiter> ipLimiters = new ConcurrentHashMap<>();

    /** 按用户限流器缓存 */
    private final Map<String, RateLimiter> userLimiters = new ConcurrentHashMap<>();

    /** IP 每秒请求限制 */
    @Value("${rate-limit.ip.requests-per-second:10}")
    private int ipRequestsPerSecond;

    /** 用户每秒请求限制 */
    @Value("${rate-limit.user.requests-per-second:50}")
    private int userRequestsPerSecond;

    /** 是否启用限流 */
    @Value("${rate-limit.enabled:true}")
    private boolean enabled;

    /**
     * 请求预处理，执行限流检查
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param handler  处理器
     * @return 是否允许继续处理
     * @throws Exception 异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }

        String clientIp = getClientIp(request);
        String userId = getUserId(request);

        // 优先按用户限流
        if (userId != null && !userId.isEmpty()) {
            if (!tryAcquire(userId, userLimiters, userRequestsPerSecond, "user:" + userId)) {
                sendTooManyRequestsResponse(response, "用户请求过于频繁");
                return false;
            }
        } else {
            // 按 IP 限流
            if (!tryAcquire(clientIp, ipLimiters, ipRequestsPerSecond, "ip:" + clientIp)) {
                sendTooManyRequestsResponse(response, "请求过于频繁，请稍后重试");
                return false;
            }
        }

        return true;
    }

    /**
     * 尝试获取限流许可
     *
     * @param key             限流键
     * @param limiterCache    限流器缓存
     * @param permitsPerSecond 每秒许可数
     * @param limiterName     限流器名称
     * @return 是否获取成功
     */
    private boolean tryAcquire(String key, Map<String, RateLimiter> limiterCache, int permitsPerSecond,
            String limiterName) {
        RateLimiter limiter = limiterCache.computeIfAbsent(limiterName, name -> {
            RateLimiterConfig config = RateLimiterConfig.custom()
                    .limitRefreshPeriod(Duration.ofSeconds(1))
                    .limitForPeriod(permitsPerSecond)
                    .timeoutDuration(Duration.ZERO)
                    .build();
            return RateLimiter.of(name, config);
        });

        try {
            return limiter.acquirePermission();
        } catch (RequestNotPermitted e) {
            log.warn("限流触发: key={}", key);
            return false;
        }
    }

    /**
     * 获取客户端 IP 地址
     *
     * @param request HTTP 请求
     * @return 客户端 IP
     */
    private String getClientIp(HttpServletRequest request) {
        // 如果启用了增强IP检测服务，使用新的检测逻辑
        if (ipDetectionServiceProvider.getIfAvailable() != null) {
            try {
                String enhancedIp = ipDetectionServiceProvider.getIfAvailable().getClientIp(request);
                log.debug("使用增强IP检测: {}", enhancedIp);
                return enhancedIp;
            } catch (Exception e) {
                log.warn("增强IP检测失败，回退到基础检测逻辑: {}", e.getMessage());
            }
        }
        
        // 回退到基础IP检测逻辑（原来的实现）
        return getClientIpFallback(request);
    }

    /**
     * 基础IP检测逻辑（原实现）
     */
    private String getClientIpFallback(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 获取用户 ID
     *
     * @param request HTTP 请求
     * @return 用户 ID，未登录时返回 null
     */
    private String getUserId(HttpServletRequest request) {
        // 尝试从请求属性获取用户ID（由认证拦截器设置）
        Object userId = request.getAttribute("userId");
        if (userId != null) {
            return userId.toString();
        }
        // 尝试从 header 获取
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // JWT token can be parsed to get userId
            // For simplicity, return null and fall back to IP limiting
            return null;
        }
        return null;
    }

    /**
     * 发送 429 Too Many Requests 响应
     *
     * @param response HTTP 响应
     * @param message  错误消息
     * @throws Exception 异常
     */
    private void sendTooManyRequestsResponse(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"" + message + "\"}");
    }
}