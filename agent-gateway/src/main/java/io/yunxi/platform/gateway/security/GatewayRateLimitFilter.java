package io.yunxi.platform.gateway.security;

import io.yunxi.platform.gateway.GatewayProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 网关并发限流过滤器
 * <p>
 * 使用 {@link Semaphore} 实现 /api/gateway/webapi/chat 端点的并发控制，
 * 替代原有未生效的 {@code maxConcurrentRequests} 配置。
 * </p>
 * <p>
 * 配置项：{@code gateway.concurrency.max-concurrent-requests}（默认 10）
 * </p>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GatewayRateLimitFilter implements Filter {

    private final Semaphore semaphore;

    public GatewayRateLimitFilter(GatewayProperties properties) {
        int maxConcurrent = properties.getConcurrency().getMaxConcurrentRequests();
        this.semaphore = new Semaphore(maxConcurrent, true);
        log.info("[Gateway] 限流过滤器初始化，最大并发数: {}", maxConcurrent);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String requestPath = httpRequest.getRequestURI();

        // 仅对 WebAPI chat 端点限流（核心写入路径）
        if (!requestPath.equals("/api/gateway/webapi/chat")) {
            chain.doFilter(request, response);
            return;
        }

        boolean acquired = false;
        try {
            acquired = semaphore.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[Gateway] 请求被限流，当前并发数已满，路径: {}", requestPath);
                writeRateLimitResponse(response);
                return;
            }
            log.debug("[Gateway] 获取并发许可，剩余: {}", semaphore.availablePermits());
            chain.doFilter(request, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Gateway] 等待并发许可被中断");
            writeRateLimitResponse(response);
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private void writeRateLimitResponse(ServletResponse response) throws IOException {
        jakarta.servlet.http.HttpServletResponse httpResponse =
                (jakarta.servlet.http.HttpServletResponse) response;
        httpResponse.setStatus(429); // SC_TOO_MANY_REQUESTS
        httpResponse.setContentType("application/json;charset=UTF-8");
        httpResponse.getWriter().write("{\"error\":\"Too Many Requests\",\"message\":\"Gateway concurrent limit reached. Please retry later.\"}");
    }
}
