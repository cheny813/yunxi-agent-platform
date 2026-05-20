package io.yunxi.platform.controller;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.yunxi.platform.framework.tracing.TraceContext;

/**
 * MDC上下文传播测试Controller
 * 用于验证各类型线程中的MDC传播效果
 */
@RestController
@RequestMapping("/api/test/mdc")
public class MdcTestController {

    private static final Logger log = LoggerFactory.getLogger(MdcTestController.class);

    /**
     * 测试HTTP请求中的MDC传播
     */
    @GetMapping("/http-test")
    public String testHttpMdc() {
        log.info("=== HTTP请求MDC测试开始 ===");
        log.info("1. 主线程日志 - 检查MDC上下文");

        // 测试异步任务中的MDC传播
        testAsyncMdc();

        log.info("=== HTTP请求MDC测试结束 ===");
        return "HTTP请求MDC测试完成，请查看日志确认传播效果";
    }

    /**
     * 测试异步任务中的MDC传播
     */
    @Async("asyncTaskExecutor")
    public void testAsyncMdc() {
        log.info("2. AsyncExecutor线程日志 - 检查MDC上下文传播");

        // 在异步任务中再启动一个新的CompletableFuture
        CompletableFuture.supplyAsync(() -> {
            log.info("3. CompletableFuture线程日志 - 检查MDC上下文传播");
            return "测试完成";
        }).join();
    }

    /**
     * 固定定时任务：测试定时任务线程中的MDC传播
     * 每30秒执行一次
     */
    // @Scheduled(fixedRate = 30000)
    public void testScheduledMdc() {
        // 设置定时任务的MDC上下文
        MDC.clear();
        TraceContext.clear();

        // 设置线程ID到MDC上下文
        Thread currentThread = Thread.currentThread();
        String threadId = "scheduled-" + currentThread.getName() + "-" + currentThread.getId();
        MDC.put("threadId", threadId);

        // 设置定时任务的traceId
        String traceId = "scheduled-test-fixed-" + System.currentTimeMillis();
        TraceContext.setCurrentTraceId(traceId);
        MDC.put("traceId", traceId);

        log.info("=== 定时任务MDC测试 ===");
        log.info("定时任务线程日志检查MDC上下文传播");

        // 打印当前MDC上下文详细信息
        log.info("=== 定时任务MDC上下文 ===");
        log.info("ThreadId: {}", threadId);
        log.info("TraceId: {}", traceId);
    }

    /**
     * 获取当前MDC上下文状态
     */
    @GetMapping("/current-mdc")
    public String getCurrentMdc() {
        StringBuilder sb = new StringBuilder();
        sb.append("当前MDC上下文:\n");
        sb.append("线程信息: ").append(Thread.currentThread().getName())
                .append("-").append(Thread.currentThread().getId()).append("\n");
        sb.append("threadId: ").append(MDC.get("threadId")).append("\n");
        sb.append("traceId: ").append(MDC.get("traceId")).append("\n");

        return sb.toString();
    }
}