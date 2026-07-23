import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 性能压力测试工具
 * 用于验证API性能优化效果
 */
public class performance-test {
    
    private static final int NUM_THREADS = 50;
    private static final int REQUESTS_PER_THREAD = 100;
    private static final ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
    
    private static final AtomicInteger successfulRequests = new AtomicInteger(0);
    private static final AtomicInteger failedRequests = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final AtomicLong maxResponseTime = new AtomicLong(0);
    private static final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("🚀 开始性能压力测试");
        System.out.println("配置: " + NUM_THREADS + " 线程 * " + REQUESTS_PER_THREAD + " 请求 = " + 
                          (NUM_THREADS * REQUESTS_PER_THREAD) + " 总请求数");
        System.out.println("=".repeat(80));
        
        // 创建并提交测试任务
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(NUM_THREADS * REQUESTS_PER_THREAD);
        
        for (int i = 0; i < NUM_THREADS; i++) {
            executor.submit(new TestWorker(i, startLatch, endLatch));
        }
        
        // 等待所有线程准备就绪
        Thread.sleep(2000);
        
        // 开始测试
        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        
        // 等待所有任务完成
        endLatch.await();
        long endTime = System.currentTimeMillis();
        
        // 输出测试结果
        printTestResults(startTime, endTime);
        
