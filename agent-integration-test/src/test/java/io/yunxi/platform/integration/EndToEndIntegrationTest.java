package io.yunxi.platform.integration;

import io.yunxi.platform.AgentPlatformApplication;
import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.conversation.ChatAppService;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.exception.BadRequestException;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试（新架构）。
 *
 * <p>覆盖：概念识别 → Agent 对话 → SSE 通知 的完整链路，
 * 以及配置加载与基础性能。对话使用通用助手 general-assistant，
 * 调用真实的本地 LLM（凭证见 src/test/resources/application.yml）。</p>
 */
@SpringBootTest(classes = {AgentPlatformApplication.class, IntegrationTestConfig.class})
class EndToEndIntegrationTest {

    private static final String AGENT = "general-assistant";

    @Autowired
    private ChatAppService chatAppService;

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    void testConceptDetectionAndChatE2E() {
        // 阶段1：概念（身份）识别
        String userMessage = "作为一名营养师，我经常关注儿童膳食均衡问题，特别是6-8岁学龄前儿童的营养需求。";
        var identities = conceptRegistry.detectIdentities(userMessage);
        assertNotNull(identities, "概念识别结果不应为空");
        assertTrue(identities.contains("NUTRITION"), "应检测到营养师身份，实际: " + identities);

        // 阶段2：智能会话处理（真实 LLM 调用）
        ChatResponse response = chatAppService.chat(AGENT, new ChatRequest(userMessage));
        assertNotNull(response, "会话响应不应为空");
        assertNotNull(response.getReply(), "应生成 AI 回复");
        assertFalse(response.getReply().isBlank(), "AI 回复不应为空字符串");
    }

    @Test
    void testSseNotificationIntegration() {
        // 测试 SSE 通知系统的端到端集成（新 SPI 接口）
        String sessionId = "test-e2e-sse-3";
        SseEmitter emitter = sseNotificationProvider.createEmitter(sessionId);
        assertNotNull(emitter, "应成功创建 SSE 连接");

        assertTrue(sseNotificationProvider.hasEmitter(sessionId), "会话管理器应包含此会话");

        boolean sent = sseNotificationProvider.send(sessionId, "test_event", "测试SSE通知消息");
        assertTrue(sent, "应成功发送 SSE 事件");

        sseNotificationProvider.complete(sessionId);
    }

    @Test
    void testInvalidInputHandling() {
        // 空消息应在进入 Agent 前抛出 BadRequestException
        assertThrows(BadRequestException.class,
                () -> chatAppService.chat(AGENT, new ChatRequest("")),
                "空消息应抛出 BadRequestException");
        // 纯空白消息同样视为空
        assertThrows(BadRequestException.class,
                () -> chatAppService.chat(AGENT, new ChatRequest("   ")),
                "空白消息应抛出 BadRequestException");
    }

    @Test
    void testConceptConfigurationLoading() {
        // 验证概念配置已正确加载（来自 src/test/resources/application.yml）
        var entries = conceptRegistry.getEntries();
        assertNotNull(entries);
        assertFalse(entries.isEmpty(), "概念配置应被正确加载");

        var nutrition = conceptRegistry.getByName("NUTRITION");
        assertNotNull(nutrition, "营养概念应存在于配置中");
        assertEquals("营养健康", nutrition.getDisplayName(), "显示名称应正确");

        var healthEntries = conceptRegistry.getByDomain("health");
        assertNotNull(healthEntries);
        assertTrue(healthEntries.size() >= 2, "健康领域应至少包含医疗和营养概念");
    }

    @Test
    void testConceptRecognitionPerformance() {
        // 验证批量概念识别在合理时间内完成
        long start = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            assertNotNull(conceptRegistry.detectIdentities("测试文本内容 " + i));
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "批量概念识别应在5秒内完成，实际: " + elapsed + "ms");
    }
}
