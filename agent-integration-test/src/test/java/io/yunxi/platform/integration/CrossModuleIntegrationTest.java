package io.yunxi.platform.integration;

import io.yunxi.platform.AgentPlatformApplication;
import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.conversation.ChatAppService;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 跨模块交互集成测试（新架构）。
 *
 * <p>验证概念识别、Agent 对话、SSE 通知三个模块间的协同与数据一致性。</p>
 */
@SpringBootTest(classes = {AgentPlatformApplication.class, IntegrationTestConfig.class})
class CrossModuleIntegrationTest {

    private static final String AGENT = "general-assistant";

    @Autowired
    private ChatAppService chatAppService;

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    void testConceptToChatWorkflow() {
        // 概念识别 → 智能会话 的完整流程
        String userInput = "我是一名营养师，主要关注儿童生长发育期的营养需求，特别是蛋白质和钙的补充问题。";
        List<String> identities = conceptRegistry.detectIdentities(userInput);
        assertNotNull(identities, "概念识别结果不应为空");
        assertTrue(identities.contains("NUTRITION"), "应识别到营养师身份");

        ChatResponse response = chatAppService.chat(AGENT, new ChatRequest(userInput));
        assertNotNull(response, "会话响应不应为空");
        assertNotNull(response.getReply(), "AI 回复不应为空");
    }

    @Test
    void testSseWithChatIntegration() {
        // SSE 通知与会话服务的结合
        String sessionId = "cross-sse-1";
        assertNotNull(sseNotificationProvider.createEmitter(sessionId), "应成功创建 SSE 会话");

        ChatResponse response = chatAppService.chat(AGENT,
                new ChatRequest("请帮我生成一份适合6-8岁儿童的营养建议。"));
        assertNotNull(response);
        assertNotNull(response.getReply());

        // 通过 SSE 发送完成通知
        boolean sent = sseNotificationProvider.send(sessionId, "chat_complete",
                Map.of("reply", response.getReply()));
        assertTrue(sent, "应成功发送 SSE 完成通知");
        assertTrue(sseNotificationProvider.hasEmitter(sessionId), "SSE 会话应保持活跃");

        sseNotificationProvider.complete(sessionId);
    }

    @Test
    void testDataConsistencyAcrossModules() {
        // 不同模块间数据的一致性：相同输入的概念识别结果应一致
        String message = "营养均衡对于儿童生长发育非常重要";
        var identities1 = conceptRegistry.detectIdentities(message);
        var identities2 = conceptRegistry.detectIdentities(message);
        assertEquals(identities1, identities2, "相同输入的概念识别结果应完全一致");
    }

    @Test
    void testModuleIsolation() {
        // 验证核心模块的功能独立性
        String sseSessionId = "iso-sse";
        assertNotNull(sseNotificationProvider.createEmitter(sseSessionId), "SSE 模块应独立工作");

        ChatResponse response = chatAppService.chat(AGENT, new ChatRequest("测试模块独立性"));
        assertNotNull(response, "会话服务应独立工作");

        sseNotificationProvider.removeEmitter(sseSessionId);
    }
}
