package io.yunxi.platform.integration;

import io.yunxi.platform.business.nutrition.service.SmartConversationService;
import io.yunxi.platform.config.yunxiAgentPlatformApplication;
import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.notification.SseNotificationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 负载和性能集成测试
 * 
 * 验证系统在高并发和大数据量情况下的性能表现
 */
@SpringBootTest(classes = yunxiAgentPlatformApplication.class)
@ExtendWith(SpringExtension.class)
@ActiveProfiles("integration-test")
class LoadAndPerformanceIntegrationTest {

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SmartConversationService smartConversationService;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testHighConcurrentConceptRecognition() {
        // 测试概念识别服务在高并发下的性能
        int concurrentUsers = 20;
        int requestsPerUser = 10;
        
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < concurrentUsers; i++) {
            final int userIndex = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < requestsPerUser; j++) {
                    String testText = String.format("用户%d查询%d: 营养健康测试", userIndex, j);
                    
                    try {
                        var identities = conceptRegistry.detectIdentities(testText);
                        assertNotNull(identities, "概念识别在高并发下不应返回null");
                        
                        // 验证响应时间在合理范围内
                        long requestStart = System.currentTimeMillis();
                        var response = conceptRegistry.detectIdentities(testText);
                        long requestEnd = System.currentTimeMillis();
                        
                        long responseTime = requestEnd - requestStart;
                        assertTrue(responseTime < 1000, 
                            "单个概念识别请求应该在1秒内完成，实际: " + responseTime + "ms");
                        
                    } catch (Exception e) {
                        fail("概念识别在高并发下不应抛出异常: " + e.getMessage());
                    }
                }
            }, executor);
            
            futures.add(future);
        }
        
        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;
        int totalRequests = concurrentUsers * requestsPerUser;
        
        // 验证总体性能
        double requestsPerSecond = (double) totalRequests / (totalDuration / 1000.0);
        
        System.out.println("并发概念识别测试结果:");
        System.out.println("总请求数: " + totalRequests);
        System.out.println("总时间: " + totalDuration + "ms");
        System.out.println("QPS: " + String.format("%.2f", requestsPerSecond));
        
        assertTrue(requestsPerSecond > 10, "概念识别QPS应该超过10，实际: " + requestsPerSecond);
        assertTrue(totalDuration < 30000, "总体测试时间应该小于30秒，实际: " + totalDuration + "ms");
        
        executor.shutdown();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testConversationServiceThroughput() {
        // 测试会话服务的吞吐量
        int users = 10;
        int messagesPerUser = 5;
        
        ExecutorService executor = Executors.newFixedThreadPool(users);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        List<Long> individualResponseTimes = new ArrayList<>();
        
        for (int i = 0; i < users; i++) {
            final int userIndex = i;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                for (int j = 0; j < messagesPerUser; j++) {
                    String sessionId = "load-test-session-" + userIndex + "-" + j;
                    String message = String.format("用户%d消息%d: 请帮我分析营养需求", userIndex, j);
                    
                    long requestStart = System.currentTimeMillis();
                    try {
                        var request = SmartConversationService.ConversationRequest.builder()
                                .userMessage(message)
                                .sessionId(sessionId)
                                .build();
                        
                        var response = smartConversationService.handleConversation(request);
                        
                        long requestEnd = System.currentTimeMillis();
                        long responseTime = requestEnd - requestStart;
                        individualResponseTimes.add(responseTime);
                        
                        assertNotNull(response, "会话服务在高负载下应该返回有效响应");
                        assertNotNull(response.getAgentResponse(), "AI回复不应该为空");
                        
                        // 验证响应时间合理性
                        assertTrue(responseTime < 15000, 
                            "单个会话请求应该在15秒内完成，实际: " + responseTime + "ms");
                        
                    } catch (Exception e) {
                        fail("会话服务在高负载下不应抛出异常: " + e.getMessage());
                    }
                }
            }, executor);
            
            futures.add(future);
        }
        
        // 等待所有任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;
        int totalMessages = users * messagesPerUser;
        
        // 计算性能指标
        double messagesPerSecond = (double) totalMessages / (totalDuration / 1000.0);
        
        // 统计响应时间
        double avgResponseTime = individualResponseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        
        long maxResponseTime = individualResponseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        
        System.out.println("\n会话服务吞吐量测试结果:");
        System.out.println("总消息数: " + totalMessages);
        System.out.println("总时间: " + totalDuration + "ms");
        System.out.println("平均QPS: " + String.format("%.2f", messagesPerSecond));
        System.out.println("平均响应时间: " + String.format("%.2f", avgResponseTime) + "ms");
        System.out.println("最大响应时间: " + maxResponseTime + "ms");
        
        // 验证性能要求
        assertTrue(messagesPerSecond > 1, "会话服务QPS应该超过1，实际: " + messagesPerSecond);
        assertTrue(avgResponseTime < 5000, 
            "平均响应时间应该小于5秒，实际: " + avgResponseTime + "ms");
        assertTrue(totalDuration < 120000, 
            "总体测试时间应该小于120秒，实际: " + totalDuration + "ms");
        
        executor.shutdown();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testSSEConnectionScalability() {
        // 测试SSE连接的扩展性
        int connectionCount = 50;
        List<String> sessionIds = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        // 创建大量SSE连接
        for (int i = 0; i < connectionCount; i++) {
            String sessionId = "sse-scalability-" + i;
            boolean created = sseNotificationProvider.createEmitter(sessionId);
            
            assertTrue(created, "应该能够创建SSE连接 " + i);
            sessionIds.add(sessionId);
            
            // 验证连接创建时间
            long connectionTime = System.currentTimeMillis() - startTime;
            if (i > 0) {
                assertTrue(connectionTime < 5000, 
                    "创建50个连接应该在5秒内完成，当前连接: " + i + "，时间: " + connectionTime + "ms");
            }
        }
        
        long createTime = System.currentTimeMillis() - startTime;
        
        // 验证所有连接都活跃
        for (String sessionId : sessionIds) {
            boolean active = sseNotificationProvider.hasEmitter(sessionId);
            assertTrue(active, "SSE连接应该保持活跃: " + sessionId);
        }
        
        // 测试性能：同时向所有连接发送消息
        long broadcastStart = System.currentTimeMillis();
        int successfulBroadcasts = 0;
        
        for (String sessionId : sessionIds) {
            boolean sent = sseNotificationProvider.sendEvent(sessionId,
                "scalability_test",
                "测试广播消息"
            );
            
            if (sent) successfulBroadcasts++;
        }
        
        long broadcastTime = System.currentTimeMillis() - broadcastStart;
        
        // 清理连接
        long cleanupStart = System.currentTimeMillis();
        for (String sessionId : sessionIds) {
            sseNotificationProvider.completeAndClose(sessionId);
        }
        long cleanupTime = System.currentTimeMillis() - cleanupStart;
        
        System.out.println("\nSSE连接扩展性测试结果:");
        System.out.println("连接创建时间: " + createTime + "ms");
        System.out.println("广播时间: " + broadcastTime + "ms");
        System.out.println("成功广播数: " + successfulBroadcasts + "/" + connectionCount);
        System.out.println("清理时间: " + cleanupTime + "ms");
        
        // 验证性能要求
        assertTrue(createTime < 10000, "创建连接时间应该小于10秒，实际: " + createTime + "ms");
        assertTrue(broadcastTime < 5000, "广播时间应该小于5秒，实际: " + broadcastTime + "ms");
        assertTrue(successfulBroadcasts >= connectionCount * 0.8, 
            "成功广播率应该超过80%，实际: " + successfulBroadcasts + "/" + connectionCount);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMemoryUsageUnderLoad() {
        // 测试系统在高负载下的内存使用情况
        Runtime runtime = Runtime.getRuntime();
        
        // 记录初始内存状态
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // 执行大量操作来观察内存增长
        int operationCount = 1000;
        List<String> largeData = new ArrayList<>();
        
        for (int i = 0; i < operationCount; i++) {
            String testText = "内存测试操作 " + i + " - " + 
                "这是一个相对较长的文本内容，用于测试内存使用模式";
            
            // 执行概念识别
            var identities = conceptRegistry.detectIdentities(testText);
            assertNotNull(identities);
            
            // 模拟数据积累（但注意实际内存管理）
            if (i % 100 == 0) {
                largeData.add("缓存数据块 " + i);
            }
        }
        
        // 强制垃圾回收来观察内存回收
        System.gc();
        
        // 记录最终内存状态
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;
        long maxMemory = runtime.maxMemory();
        
        double memoryIncreaseMB = memoryIncrease / (1024.0 * 1024.0);
        double maxMemoryMB = maxMemory / (1024.0 * 1024.0);
        
        System.out.println("\n内存使用测试结果:");
        System.out.println("初始内存: " + (initialMemory / (1024 * 1024)) + "MB");
        System.out.println("最终内存: " + (finalMemory / (1024 * 1024)) + "MB");
        System.out.println("内存增长: " + String.format("%.2f", memoryIncreaseMB) + "MB");
        System.out.println("最大内存: " + String.format("%.2f", maxMemoryMB) + "MB");
        
        // 验证内存使用合理性
        assertTrue(memoryIncreaseMB < 100, 
            "内存增长应该小于100MB，实际: " + memoryIncreaseMB + "MB");
        
        // 验证内存使用率
        double memoryUsageRatio = (double) finalMemory / maxMemory;
        assertTrue(memoryUsageRatio < 0.8, 
            "内存使用率应该小于80%，实际: " + (memoryUsageRatio * 100) + "%");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void testMixedWorkloadPerformance() {
        // 测试混合工作负载下的综合性能
        int conceptOperations = 500;
        int conversationOperations = 100;
        int sseOperations = 50;
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        long startTime = System.currentTimeMillis();
        
        // 概念识别操作
        for (int i = 0; i < conceptOperations; i++) {
            final int opIndex = i;
            futures.add(CompletableFuture.runAsync(() -> {
                String text = "混合负载概念测试 " + opIndex;
                var identities = conceptRegistry.detectIdentities(text);
                assertNotNull(identities);
            }, executor));
        }
        
        // 会话操作
        for (int i = 0; i < conversationOperations; i++) {
            final int opIndex = i;
            futures.add(CompletableFuture.runAsync(() -> {
                String sessionId = "mixed-workload-conv-" + opIndex;
                var request = SmartConversationService.ConversationRequest.builder()
                        .userMessage("混合负载会话测试 " + opIndex)
                        .sessionId(sessionId)
                        .build();
                
                var response = smartConversationService.handleConversation(request);
                assertNotNull(response);
            }, executor));
        }
        
        // SSE操作
        for (int i = 0; i < sseOperations; i++) {
            final int opIndex = i;
            futures.add(CompletableFuture.runAsync(() -> {
                String sessionId = "mixed-workload-sse-" + opIndex;
                if (sseNotificationProvider.createEmitter(sessionId)) {
                    sseNotificationProvider.sendEvent(sessionId, "test", "混合负载SSE测试");
                    sseNotificationProvider.completeAndClose(sessionId);
                }
            }, executor));
        }
        
        // 等待所有操作完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        long endTime = System.currentTimeMillis();
        long totalDuration = endTime - startTime;
        int totalOperations = conceptOperations + conversationOperations + sseOperations;
        
        double operationsPerSecond = (double) totalOperations / (totalDuration / 1000.0);
        
        System.out.println("\n混合工作负载测试结果:");
        System.out.println("总操作数: " + totalOperations);
        System.out.println("概念识别: " + conceptOperations);
        System.out.println("会话操作: " + conversationOperations);
        System.out.println("SSE操作: " + sseOperations);
        System.out.println("总时间: " + totalDuration + "ms");
        System.out.println("综合QPS: " + String.format("%.2f", operationsPerSecond));
        
        // 验证综合性能
        assertTrue(operationsPerSecond > 5, 
            "混合负载QPS应该超过5，实际: " + operationsPerSecond);
        assertTrue(totalDuration < 60000, 
            "混合负载测试时间应该小于60秒，实际: " + totalDuration + "ms");
        
        executor.shutdown();
    }

    @Test
    void testResponseTimeConsistency() {
        // 测试系统响应时间的一致性
        int sampleCount = 20;
        List<Long> responseTimes = new ArrayList<>();
        
        // 多次执行相同的操作来观察响应时间波动
        for (int i = 0; i < sampleCount; i++) {
            long startTime = System.nanoTime();
            
            var identities = conceptRegistry.detectIdentities("一致性测试");
            assertNotNull(identities);
            
            long endTime = System.nanoTime();
            long responseTime = (endTime - startTime) / 1000000; // 转换为毫秒
            responseTimes.add(responseTime);
            
            // 验证每次响应都在合理时间内
            assertTrue(responseTime < 1000, 
                "响应时间应该小于1秒，实际: " + responseTime + "ms");
        }
        
        // 计算响应时间的统计指标
        double avgResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
        
        double maxResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L);
        
        double minResponseTime = responseTimes.stream()
                .mapToLong(Long::longValue)
                .min()
                .orElse(0L);
        
        // 计算标准差
        double variance = responseTimes.stream()
                .mapToDouble(time -> Math.pow(time - avgResponseTime, 2))
                .average()
                .orElse(0.0);
        double stdDev = Math.sqrt(variance);
        
        double coefficientOfVariation = (stdDev / avgResponseTime) * 100;
        
        System.out.println("\n响应时间一致性测试结果:");
        System.out.println("样本数量: " + sampleCount);
        System.out.println("平均响应时间: " + String.format("%.2f", avgResponseTime) + "ms");
        System.out.println("最小响应时间: " + minResponseTime + "ms");
        System.out.println("最大响应时间: " + maxResponseTime + "ms");
        System.out.println("标准差: " + String.format("%.2f", stdDev) + "ms");
        System.out.println("变异系数: " + String.format("%.2f", coefficientOfVariation) + "%");
        
        // 验证响应时间的一致性
        assertTrue(coefficientOfVariation < 50, 
            "响应时间变异系数应该小于50%，实际: " + coefficientOfVariation + "%");
        assertTrue(maxResponseTime - minResponseTime < 500, 
            "响应时间波动范围应该小于500ms，实际: " + (maxResponseTime - minResponseTime) + "ms");
    }
}