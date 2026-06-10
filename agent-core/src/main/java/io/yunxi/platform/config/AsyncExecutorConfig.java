package io.yunxi.platform.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;

/**
 * 异步执行器配置
 * <p>配置用于后台审查、摘要生成等异步任务的线程池。</p>
 *
 * @author yunxi-agent-platform
 */
@Data
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    @Value("${yunxi.learning-loop.async-thread-pool-size:10}")
    private int threadPoolSize;

    @Value("${yunxi.learning-loop.async-timeout-seconds:60}")
    private int timeoutSeconds;

    @Bean(name = "asyncExecutor")
    @org.springframework.context.annotation.Primary
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(2, threadPoolSize / 2));
        executor.setMaxPoolSize(threadPoolSize);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("AsyncExecutor-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Bean
    public TimeoutHandler timeoutHandler() {
        return new TimeoutHandler(timeoutSeconds);
    }

    /** 超时处理器 */
    @Data
    public static class TimeoutHandler {
        private final long timeoutMillis;

        public TimeoutHandler(int timeoutSeconds) { this.timeoutMillis = timeoutSeconds * 1000L; }

        public <T> T executeWithTimeout(Callable<T> task) throws TimeoutException {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<T> future = executor.submit(task);
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) { throw new TimeoutException("任务执行超时: " + timeoutMillis + "ms");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException("任务被中断", e);
            } catch (ExecutionException e) { throw new RuntimeException("任务执行失败", e.getCause());
            } finally { executor.shutdown(); }
        }
    }
}
