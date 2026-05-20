package io.yunxi.platform.framework.async;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;

/**
 * 异步执行器配置
 * <p>
 * 配置用于后台审查、摘要生成等异步任务的线程池
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Data
@Configuration
@EnableAsync
public class AsyncExecutorConfig {

    /** 异步线程池大小 */
    @Value("${yunxi.learning-loop.async-thread-pool-size:10}")
    private int threadPoolSize;

    /** 异步任务超时时间（秒） */
    @Value("${yunxi.learning-loop.async-timeout-seconds:60}")
    private int timeoutSeconds;

    /** 审查批处理大小 */
    @Value("${yunxi.learning-loop.review-batch-size:5}")
    private int reviewBatchSize;

    /**
     * 配置异步执行器线程池
     *
     * @return ExecutorService
     */
    @Bean(name = "asyncExecutor")
    @org.springframework.context.annotation.Primary
    public Executor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数
        executor.setCorePoolSize(Math.max(2, threadPoolSize / 2));

        // 最大线程数
        executor.setMaxPoolSize(threadPoolSize);

        // 队列容量
        executor.setQueueCapacity(100);

        // 线程名称前缀
        executor.setThreadNamePrefix("AsyncExecutor-");

        // 拒绝策略：由调用线程执行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // MDC上下文传播装饰器，确保异步线程能正确显示链路追踪信息
        executor.setTaskDecorator(new MdcAwareTaskDecorator());

        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间
        executor.setAwaitTerminationSeconds(60);

        // 初始化
        executor.initialize();

        return executor;
    }

    /**
     * 配置异步任务超时处理器
     *
     * @return TimeoutHandler
     */
    @Bean
    public TimeoutHandler timeoutHandler() {
        return new TimeoutHandler(timeoutSeconds);
    }

    /**
     * 超时处理器
     */
    @Data
    public static class TimeoutHandler {
        private final long timeoutMillis;

        public TimeoutHandler(int timeoutSeconds) {
            this.timeoutMillis = timeoutSeconds * 1000L;
        }

        /**
         * 执行带超时的任务
         *
         * @param task 要执行的任务
         * @param <T>  返回类型
         * @return 任务结果
         * @throws TimeoutException 超时异常
         */
        public <T> T executeWithTimeout(Callable<T> task) throws TimeoutException {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<T> future = null;
            try {
                future = executor.submit(task);
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                throw new TimeoutException("任务执行超时: " + timeoutMillis + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("任务被中断", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("任务执行失败", e.getCause());
            } finally {
                executor.shutdown();
            }
        }
    }

    /**
     * 获取线程池统计信息
     *
     * @param executor 线程池
     * @return 统计信息
     */
    public ThreadPoolStats getStats(ExecutorService executor) {
        if (executor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            ThreadPoolExecutor threadPool = taskExecutor.getThreadPoolExecutor();

            ThreadPoolStats stats = new ThreadPoolStats();
            stats.setCorePoolSize(threadPool.getCorePoolSize());
            stats.setMaxPoolSize(threadPool.getMaximumPoolSize());
            stats.setActiveCount(threadPool.getActiveCount());
            stats.setPoolSize(threadPool.getPoolSize());
            stats.setQueueSize(threadPool.getQueue().size());
            stats.setCompletedTaskCount(threadPool.getCompletedTaskCount());
            stats.setTaskCount(threadPool.getTaskCount());
            stats.setLargestPoolSize(threadPool.getLargestPoolSize());

            return stats;
        }
        return null;
    }

    /**
     * 线程池统计
     */
    public static class ThreadPoolStats {
        private int corePoolSize;
        private int maxPoolSize;
        private int activeCount;
        private int poolSize;
        private int queueSize;
        private long completedTaskCount;
        private long taskCount;
        private int largestPoolSize;

        // Getter methods
        public int getCorePoolSize() {
            return corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getPoolSize() {
            return poolSize;
        }

        public int getQueueSize() {
            return queueSize;
        }

        public long getCompletedTaskCount() {
            return completedTaskCount;
        }

        public long getTaskCount() {
            return taskCount;
        }

        public int getLargestPoolSize() {
            return largestPoolSize;
        }

        // Setter methods
        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public void setActiveCount(int activeCount) {
            this.activeCount = activeCount;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public void setQueueSize(int queueSize) {
            this.queueSize = queueSize;
        }

        public void setCompletedTaskCount(long completedTaskCount) {
            this.completedTaskCount = completedTaskCount;
        }

        public void setTaskCount(long taskCount) {
            this.taskCount = taskCount;
        }

        public void setLargestPoolSize(int largestPoolSize) {
            this.largestPoolSize = largestPoolSize;
        }

        public String toString() {
            return String.format(
                    "ThreadPool[core=%d, max=%d, active=%d, pool=%d, queue=%d, completed=%d, task=%d, largest=%d]",
                    corePoolSize, maxPoolSize, activeCount, poolSize,
                    queueSize, completedTaskCount, taskCount, largestPoolSize);
        }
    }
}
