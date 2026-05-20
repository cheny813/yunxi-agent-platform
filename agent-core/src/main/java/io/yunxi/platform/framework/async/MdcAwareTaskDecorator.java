package io.yunxi.platform.framework.async;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * MDC感知任务装饰器
 * 
 * 用于异步执行时传播MDC上下文，确保异步线程能够正确显示链路追踪信息
 */
public class MdcAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 获取当前线程的MDC上下文副本
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        
        return () -> {
            try {
                // 在异步线程中设置MDC上下文
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                
                // 确保异步线程也有自己独立的threadId
                String threadId = Thread.currentThread().getName() + "-" + Thread.currentThread().getId();
                MDC.put("threadId", threadId);
                
                // 执行原始任务
                runnable.run();
            } finally {
                // 清理MDC上下文防止内存泄漏
                MDC.clear();
            }
        };
    }
}