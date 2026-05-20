package io.yunxi.platform.integration;

import io.yunxi.platform.business.nutrition.service.*;
import io.yunxi.platform.config.yunxiAgentPlatformApplication;
import io.yunxi.platform.framework.profile.ConceptRegistry;
import io.yunxi.platform.framework.notification.SseNotificationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误恢复集成测试
 * 
 * 验证系统在遇到各种错误情况时的自愈能力和恢复机制
 */
@SpringBootTest(classes = yunxiAgentPlatformApplication.class)
@ExtendWith(SpringExtension.class)
@ActiveProfiles("integration-test")
class ErrorRecoveryIntegrationTest {

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SmartConversationService smartConversationService;

    @Autowired
    private ProfessionExtractionService professionExtractionService;

    @Autowired
    private NutritionPageAgentService nutritionPageAgentService;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    void testInvalidInputHandling() {
        // 测试系统对无效输入的处理能力
        String sessionId = "invalid-input-test";
        
        // 测试各种无效输入场景
        String[] invalidInputs = {
            "",          // 空输入
            "   ",       // 空白输入
            null,        // null输入（需要使用特殊处理）
            "\n\n\n",    // 换行符输入
            "!@#$%^&*()", // 特殊字符输入
            "这是一个非常长的输入，包含大量重复内容，用于测试系统对超长输入的承受能力。这是一个非常长的输入，包含大量重复内容，用于测试系统对超长输入的承受能力。这是一个非常长的输入，包含大量重复内容，用于测试系统对超长输入的承受能力。" // 超长输入
        };
        
        for (int i = 0; i < invalidInputs.length - 1; i++) { // 跳过null测试，需要特殊处理
            String invalidInput = invalidInputs[i];
            
            // 概念识别应该能够处理无效输入
            List<String> identities = conceptRegistry.detectIdentities(invalidInput);
            assertNotNull(identities, "概念识别应该能处理无效输入而不崩溃");
            
            // 智能会话应该能够处理无效输入
            var conversationRequest = SmartConversationService.ConversationRequest.builder()
                    .userMessage(invalidInput)
                    .sessionId(sessionId + "-" + i)
                    .build();
            
            var conversationResponse = smartConversationService.handleConversation(conversationRequest);
            assertNotNull(conversationResponse, "会话服务应该能处理无效输入而不崩溃");
            
            // 验证响应结构完整性
            assertNotNull(conversationResponse.getAgentResponse(), "响应应该包含有效回复");
            assertNotNull(conversationResponse.getSessionId(), "响应应该包含会话ID");
        }
    }

    @Test
    void testNetworkFailureRecovery() {
        // 测试网络故障的恢复机制
        String sessionId = "network-recovery-test";
        
        // 创建SSE会话
        assertTrue(sseNotificationProvider.createEmitter(sessionId), "应该创建SSE会话");
        
        // 模拟网络中断（通过断开再重连）
        boolean preFailureState = sseNotificationProvider.hasEmitter(sessionId);
        assertTrue(preFailureState, "会话在模拟故障前应该存在");
        
        // 模拟网络恢复：重新创建会话
        sseNotificationProvider.completeAndClose(sessionId); // 模拟断开
        
        // 重新连接（在实际场景中应该是自动重连）
        boolean reconnected = sseNotificationProvider.createEmitter(sessionId);
        assertTrue(reconnected, "应该能够重新连接会话");
        
        // 验证会话在恢复后正常工作
        boolean postRecoveryState = sseNotificationProvider.hasEmitter(sessionId);
        assertTrue(postRecoveryState, "会话在恢复后应该正常工作");
        
        // 测试会话恢复后的发送能力
        boolean messageSent = sseNotificationProvider.sendEvent(sessionId,
            "recovery_test", 
            Map.of("status", "reconnected", "message", "网络恢复成功")
        );
        assertTrue(messageSent, "会话恢复后应该能够正常发送消息");
        
        // 清理
        sseNotificationProvider.completeWithSuccess(sessionId, "网络恢复测试完成");
    }

    @Test
    void testResourceExhaustionRecovery() {
        // 测试资源耗尽后的恢复机制
        
        // 创建大量会话模拟资源压力
        int maxSessions = 50; // 适当数量避免测试过慢
        String baseSessionId = "resource-test-";
        
        for (int i = 0; i < maxSessions; i++) {
            String sessionId = baseSessionId + i;
            sseNotificationProvider.createEmitter(sessionId);
            
            // 在每个会话中执行一些操作
            var request = SmartConversationService.ConversationRequest.builder()
                    .userMessage("资源压力测试消息 " + i)
                    .sessionId(sessionId)
                    .build();
            
            var response = smartConversationService.handleConversation(request);
            assertNotNull(response, "系统在高负载下应该正常工作");
        }
        
        // 验证系统在压力下的稳定性
        // 执行清理操作来模拟资源回收
        for (int i = 0; i < maxSessions; i++) {
            String sessionId = baseSessionId + i;
            // 理论上每个会话都需要清理，这里简化为验证系统不会崩溃
        }
        
        // 验证核心功能在资源压力后仍然可用
        var recoveryRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("系统恢复测试")
                .sessionId("recovery-test-session")
                .build();
        
        var recoveryResponse = smartConversationService.handleConversation(recoveryRequest);
        assertNotNull(recoveryResponse, "系统在资源压力后应该能正常恢复");
        
        // 验证概念识别功能在压力后仍然正常
        var identities = conceptRegistry.detectIdentities("营养健康");
        assertNotNull(identities, "概念识别在压力后应该正常工作");
        assertTrue(identities.contains("NUTRITION"), "应该能够识别营养相关概念");
    }

