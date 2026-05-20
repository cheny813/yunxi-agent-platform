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
 * 跨模块交互集成测试
 * 
 * 验证不同模块间的通信、数据传递和协同工作
 */
@SpringBootTest(classes = yunxiAgentPlatformApplication.class)
@ExtendWith(SpringExtension.class)
@ActiveProfiles("integration-test")
class CrossModuleIntegrationTest {

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SmartConversationService smartConversationService;

    @Autowired
    private ProfessionExtractionService professionExtractionService;

    @Autowired
    private RecipeStreamService recipeStreamService;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    void testConceptToProfileToConversationWorkflow() {
        // 测试概念识别 → 用户画像 → 智能会话的完整流程
        String userInput = "我是一名营养师，主要关注儿童生长发育期的营养需求，特别是蛋白质和钙的补充问题。";
        String sessionId = "cross-module-test-1";
        
        // 模块1：概念识别
        List<String> identities = conceptRegistry.detectIdentities(userInput);
        assertNotNull(identities, "概念识别结果不应该为空");
        assertTrue(identities.contains("NUTRITION"), "应该识别到营养师身份");
        assertTrue(identities.contains("PARENTING") || identities.contains("EDUCATION"), 
            "应该识别到与儿童相关的身份");
        
        // 模块2：用户画像构建
        var currentProfile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(identities)
                .build();
        
        var conversation = List.of(
                Map.of("role", "user", "content", userInput)
        );
        
        var evolvedProfile = professionExtractionService.evolve(currentProfile, conversation);
        assertNotNull(evolvedProfile, "用户画像应该成功构建");
        assertFalse(evolvedProfile.getIdentities().isEmpty(), "用户画像应该包含身份信息");
        
        // 模块3：智能会话处理
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(userInput)
                .sessionId(sessionId)
                .userProfile(evolvedProfile)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        assertNotNull(conversationResponse, "会话响应不应该为空");
        assertNotNull(conversationResponse.getAgentResponse(), "AI回复不应该为空");
        assertEquals(sessionId, conversationResponse.getSessionId(), "会话ID应该保持一致");
        
        // 验证模块间数据传递
        assertNotNull(conversationResponse.getUserProfile(), "用户画像应该传递到会话模块");
        assertEquals(evolvedProfile.getIdentities().size(), 
                     conversationResponse.getUserProfile().getIdentities().size(),
                    "用户身份信息应该保持一致");
    }

    @Test
    void testSSEWithConversationIntegration() {
        // 测试SSE通知与会话服务的结合
        String sessionId = "cross-module-sse-1";
        String userMessage = "请帮我生成一份适合6-8岁儿童的营养食谱。";
        
        // 创建SSE会话
        assertTrue(sseNotificationProvider.createEmitter(sessionId), "应该成功创建SSE会话");
        
        // 执行智能会话
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(userMessage)
                .sessionId(sessionId)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        
        // 通过SSE发送进度通知
        boolean progressSent = sseNotificationProvider.sendEvent(sessionId, 
            "conversation_progress", 
            Map.of("status", "processing", "message", "正在生成回复...")
        );
        
        assertTrue(progressSent, "应该成功发送SSE进度通知");
        
        // 发送完成通知
        boolean completeSent = sseNotificationProvider.sendEvent(sessionId,
            "conversation_complete", 
            Map.of("response", conversationResponse.getAgentResponse())
        );
        
        assertTrue(completeSent, "应该成功发送SSE完成通知");
        
        // 验证SSE会话的一致性
        assertTrue(sseNotificationProvider.hasEmitter(sessionId), "SSE会话应该保持活跃");
        
        // 清理资源
        sseNotificationProvider.completeWithSuccess(sessionId, "测试完成");
    }

