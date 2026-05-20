package io.yunxi.platform.gateway.tracing;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * 网关 TraceId 过滤器
 * <p>
 * 为所有经过网关的请求生成或提取 TraceId，并设置到 MDC 中。
 * 支持从请求头中提取上游传递的 TraceId，如果没有则生成新的。
 * </p>
 * <p>
 * 优先级设置为最高，确保在其他过滤器之前执行。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GatewayTraceFilter implements Filter {

    /**
     * TraceId HTTP 头名称
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /**
     * MDC 中 TraceId 的键名
     */
    public static final String TRACE_ID_MDC_KEY = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 从请求头中提取 TraceId，不存在则生成新的
        String traceId = httpRequest.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }

        // 设置到 MDC
        MDC.put(TRACE_ID_MDC_KEY, traceId);

        try {
            // 在响应头中返回 TraceId
            httpResponse.setHeader(TRACE_ID_HEADER, traceId);

            chain.doFilter(request, response);
        } finally {
            // 清理 MDC 防止内存泄漏
            MDC.remove(TRACE_ID_MDC_KEY);
        }
    }

    /**
     * 生成新的 TraceId
     *
     * @return 新的 TraceId（UUID 格式，去除横线）
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