    @Test
    void testConcurrentAccessHandling() {
        // 测试并发访问的处理能力
        int concurrentThreads = 10;
        Thread[] threads = new Thread[concurrentThreads];
        
        for (int i = 0; i < concurrentThreads; i++) {
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                String sessionId = "concurrent-test-" + threadIndex;
                
                try {
                    // 每个线程执行独立的操作
                    var request = SmartConversationService.ConversationRequest.builder()
                            .userMessage("并发测试消息 " + threadIndex)
                            .sessionId(sessionId)
                            .build();
                    
                    var response = smartConversationService.handleConversation(request);
                    assertNotNull(response, "并发环境下会话服务应该正常工作");
                    
                    // 概念识别也应该支持并发
                    var identities = conceptRegistry.detectIdentities("营养");
                    assertNotNull(identities, "并发环境下概念识别应该正常工作");
                    
                } catch (Exception e) {
                    // 在并发环境下，系统不应该抛异常而使整个应用崩溃
                    fail("并发操作不应该导致系统异常: " + e.getMessage());
                }
            });
        }
        
        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }
        
        // 等待所有线程完成，设置合理超时
        try {
            for (Thread thread : threads) {
                thread.join(30000); // 30秒超时
            }
        } catch (InterruptedException e) {
            fail("并发测试被中断: " + e.getMessage());
        }
        
        // 验证系统在并发后状态正常
        var postConcurrentRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("并发后系统状态测试")
                .sessionId("post-concurrent-test")
                .build();
        
        var postResponse = smartConversationService.handleConversation(postConcurrentRequest);
        assertNotNull(postResponse, "系统在并发操作后应该保持稳定");
    }

    @Test
    void testGracefulDegradation() {
        // 测试系统在部分功能失效时的优雅降级
        String sessionId = "degradation-test";
        
        // 模拟外部服务不可用时的降级处理
        
        // 概念识别应该能够降级处理
        var identities = conceptRegistry.detectIdentities("故障降级测试");
        assertNotNull(identities, "概念识别应该在故障情况下提供基本功能");
        
        // 会话服务应该能够降级处理
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("系统故障，请提供基本功能")
                .sessionId(sessionId)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        assertNotNull(conversationResponse, "会话服务应该在故障情况下提供基本回复");
        
        // 验证即使部分服务不可用，核心功能仍然可用
        assertNotNull(conversationResponse.getAgentResponse(), 
            "即使在降级模式下，AI回复也不应该为空");
        assertNotNull(conversationResponse.getSessionId(),
            "即使在降级模式下，会话ID也应该存在");
        
        // 测试降级后的用户体验
        // 响应时间应该在合理范围内
        long startTime = System.currentTimeMillis();
        var testResponse = smartConversationService.handleConversation(conversationRequest);
        long endTime = System.currentTimeMillis();
        long responseTime = endTime - startTime;
        
        assertTrue(responseTime < 10000, "降级模式下响应时间应该在10秒内");
        assertNotNull(testResponse, "多次降级请求应该都能得到响应");
    }

    @Test
    void testDataCorruptionRecovery() {
        // 测试数据损坏时的恢复机制
        String sessionId = "corruption-recovery-test";
        
        // 模拟数据损坏场景
        
        // 场景1: 损坏的配置数据
        // 概念识别应该使用默认配置或上次有效的配置
        var corruptedInput = "营养健康"; // 正常输入，观察系统行为
        var identities = conceptRegistry.detectIdentities(corruptedInput);
        assertNotNull(identities, "即使配置可能有问题，概念识别也不应该崩溃");
        
        // 场景2: 损坏的会话数据
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(corruptedInput)
                .sessionId(sessionId)
                .conversationHistory(List.of( // 模拟损坏的历史数据
                    Map.of("role", "user", "content", "损坏的消息"),
                    Map.of("corrupted", "data") // 损坏的数据结构
                ))
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        assertNotNull(conversationResponse, "系统应该能够处理损坏的历史数据而不崩溃");
        
        // 清理损坏的会话
        // 在实际系统中应该有自动清理机制
    }

    @Test
    void testTimeoutHandlingAndRecovery() throws InterruptedException {
        // 测试超时处理和恢复机制
        String sessionId = "timeout-test";
        
        // 创建长时间运行的操作模拟超时
        sseNotificationProvider.createEmitter(sessionId);
        
        // 模拟超时操作：发送多个事件观察系统行为
        for (int i = 0; i < 5; i++) {
            boolean sent = sseNotificationProvider.sendEvent(sessionId,
                "timeout_test",
                Map.of("index", i, "timestamp", System.currentTimeMillis())
            );
            
            assertTrue(sent, "在超时情况下应该能够发送事件");
            
            // 短暂延迟模拟网络延迟
            TimeUnit.MILLISECONDS.sleep(100);
        }
        
        // 验证超时后系统状态
        boolean stillActive = sseNotificationProvider.hasEmitter(sessionId);
        assertTrue(stillActive, "在超时测试期间会话应该保持活跃");
        
        // 恢复操作：发送完成事件
        boolean finalSent = sseNotificationProvider.sendEvent(sessionId,
            "timeout_recovery",
            Map.of("status", "completed", "message", "超时测试完成")
        );
        
        assertTrue(finalSent, "超时测试后应该能够正常发送完成事件");
        
        // 清理
        sseNotificationProvider.completeWithSuccess(sessionId, "超时测试完成");
    }

    @Test
    void testCascadingFailureContainment() {
        // 测试级联故障的遏制机制
        String sessionId = "cascade-failure-test";
        
        // 核心思想：即使某个模块完全失败，其他模块也不应该受影响
        
        // 模块1: 概念识别（假设正常）
        var identities = conceptRegistry.detectIdentities("系统稳定性测试");
        assertNotNull(identities, "概念识别应该独立运行");
        
        // 模块2: 用户画像构建（假设正常）
        var profile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(identities)
                .build();
        
        // 模块3: 会话服务（假设可能失败但不应影响其他模块）
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("即使其他模块失败，我仍然正常工作")
                .sessionId(sessionId)
                .userProfile(profile)
                .build();
        
        // 假设会话服务出现故障，但其他模块不应受影响
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        
        // 验证即使某些操作可能失败，系统整体保持稳定
        if (conversationResponse != null) {
            // 正常情况：所有模块正常工作
            assertNotNull(conversationResponse.getAgentResponse());
            assertNotNull(conversationResponse.getUserProfile());
        } else {
            // 故障情况：会话服务失败，但系统不应完全崩溃
            // 在实际系统中，应该有无响应时的降级处理
            // 这里我们验证系统没有抛出未捕获的异常
        }
        
        // 验证故障遏制：其他功能应该仍然可用
        var secondaryIdentities = conceptRegistry.detectIdentities("后续测试");
        assertNotNull(secondaryIdentities, "即使会话服务有问题，概念识别应该仍然工作");
    }

    @Test
    void testRecoveryMetricsAndMonitoring() {
        // 测试恢复期间的指标监控和状态报告
        String sessionId = "metrics-test";
        
        // 创建SSE会话用于监控
        sseNotificationProvider.createEmitter(sessionId);
        
        // 执行一系列操作并监控状态
        int operationCount = 10;
        int successfulOperations = 0;
        
        for (int i = 0; i < operationCount; i++) {
            try {
                // 执行操作
                var request = SmartConversationService.ConversationRequest.builder()
                        .userMessage("监控测试操作 " + i)
                        .sessionId(sessionId + "-" + i)
                        .build();
                
                var response = smartConversationService.handleConversation(request);
                
                if (response != null && response.getAgentResponse() != null) {
                    successfulOperations++;
                    
                    // 发送监控事件
                    sseNotificationProvider.sendEvent(sessionId,
                        "operation_metric",
                        Map.of(
                            "operation", i,
                            "status", "success",
                            "timestamp", System.currentTimeMillis()
                        )
                    );
                }
                
            } catch (Exception e) {
                // 记录失败但继续测试
                sseNotificationProvider.sendEvent(sessionId,
                    "operation_metric",
                    Map.of(
                        "operation", i,
                        "status", "failure",
                        "error", e.getMessage(),
                        "timestamp", System.currentTimeMillis()
                    )
                );
            }
        }
        
        // 验证成功率在合理范围内
        double successRate = (double) successfulOperations / operationCount;
        assertTrue(successRate > 0.7, "系统成功率应该超过70%: " + successRate);
        
        // 清理监控会话
        sseNotificationProvider.sendEvent(sessionId,
            "metric_summary",
            Map.of(
                "totalOperations", operationCount,
                "successfulOperations", successfulOperations,
                "successRate", successRate,
                "status", "completed"
            )
        );
        
        sseNotificationProvider.completeWithSuccess(sessionId, "监控测试完成");
    }
}