    @Test
    void testRecipeFlowWithMultipleServices() {
        // 测试食谱流程中多个服务的协同工作
        String sessionId = "recipe-flow-test-1";
        
        // 模拟复杂的业务场景：用户咨询后进行食谱生成
        String userQuery = "学校有300名学生，年龄分布6-12岁，需要生成一周的食谱方案，要求蛋白质和维生素充足。";
        
        // 阶段1：用户画像提取
        var identities = conceptRegistry.detectIdentities(userQuery);
        var profile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(identities)
                .build();
        
        // 阶段2：智能对话获取需求细节
        var detailsRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("请提供学生年龄分布的具体数据，男生女生各多少人？")
                .sessionId(sessionId)
                .userProfile(profile)
                .build();
        
        var detailsResponse = smartConversationService.handleConversation(detailsRequest);
        assertNotNull(detailsResponse, "细节询问响应不应该为空");
        
        // 阶段3：转换对话结果为结构化数据
        // （假设有转换逻辑，这里简化）
        var structuredData = Map.of(
            "schoolId", 12345,
            "maleCount", 150,
            "femaleCount", 150,
            "ageGroup", "6-12岁",
            "mealTypes", "早餐,午餐,晚餐",
            "duration", "一周"
        );
        
        // 阶段4：食谱生成（使用流式服务）
        var recipeRequest = new RecipeStreamService.RecipeGenerateRequest();
        recipeRequest.setSchoolId(structuredData.get("schoolId").toString());
        recipeRequest.setMaleCount(Integer.parseInt(structuredData.get("maleCount").toString()));
        recipeRequest.setFemaleCount(Integer.parseInt(structuredData.get("femaleCount").toString()));
        recipeRequest.setAgeGroup(structuredData.get("ageGroup").toString());
        recipeRequest.setMealTypes(structuredData.get("mealTypes").toString());
        recipeRequest.setAutoBalance(true);
        
        // 验证流式服务的响应结构
        var recipeFlux = recipeStreamService.streamGenerateRecipe(recipeRequest);
        assertNotNull(recipeFlux, "食谱流不应该为空");
        
        // 验证整个业务流程的数据完整性
        assertNotNull(structuredData.get("schoolId"), "学校ID应该存在");
        assertNotNull(profile.getIdentities(), "用户画像应该存在");
        assertNotNull(detailsResponse.getConversationHistory(), "对话历史应该存在");
    }

    @Test
    void testDataConsistencyAcrossModules() {
        // 测试不同模块间数据的一致性
        String sessionId = "data-consistency-test";
        String consistentMessage = "营养均衡对于儿童生长发育非常重要";
        
        // 在不同模块中处理相同的数据
        var identities1 = conceptRegistry.detectIdentities(consistentMessage);
        var identities2 = conceptRegistry.detectIdentities(consistentMessage);
        
        // 验证概念识别的一致性
        assertEquals(identities1.size(), identities2.size(), 
            "相同输入的概念识别结果应该一致");
        assertTrue(identities1.containsAll(identities2) && identities2.containsAll(identities1),
            "概念识别结果应该完全相同");
        
        // 验证会话处理的稳定性
        var request1 = SmartConversationService.ConversationRequest.builder()
                .userMessage(consistentMessage)
                .sessionId(sessionId + "-1")
                .build();
        
        var request2 = SmartConversationService.ConversationRequest.builder()
                .userMessage(consistentMessage)
                .sessionId(sessionId + "-2")
                .build();
        
        var response1 = smartConversationService.handleConversation(request1);
        var response2 = smartConversationService.handleConversation(request2);
        
        // 响应可能不同（因为AI的回答有随机性），但结构应该一致
        assertNotNull(response1.getAgentResponse());
        assertNotNull(response2.getAgentResponse());
        assertEquals(response1.getSessionId(), sessionId + "-1");
        assertEquals(response2.getSessionId(), sessionId + "-2");
    }

    @Test
    void testModuleDependencyAndIsolation() {
        // 测试模块间的依赖关系和隔离性
        
        // 验证核心模块的功能独立性
        var testText = "测试模块独立性";
        
        // 概念识别模块应该能够独立工作
        var identities = conceptRegistry.detectIdentities(testText);
        assertNotNull(identities, "概念识别应该独立工作");
        
        // SSE模块应该能够独立管理会话
        String sseSessionId = "isolation-test-sse";
        assertTrue(sseNotificationProvider.createEmitter(sseSessionId), 
            "SSE模块应该独立工作");
        
        // 会话服务可能需要用户画像，但应该有默认处理
        var defaultRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage(testText)
                .sessionId("isolation-test-conv")
                .build();
        
        var defaultResponse = smartConversationService.handleConversation(defaultRequest);
        assertNotNull(defaultResponse, "会话服务应该处理无用户画像的情况");
        
        // 清理测试资源
        sseNotificationProvider.completeAndClose(sseSessionId);
    }

