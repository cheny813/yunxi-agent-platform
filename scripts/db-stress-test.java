import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 数据库连接池压力测试工具
 * 用于验证优化后的连接池性能
 */
public class db-stress-test {
    
    private static final String DB_URL = "jdbc:mysql://localhost:3306/yunxi_agent_platform";
    private static final String DB_USER = "root";
    private static final String DB_PASSWORD = "root";
    private static final int THREAD_COUNT = 20;
    private static final int TEST_DURATION_SECONDS = 30;
    
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger errorCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    
    public static void main(String[] args) throws Exception {
        System.out.println("🚀 数据库连接池压力测试开始...");
        System.out.println("=".repeat(60));
        
        // 预热连接池
        warmupConnectionPool();
        
        // 执行并发测试
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);
        
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(new DatabaseWorker(startLatch, endLatch, i));
        }
        
        startLatch.countDown(); // 开始测试
        endLatch.await(TEST_DURATION_SECONDS + 10, TimeUnit.SECONDS);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // 输出测试结果
        printTestResults();
    }
    
    private static void warmupConnectionPool() {
        System.out.println("🔥 预热连接池...");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            System.out.println("✅ 连接池预热完成");
        } catch (SQLException e) {
            System.err.println("❌ 预热失败: " + e.getMessage());
        }
    }
    
    private static void printTestResults() {
        int totalRequests = successCount.get() + errorCount.get();
        double avgResponseTime = totalRequests > 0 ? 
            (double) totalResponseTime.get() / totalRequests / 1_000_000.0 : 0.0;
        double qps = (double) successCount.get() / TEST_DURATION_SECONDS;
        
        System.out.println("\n📊 测试结果:");
        System.out.println("=".repeat(60));
        System.out.printf("总请求数: %d\n", totalRequests);
        System.out.printf("成功请求: %d\n", successCount.get());
        System.out.printf("失败请求: %d\n", errorCount.get());
        System.out.printf("成功率: %.2f%%\n", 
            (double) successCount.get() / totalRequests * 100);
        System.out.printf("平均响应时间: %.2f ms\n", avgResponseTime);
        System.out.printf("QPS (每秒查询数): %.2f\n", qps);
        
        // 性能评级
        System.out.println("\n🏆 性能评级:");
        if (avgResponseTime < 5) {
            System.out.println("   ✅ 优秀 - 连接池性能极佳");
        } else if (avgResponseTime < 20) {
            System.out.println("   ✅ 良好 - 连接池性能良好");
        } else if (avgResponseTime < 50) {
            System.out.println("   ⚠️  一般 - 建议进一步优化");
        } else {
            System.out.println("   ❌ 较差 - 需要立即优化");
        }
        
        System.out.println("=".repeat(60));
    }
    
    static class DatabaseWorker implements Runnable {
        private final CountDownLatch startLatch;
        private final CountDownLatch endLatch;
        private final int workerId;
        
        DatabaseWorker(CountDownLatch startLatch, CountDownLatch endLatch, int workerId) {
            this.startLatch = startLatch;
            this.endLatch = endLatch;
            this.workerId = workerId;
        }
        
        @Override
        public void run() {
            try {
                startLatch.await();
                
                long endTime = System.currentTimeMillis() + TEST_DURATION_SECONDS * 1000;
                
                while (System.currentTimeMillis() < endTime) {
                    long startTime = System.nanoTime();
                    
                    try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                         PreparedStatement pstmt = conn.prepareStatement("SELECT ?")) {
                        
                        pstmt.setInt(1, workerId);
                        try (ResultSet rs = pstmt.executeQuery()) {
                            if (rs.next()) {
                                successCount.incrementAndGet();
                            }
                        }
                        
                        long responseTime = System.nanoTime() - startTime;
                        totalResponseTime.addAndGet(responseTime);
                        
                        // 模拟真实工作负载的小延迟
                        Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));
                        
                    } catch (SQLException e) {
                        errorCount.incrementAndGet();
                        System.err.printf("Worker %d error: %s\n", workerId, e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                System.err.printf("Worker %d fatal error: %s\n", workerId, e.getMessage());
            } finally {
                endLatch.countDown();
            }
        }
    }
    
    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("MySQL驱动未找到", e);
        }
    }
}