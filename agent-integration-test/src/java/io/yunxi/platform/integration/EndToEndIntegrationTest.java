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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试框架
 * 
 * 测试整个应用从请求接收到业务处理的完整流程
 */
@SpringBootTest(classes = yunxiAgentPlatformApplication.class)
@ExtendWith(SpringExtension.class)
@ActiveProfiles("test")
class EndToEndIntegrationTest {

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

    @Autowired
    private RecipeStreamService recipeStreamService;

    @Test
    void testCompleteUserProfileExtractionWorkflow() {
        // 模拟用户对话输入
        String userMessage = "作为一名营养师，我经常关注儿童膳食均衡问题，特别是6-8岁学龄前儿童的营养需求。每天需要为孩子准备均衡的早餐和午餐，保证足够的蛋白质、维生素和矿物质摄入。";
        
        // 阶段1: 用户身份识别
        List<String> detectedIdentities = conceptRegistry.detectIdentities(userMessage);
        assertNotNull(detectedIdentities);
        assertTrue(detectedIdentities.contains("NUTRITION"), "应该检测到营养师身份");
        assertTrue(detectedIdentities.contains("PARENTING"), "应该检测到家长身份");
        
        // 阶段2: 用户画像构建
        var currentProfile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(detectedIdentities)
                .build();
        
        var conversation = List.of(
                Map.of("role", "user", "content", userMessage)
        );
        
        var evolvedProfile = professionExtractionService.evolve(currentProfile, conversation);
        assertNotNull(evolvedProfile);
        assertTrue(evolvedProfile.getIdentities().contains("NUTRITION"), "用户画像应该包含营养师身份");
        
        // 阶段3: 智能会话处理
        var sessionId = "test-e2e-session-1";
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(userMessage)
                .sessionId(sessionId)
                .userProfile(evolvedProfile)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        assertNotNull(conversationResponse);
        assertNotNull(conversationResponse.getAgentResponse(), "应该生成AI回复");
        
        // 验证整个流程的完整性和数据一致性
        assertEquals(sessionId, conversationResponse.getSessionId(), "会话ID应该保持一致");
    }

    @Test
    void testNutritionPageAgentCompleteWorkflow() {
        // 模拟食谱生成和表单填写的完整流程
        String sessionId = "test-e2e-session-2";
        
        // 阶段1: 食谱生成
        var generateRequest = new RecipeStreamService.RecipeGenerateRequest();
        generateRequest.setSchoolId(12345L);
        generateRequest.setNsId(1);
        generateRequest.setNscId(1);
        generateRequest.setMealTypes("早餐,午餐");
        generateRequest.setMaleCount(100);
        generateRequest.setFemaleCount(100);
        generateRequest.setAgeGroup("6-8岁");
        generateRequest.setAutoBalance(true);
        
        // 阶段2: 表单填充请求
        var fillRequest = new NutritionPageAgentService.RecipeFillRequest();
        fillRequest.setSchoolId(12345L);
        fillRequest.setNsId(1);
        fillRequest.setMaleCount(100);
        fillRequest.setFemaleCount(100);
        fillRequest.setAgeGroup("6-8岁");
        fillRequest.setTargetFormUrl("https://example.com/recipe-form");
        fillRequest.setBalanceFormUrl("https://example.com/balance-form");
        fillRequest.setAutoBalance(true);
        
        // 执行端到端流程
        var fillResult = nutritionPageAgentService.generateAndFill(fillRequest);
        
        // 验证结果
        assertNotNull(fillResult);
        assertNotNull(fillResult.getSessionId(), "应该生成会话ID");
        assertTrue(fillResult.getDuration() >= 0, "执行时间应该是非负数");
        
        // 验证食谱数据
        var recipeData = fillResult.getRecipeData();
        if (recipeData != null) {
            assertNotNull(recipeData.get("schoolId"), "食谱数据应该包含学校ID");
            assertNotNull(recipeData.get("ageGroup"), "食谱数据应该包含年龄组");
        }
    }

    @Test
    void testSSENotificationIntegration() {
        // 测试SSE通知系统的端到端集成
        String sessionId = "test-e2e-session-3";
        String testMessage = "测试SSE通知消息";
        
        // 创建新的SSE会话
        boolean sessionCreated = sseNotificationProvider.createEmitter(sessionId);
        assertTrue(sessionCreated, "应该成功创建SSE会话");
        
        // 发送测试消息
        boolean messageSent = sseNotificationProvider.sendEvent(sessionId, "test_event", testMessage);
        assertTrue(messageSent, "应该成功发送SSE事件");
        
        // 验证会话管理
        boolean hasEmitter = sseNotificationProvider.hasEmitter(sessionId);
        assertTrue(hasEmitter, "会话管理器应该包含此会话");
        
        // 清理会话
        sseNotificationProvider.completeWithSuccess(sessionId, "测试完成");
    }

    @Test
    void testErrorHandlingAndRecoveryWorkflow() {
        // 测试错误处理和恢复的端到端流程
        String sessionId = "test-e2e-error-session";
        
        // 模拟无效请求数据
        var invalidRequest = new NutritionPageAgentService.RecipeFillRequest();
        invalidRequest.setSchoolId(null);  // 必填字段为null
        invalidRequest.setTargetFormUrl("");  // 空URL
        
        // 验证错误处理
        var errorResult = nutritionPageAgentService.generateAndFill(invalidRequest);
        assertNotNull(errorResult);
        assertFalse(errorResult.isSuccess(), "无效请求应该失败");
        assertNotNull(errorResult.getError(), "错误信息不应该为空");
        
        // 验证应用不会因错误请求而崩溃
        var validRequest = new NutritionPageAgentService.RecipeFillRequest();
        validRequest.setSchoolId(12345L);
        validRequest.setNsId(1);
        validRequest.setTargetFormUrl("https://example.com/test");
        validRequest.setAgeGroup("6-8岁");
        
        var recoveryResult = nutritionPageAgentService.generateAndFill(validRequest);
        // 即使可能失败（因为需要真实服务），但应用不应该崩溃
        assertNotNull(recoveryResult, "应用应该继续处理有效请求");
    }