    @Test
    void testErrorPropagationAndHandling() {
        // 测试模块间错误传播和处理
        String sessionId = "error-propagation-test";
        
        // 场景：一个模块失败时，其他模块应该优雅处理
        
        // 创建SSE会话
        sseNotificationProvider.createEmitter(sessionId);
        
        // 模拟一个可能失败的操作：使用无效数据
        var invalidRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("")
                .sessionId(sessionId)
                .build();
        
        // 即使输入无效，系统也不应该崩溃
        var errorResponse = smartConversationService.handleConversation(invalidRequest);
        assertNotNull(errorResponse, "系统应该处理无效输入而不崩溃");
        
        // 验证SSE会话在错误后仍然有效
        assertTrue(sseNotificationProvider.hasEmitter(sessionId), 
            "错误不应该导致SSE会话关闭");
        
        // 发送错误恢复消息
        boolean recoverySent = sseNotificationProvider.sendEvent(sessionId,
            "error_recovery", 
            Map.of("status", "recovered", "message", "系统已恢复正常")
        );
        
        assertTrue(recoverySent, "应该能够在错误后继续使用SSE");
        
        // 清理
        sseNotificationProvider.completeWithSuccess(sessionId, "错误处理测试完成");
    }

    @Test
    void testResourceSharingAndConflicts() {
        // 测试模块间资源共享和冲突处理
        String sharedSessionId = "shared-resource-test";
        
        // 多个服务使用相同的会话ID
        
        // SSE会话创建
        assertTrue(sseNotificationProvider.createEmitter(sharedSessionId), 
            "应该能够创建共享SSE会话");
        
        // 概念识别使用会话上下文
        var profile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(List.of("TESTER"))
                .build();
        
        // 智能会话使用相同会话ID
        var conversationRequest = SmartConversationService.ConversationRequest.builder()
                .userMessage("测试资源共享")
                .sessionId(sharedSessionId)
                .userProfile(profile)
                .build();
        
        var conversationResponse = smartConversationService.handleConversation(conversationRequest);
        assertNotNull(conversationResponse, "共享会话应该正常工作");
        
        // 同时发送SSE通知
        boolean notificationSent = sseNotificationProvider.sendEvent(sharedSessionId,
            "shared_operation", 
            Map.of("status", "success", "message", "资源共享测试成功")
        );
        
        assertTrue(notificationSent, "应该能够在共享会话中发送通知");
        
        // 验证资源没有冲突
        assertEquals(sharedSessionId, conversationResponse.getSessionId(),
            "会话ID应该保持一致");
        assertTrue(sseNotificationProvider.hasEmitter(sharedSessionId),
            "共享会话应该仍然有效");
        
        // 清理共享资源
        sseNotificationProvider.completeAndClose(sharedSessionId);
    }

    @Test
    void testModuleCommunicationProtocols() {
        // 测试模块间通信协议的一致性
        String protocolTestSession = "protocol-test-session";
        
        // 测试数据格式的一致性
        var standardProfile = ProfessionExtractionService.UserProfileProvider.UserProfile.builder()
                .identities(List.of("PROTOCOL_TEST"))
                .build();
        
        // 验证不同服务对相同数据结构的处理
        var requestForConversation = SmartConversationService.ConversationRequest.builder()
                .userMessage("协议一致性测试")
                .sessionId(protocolTestSession)
                .userProfile(standardProfile)
                .build();
        
        var response = smartConversationService.handleConversation(requestForConversation);
        
        // 验证返回数据的结构符合协议
        assertNotNull(response.getSessionId(), "响应应该包含会话ID");
        assertNotNull(response.getAgentResponse(), "响应应该包含AI回复");
        assertNotNull(response.getTimestamp(), "响应应该包含时间戳");
        assertNotNull(response.getUserProfile(), "响应应该包含用户画像");
        
        // 验证数据结构与来源一致
        assertEquals(standardProfile.getIdentities().size(),
                     response.getUserProfile().getIdentities().size(),
                    "用户画像数据应该保持一致性");
    }
}