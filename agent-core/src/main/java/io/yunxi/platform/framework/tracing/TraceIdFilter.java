package io.yunxi.platform.framework.tracing;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * TraceId 过滤器
 * <p>
 * 为所有请求生成或提取 TraceId，并设置到 MDC 中。
 * 支持从请求头中提取上游传递的 TraceId，如果没有则生成新的。
 * </p>
 * <p>
 * 优先级设置为最高，确保在MDCContextFilter之后执行。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // 最高优先级，在MdcContextFilter之前执行
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 从请求头中提取 TraceId，不存在则生成新的
        String traceId = httpRequest.getHeader(TraceContext.TRACE_ID_HEADER);
        TraceContext.setTraceIdIfAbsent(traceId);

        // 获取实际使用的 TraceId
        String actualTraceId = TraceContext.getCurrentTraceId();

        try {
            // 在响应头中返回 TraceId
            httpResponse.setHeader(TraceContext.TRACE_ID_HEADER, actualTraceId);

            chain.doFilter(request, response);
        } finally {
            // 清理 MDC 防止内存泄漏
            TraceContext.clear();
        }
    }
}
