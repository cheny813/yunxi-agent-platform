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

    /**
     * 创建异步任务执行线程池 Bean。
     *
     * @return 配置好的线程池执行器，用于后台审查、摘要生成等异步任务
     */
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

    /**
     * 创建超时处理器 Bean。
     *
     * @return 基于 {@code timeoutSeconds}（乘以 1000 转为毫秒）构造的超时处理器
     */
    @Bean
    public TimeoutHandler timeoutHandler() {
        return new TimeoutHandler(timeoutSeconds);
    }

    /**
     * 超时处理器，在独立线程中执行任务并在超时后抛出异常。
     */
    @Data
    public static class TimeoutHandler {
        private final long timeoutMillis;

        /**
         * 构造超时处理器。
         *
         * @param timeoutSeconds 超时秒数，将转换为毫秒作为执行上限
         */
        public TimeoutHandler(int timeoutSeconds) { this.timeoutMillis = timeoutSeconds * 1000L; }

        /**
         * 在独立线程中执行任务，超过 {@code timeoutMillis} 毫秒未返回则抛出超时异常。
         *
         * @param task 待执行的任务
         * @param <T>  任务返回类型
         * @return 任务执行结果
         * @throws TimeoutException 任务执行超过设定超时时间
         */
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