        // 关闭线程池
        executor.shutdown();
    }
    
    static class TestWorker implements Runnable {
        private final int workerId;
        private final CountDownLatch startLatch;
        private final CountDownLatch endLatch;
        
        TestWorker(int workerId, CountDownLatch startLatch, CountDownLatch endLatch) {
            this.workerId = workerId;
            this.startLatch = startLatch;
            this.endLatch = endLatch;
        }
        
        @Override
        public void run() {
            try {
                // 等待开始信号
                startLatch.await();
                
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    long requestStartTime = System.currentTimeMillis();
                    
                    try {
                        // 模拟API调用
                        simulateApiCall();
                        
                        long responseTime = System.currentTimeMillis() - requestStartTime;
                        
                        // 更新统计数据
                        successfulRequests.incrementAndGet();
                        totalResponseTime.addAndGet(responseTime);
                        maxResponseTime.accumulateAndGet(responseTime, Math::max);
                        minResponseTime.accumulateAndGet(responseTime, Math::min);
                        
                    } catch (Exception e) {
                        failedRequests.incrementAndGet();
                        System.err.println("Worker " + workerId + " 请求失败: " + e.getMessage());
                    } finally {
                        endLatch.countDown();
                    }
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        /**
         * 模拟API调用
         */
        private void simulateApiCall() throws InterruptedException {
            // 模拟API处理时间 (10-100ms随机)
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 101));
            
            // 模拟偶尔的网络延迟 (5%概率)
            if (ThreadLocalRandom.current().nextInt(100) < 5) {
                Thread.sleep(50); // 额外50ms延迟
            }
            
            // 模拟偶尔的错误 (2%概率)
            if (ThreadLocalRandom.current().nextInt(100) < 2) {
                throw new RuntimeException("模拟API错误");
            }
        }
    }
    
    /**
     * 输出测试结果
     */
    private static void printTestResults(long startTime, long endTime) {
        long totalTime = endTime - startTime;
        int totalRequests = successfulRequests.get() + failedRequests.get();
        double qps = (double) totalRequests / (totalTime / 1000.0);
        
        System.out.println("📊 性能测试结果");
        System.out.println("=".repeat(80));
        System.out.printf("总测试时间: %d 毫秒\n", totalTime);
        System.out.printf("总请求数: %d\n", totalRequests);
        System.out.printf("QPS (每秒请求数): %.2f\n", qps);
        System.out.printf("成功请求: %d\n", successfulRequests.get());
        System.out.printf("失败请求: %d\n", failedRequests.get());
        
        if (successfulRequests.get() > 0) {
            double errorRate = (double) failedRequests.get() / totalRequests * 100;
            double avgResponseTime = (double) totalResponseTime.get() / successfulRequests.get();
            
            System.out.printf("错误率: %.2f%%\n", errorRate);
            System.out.printf("平均响应时间: %.2f 毫秒\n", avgResponseTime);
            System.out.printf("最大响应时间: %d 毫秒\n", maxResponseTime.get());
            System.out.printf("最小响应时间: %d 毫秒\n", minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get());
        }
        
        System.out.println("\n💡 性能评估:");
        if (qps > 1000) {
            System.out.println("   • QPS > 1000: 🚀 性能卓越");
        } else if (qps > 500) {
            System.out.println("   • QPS > 500: ✅ 性能良好");
        } else if (qps > 200) {
            System.out.println("   • QPS > 200: ⚠️  性能一般");
        } else {
            System.out.println("   • QPS <= 200: 🔧 需要优化");
        }
        
        System.out.println("\n📈 优化建议:");
        if (maxResponseTime.get() > 1000) {
            System.out.println("   • 存在响应时间>1秒的请求，需要检查慢查询");
        }
        if (failedRequests.get() > totalRequests * 0.05) {
            System.out.println("   • 错误率超过5%，需要排查错误原因");
        }
        if (qps < 200) {
            System.out.println("   • QPS较低，建议优化数据库查询和缓存策略");
            System.out.println("   • 考虑启用HTTP/2和连接复用");
            System.out.println("   • 检查JVM内存和GC配置");
        }
        
        System.out.println("=".repeat(80));
    }
    
    /**
     * 数据库连接池测试
     */
    public static void testDatabasePoolPerformance() {
        System.out.println("\n🔍 数据库连接池压力测试");
        
        // 模拟高并发数据库访问
        int dbThreads = 20;
        int dbRequestsPerThread = 50;
        ExecutorService dbExecutor = Executors.newFixedThreadPool(dbThreads);
        
        CountDownLatch dbLatch = new CountDownLatch(dbThreads * dbRequestsPerThread);
        AtomicInteger dbSuccessCount = new AtomicInteger(0);
        AtomicLong dbTotalTime = new AtomicLong(0);
        
        for (int i = 0; i < dbThreads; i++) {
            dbExecutor.submit(() -> {
                for (int j = 0; j < dbRequestsPerThread; j++) {
                    long start = System.currentTimeMillis();
                    
                    try {
                        // 模拟数据库查询
                        Thread.sleep(ThreadLocalRandom.current().nextInt(5, 20));
                        dbSuccessCount.incrementAndGet();
                        dbTotalTime.addAndGet(System.currentTimeMillis() - start);
                    } catch (Exception e) {
                        System.err.println("数据库测试错误: " + e.getMessage());
                    } finally {
                        dbLatch.countDown();
                    }
                }
            });
        }
        
        try {
            dbLatch.await();
            
            if (dbSuccessCount.get() > 0) {
                double avgDbTime = (double) dbTotalTime.get() / dbSuccessCount.get();
                System.out.printf("   数据库查询测试: %d 成功, 平均时间: %.2f 毫秒\n", 
                    dbSuccessCount.get(), avgDbTime);
                
                if (avgDbTime > 50) {
                    System.out.println("   ⚠️  数据库查询时间较长，建议优化配置");
                }
            }
            
            dbExecutor.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 内存压力测试
     */
    public static void testMemoryPerformance() {
        System.out.println("\n💾 内存使用情况测试");
        
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory() / (1024 * 1024);
        long totalMemory = runtime.totalMemory() / (1024 * 1024);
        long freeMemory = runtime.freeMemory() / (1024 * 1024);
        long usedMemory = totalMemory - freeMemory;
        
        System.out.printf("   最大内存: %d MB\n", maxMemory);
        System.out.printf("   总内存: %d MB\n", totalMemory);
        System.out.printf("   已使用内存: %d MB\n", usedMemory);
        System.out.printf("   空闲内存: %d MB\n", freeMemory);
        
        double memoryUsage = (double) usedMemory / totalMemory * 100;
        System.out.printf("   内存使用率: %.2f%%\n", memoryUsage);
        
        if (memoryUsage > 80) {
            System.out.println("   ⚠️  内存使用率较高，建议增加JVM堆内存");
        }
        
        // 模拟内存压力测试
        try {
            List<byte[]> memoryBlocks = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                // 分配100MB内存
                memoryBlocks.add(new byte[100 * 1024 * 1024]);
                Thread.sleep(100);
                
                long currentUsage = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                System.out.printf("   内存分配后使用: %d MB\n", currentUsage);
            }
            
            // 清理内存
            memoryBlocks.clear();
            System.gc();
            Thread.sleep(1000);
            
            long afterGc = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            System.out.printf("   GC后内存使用: %d MB\n", afterGc);
            
        } catch (Exception e) {
            System.err.println("内存测试错误: " + e.getMessage());
        }
    }
}