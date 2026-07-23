import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API性能监控和响应缓存工具
 * 用于记录API响应时间并提供缓存功能
 */
public class api-performance-monitor {
    
    private static final ConcurrentMap<String, ApiStats> apiStats = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CacheEntry> responseCache = new ConcurrentHashMap<>();
    private static final long CACHE_EXPIRY_MILLIS = 5 * 60 * 1000; // 5分钟缓存过期
    
    static class ApiStats {
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final AtomicLong maxResponseTime = new AtomicLong(0);
        private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
        
        public void recordResponse(long responseTime, boolean isError) {
            totalResponseTime.addAndGet(responseTime);
            requestCount.incrementAndGet();
            if (isError) {
                errorCount.incrementAndGet();
            }
            
            // 更新最大响应时间
            maxResponseTime.accumulateAndGet(responseTime, Math::max);
            
            // 更新最小响应时间
            minResponseTime.accumulateAndGet(responseTime, Math::min);
        }
        
        public String getStats() {
            int totalRequests = requestCount.get();
            if (totalRequests == 0) return "No requests recorded";
            
            double avgTime = (double) totalResponseTime.get() / totalRequests / 1_000_000.0;
            double errorRate = (double) errorCount.get() / totalRequests * 100;
            
            return String.format(
                "Requests: %d, Avg: %.2fms, Max: %.2fms, Min: %.2fms, Errors: %.1f%%",
                totalRequests, avgTime, 
                maxResponseTime.get() / 1_000_000.0,
                minResponseTime.get() / 1_000_000.0,
                errorRate
            );
        }
    }
    
    static class CacheEntry {
        private final Object data;
        private final long timestamp;
        
        CacheEntry(Object data) {
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MILLIS;
        }
        
        Object getData() {
            return data;
        }
    }
    
    /**
     * 记录API调用响应时间
     */
    public static void recordApiCall(String apiName, long startTime, boolean isError) {
        long responseTime = System.nanoTime() - startTime;
        apiStats.computeIfAbsent(apiName, k -> new ApiStats())
                .recordResponse(responseTime, isError);
    }
    
    /**
     * 缓存API响应
     */
    public static void cacheResponse(String cacheKey, Object response) {
        responseCache.put(cacheKey, new CacheEntry(response));
        
        // 清理过期缓存
        if (responseCache.size() > 1000) {
            responseCache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
    }
    
    /**
     * 获取缓存的响应
     */
    public static Object getCachedResponse(String cacheKey) {
        CacheEntry entry = responseCache.get(cacheKey);
        if (entry != null && !entry.isExpired()) {
            return entry.getData();
        }
        return null;
    }
    
    /**
     * 获取API性能报告
     */
    public static void printPerformanceReport() {
        System.out.println("🚀 API性能报告");
        System.out.println("=".repeat(80));
        
        if (apiStats.isEmpty()) {
            System.out.println("暂无API调用记录");
            return;
        }
        
        apiStats.entrySet().stream()
            .sorted((e1, e2) -> Long.compare(
                e2.getValue().requestCount.get(), 
                e1.getValue().requestCount.get()
            ))
            .forEach(entry -> {
                System.out.printf("%-40s %s\n", entry.getKey() + ":", entry.getValue().getStats());
            });
        
        System.out.println("\n💡 性能优化建议:");
        System.out.println("   • 响应时间 < 50ms: ✅ 优秀");
        System.out.println("   • 响应时间 50-200ms: ⚠️ 良好");
        System.out.println("   • 响应时间 > 200ms: 🔧 需要优化");
        System.out.println("   • 错误率 > 5%: ❌ 需要排查");
        
        System.out.println("\n📊 缓存统计:");
        System.out.printf("   缓存条目数: %d\n", responseCache.size());
        long activeCache = responseCache.values().stream()
            .filter(entry -> !entry.isExpired())
            .count();
        System.out.printf("   有效缓存数: %d\n", activeCache);
        
        System.out.println("=".repeat(80));
    }
    
    /**
     * 示例：模拟API调用
     */
    public static void main(String[] args) throws InterruptedException {
        // 模拟API调用
        simulateApiCalls();
        
        // 生成性能报告
        printPerformanceReport();
        
        // 演示缓存功能
        demoCacheFunction();
    }
    
    private static void simulateApiCalls() throws InterruptedException {
        String[] apis = {"/api/conversations", "/api/intent", "/api/knowledge", "/api/agents"};
        
        for (int i = 0; i < 100; i++) {
            String api = apis[i % apis.length];
            long startTime = System.nanoTime();
            
            // 模拟API处理时间
            Thread.sleep(ThreadLocalRandom.current().nextInt(10, 100));
            
            boolean isError = i % 20 == 0; // 5%错误率
            
            recordApiCall(api, startTime, isError);
        }
    }
    
    private static void demoCacheFunction() {
        System.out.println("\n🔧 缓存功能演示:");
        
        String cacheKey = "user_profile_123";
        String userData = "{\"name\": \"张三\", \"role\": \"admin\"}";
        
        // 缓存数据
        cacheResponse(cacheKey, userData);
        System.out.println("   缓存数据: " + userData);
        
        // 获取缓存数据
        Object cachedData = getCachedResponse(cacheKey);
        if (cachedData != null) {
            System.out.println("   从缓存获取: " + cachedData);
        }
        
        // 模拟缓存过期后的获取
        System.out.println("   模拟缓存过期测试...");
        // 实际使用中，缓存会在5分钟后自动过期
    }
}