package io.yunxi.platform.framework.filter;

import io.yunxi.platform.framework.tracing.TraceContext;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;

/**
 * MDC上下文过滤器
 * 
 * 用于在HTTP请求开始时初始化MDC上下文，确保链路追踪信息的正确传播
 * 主要负责设置线程ID和辅助设置traceId
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1) // 最高优先级+1，确保在TraceIdFilter之前执行
public class MdcContextFilter implements Filter {

    private static final String THREAD_ID = "threadId";

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // 初始化逻辑
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        try {
            // 设置MDC上下文
            setupMdcContext(request);
            chain.doFilter(request, response);
        } finally {
            // 清理MDC上下文防止内存泄漏
            MDC.clear();
        }
    }

    @Override
    public void destroy() {
        // 清理逻辑
    }

    /**
     * 设置MDC上下文
     */
    private void setupMdcContext(ServletRequest request) {
        Thread currentThread = Thread.currentThread();
        String threadId = currentThread.getName() + "-" + currentThread.getId();
        
        // 设置线程ID
        MDC.put(THREAD_ID, threadId);
        
        // 设置traceId（如果未设置）
        // Note: TraceIdFilter将在稍后设置或覆写traceId
        if (MDC.get(TraceContext.TRACE_ID_MDC_KEY) == null) {
            String traceId = generateTraceId();
            MDC.put(TraceContext.TRACE_ID_MDC_KEY, traceId);
        }
    }

    /**
     * 生成traceId
     */
    private String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}