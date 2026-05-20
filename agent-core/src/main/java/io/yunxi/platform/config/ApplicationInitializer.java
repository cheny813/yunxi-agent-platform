package io.yunxi.platform.config;

import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import io.yunxi.platform.framework.tracing.TraceContext;

/**
 * 应用程序初始化配置
 * 用于启动过程中设置系统级MDC上下文（线程ID和traceId），覆盖所有启动阶段的日志
 */
@Component
public class ApplicationInitializer implements ApplicationListener<ApplicationEvent>, Ordered {

    @Override
    public int getOrder() {
        // 高优先级，确保在日志系统配置完成后执行
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof ApplicationEnvironmentPreparedEvent) {
            // 在环境准备完成后设置MDC上下文，此时日志配置已经完成
            // 这样可以确保后续的启动日志都有MDC信息
            setupSystemMdcContext("main-env-");
        } else if (event instanceof ApplicationReadyEvent) {
            // 在应用完全启动完成后重新同步MDC上下文
            setupSystemMdcContext("main-ready-");

            // 打印启动日志以便验证
            System.out.println("=== 应用程序MDC上下文初始化完成 ===");
            System.out.println("主线程ID: " + Thread.currentThread().getName() + "-" + Thread.currentThread().getId());
            System.out.println("系统TraceID: " + TraceContext.getCurrentTraceId());
            System.out.println("==============================");
        }
    }

    /**
     * 设置系统级MDC上下文
     * 主要用于后台任务、定时任务等非HTTP请求场景
     */
    private void setupSystemMdcContext(String prefix) {
        // 清除可能的残留上下文
        MDC.clear();
        TraceContext.clear();

        // 设置线程ID到MDC上下文
        Thread currentThread = Thread.currentThread();
        String threadId = currentThread.getName() + "-" + currentThread.getId();
        MDC.put("threadId", threadId);

        // 使用项目中已有的TraceContext框架设置traceId
        String traceId = prefix + System.currentTimeMillis();
        TraceContext.setCurrentTraceId(traceId);

        // 确保MDC中也同步了TraceContext的设置（采用你项目使用的标准键名）
        MDC.put("traceId", TraceContext.getCurrentTraceId());

        // 验证设置是否成功
        System.out.println("[MDC设置] 线程: " + threadId + ", TraceId: " + traceId);

        // 注意：系统启动时的MDC上下文会在第一个HTTP请求后被MdcContextFilter覆盖
        // 这是期望的行为，确保HTTP请求有独立的上下文
    }
}