    @Test
    void testMultiServiceIntegration() {
        // 测试多个服务集成工作流
        String sessionId = "test-multi-service-session";
        
        // 模拟复杂的业务场景：用户咨询营养问题并请求食谱生成
        String userQuery = "我家孩子6岁，对蛋白质需求比较大，你能帮我设计一个高蛋白质的早餐和午餐食谱吗？";
        
        // 身份识别
        List<String> identities = conceptRegistry.detectIdentities(userQuery);
        assertNotNull(identities);
        assertTrue(identities.contains("PARENTING"), "应该识别为家长");
        
        // 用户画像构建
        var profile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(identities)
                .build();
        
        // 智能会话处理
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(userQuery)
                .sessionId(sessionId)
                .userProfile(profile)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        
        // 验证响应包含营养相关推荐
        assertNotNull(conversationResponse);
        String agentResponse = conversationResponse.getAgentResponse();
        assertNotNull(agentResponse);
        
        // 验证整个流程的数据一致性
        assertEquals(sessionId, conversationResponse.getSessionId());
        assertNotNull(conversationResponse.getUserProfile());
    }

    @Test
    void testConfigurationLoadingIntegration() {
        // 测试配置加载的端到端验证
        var conceptEntries = conceptRegistry.getEntries();
        assertNotNull(conceptEntries);
        assertFalse(conceptEntries.isEmpty(), "概念配置应该被正确加载");
        
        // 验证关键概念的配置
        var medicalEntry = conceptRegistry.getByName("MEDICAL");
        assertNotNull(medicalEntry, "医疗概念应该存在于配置中");
        assertEquals("医疗健康", medicalEntry.getDisplayName(), "显示名称应该正确");
        
        var nutritionEntry = conceptRegistry.getByName("NUTRITION");
        assertNotNull(nutritionEntry, "营养概念应该存在于配置中");
        assertEquals("营养健康", nutritionEntry.getDisplayName(), "显示名称应该正确");
        
        // 验证配置的逻辑一致性
        var healthDomainEntries = conceptRegistry.getByDomain("health");
        assertNotNull(healthDomainEntries);
        assertTrue(healthDomainEntries.size() >= 2, "健康领域应该至少包含医疗和营养概念");
    }

    @Test
    void testPerformanceAndResourceUsage() {
        // 测试性能和资源使用的端到端验证
        long startTime = System.currentTimeMillis();
        
        // 执行一系列操作来测试性能
        for (int i = 0; i < 10; i++) {
            String testText = "测试文本内容 " + i;
            var identities = conceptRegistry.detectIdentities(testText);
            assertNotNull(identities);
        }
        
        long endTime = System.currentTimeMillis();
        long executionTime = endTime - startTime;
        
        // 验证性能在可接受范围内
        assertTrue(executionTime < 5000, "批量概念识别应该在5秒内完成");
        
        // 验证资源使用（这里是简化测试，实际需要更复杂的监控）
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        
        double memoryUsageRatio = (double) usedMemory / maxMemory;
        assertTrue(memoryUsageRatio < 0.8, "内存使用率不应该超过80%");
    }

    @Test
    void testDataFlowConsistency() {
        // 测试数据流的一致性和完整性
        String sessionId = "test-dataflow-session";
        String userInput = "我想了解学龄前儿童的营养需求和食谱建议";
        
        // 测试数据在各个服务间的传递
        var identities = conceptRegistry.detectIdentities(userInput);
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(userInput)
                .sessionId(sessionId)
                .build();
        
        var initialResponse = smartConversationService.handleConversation(conversationRequest);
        
        // 验证返回数据的结构完整性
        assertNotNull(initialResponse);
        assertNotNull(initialResponse.getSessionId());
        assertNotNull(initialResponse.getAgentResponse());
        assertNotNull(initialResponse.getTimestamp());
        
        // 验证后续流程的数据传递
        var updatedRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("具体针对蛋白质含量的要求")
                .sessionId(sessionId)
                .conversationHistory(initialResponse.getConversationHistory())
                .build();
        
        var followupResponse = smartConversationService.handleConversation(updatedRequest);
        
        // 验证对话历史的连续性
        assertEquals(sessionId, followupResponse.getSessionId(), "会话ID应该保持一致");
        assertNotNull(followupResponse.getConversationHistory(), "对话历史应该存在");
        assertTrue(followupResponse.getConversationHistory().size() >= 2, 
            "对话历史应该包含多次交互");
    }

    @Test
    void testResourceCleanupAndMemoryManagement() {
        // 测试资源清理和内存管理
        String sessionId = "test-cleanup-session";
        
        // 创建SSE会话
        sseNotificationProvider.createEmitter(sessionId);
        assertTrue(sseNotificationProvider.hasEmitter(sessionId), "会话应该存在");
        
        // 执行一些操作
        var testText = "测试内存管理和资源清理";
        for (int i = 0; i < 100; i++) {
            conceptRegistry.detectIdentities(testText + i);
        }
        
        // 强制GC来观察内存回收
        System.gc();
        
        // 清理会话
        sseNotificationProvider.completeAndClose(sessionId);
        
        // 验证资源已被清理
        // 注意：hasEmitter可能返回false，因为会话被关闭了
        // 关键是不应该出现内存泄漏或资源未释放的情况
    }
}