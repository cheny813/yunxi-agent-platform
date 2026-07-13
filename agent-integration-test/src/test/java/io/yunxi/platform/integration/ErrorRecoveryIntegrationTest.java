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

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误恢复集成测试（新架构）。
 *
 * <p>验证系统对无效输入、并发访问、SSE 中断等异常场景的健壮性。</p>
 */
@SpringBootTest(classes = {AgentPlatformApplication.class, IntegrationTestConfig.class})
class ErrorRecoveryIntegrationTest {

    private static final String AGENT = "general-assistant";

    @Autowired
    private ChatAppService chatAppService;

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    void testInvalidInputHandling() {
        // 空/空白消息应在进入 Agent 前抛出 BadRequestException，不应崩溃
        assertThrows(BadRequestException.class, () -> chatAppService.chat(AGENT, new ChatRequest("")),
                "空消息应抛出 BadRequestException");
        assertThrows(BadRequestException.class, () -> chatAppService.chat(AGENT, new ChatRequest("   ")),
                "空白消息应抛出 BadRequestException");
    }

    @Test
    void testConceptRecognitionNeverCrashes() {
        // 概念识别应能够处理各种无效输入而不崩溃
        String[] invalidInputs = { "", "   ", "\n\n\n", "!@#$%^&*()", "超长输入".repeat(50) };
        for (String input : invalidInputs) {
            assertNotNull(conceptRegistry.detectIdentities(input),
                    "概念识别应处理无效输入而不崩溃: [" + input + "]");
        }
    }

    @Test
    void testSseResilience() {
        // 模拟 SSE 会话断开后重建
        String sessionId = "err-sse";
        assertNotNull(sseNotificationProvider.createEmitter(sessionId));
        assertTrue(sseNotificationProvider.send(sessionId, "recovery", Map.of("status", "ok")),
                "应能够发送 SSE 事件");

        // 断开
        sseNotificationProvider.removeEmitter(sessionId);

        // 重建
        assertNotNull(sseNotificationProvider.createEmitter(sessionId), "应能够重新创建 SSE 会话");
        assertTrue(sseNotificationProvider.hasEmitter(sessionId), "重建后会话应有效");
        sseNotificationProvider.complete(sessionId);
    }

    @Test
    void testConcurrentAccessHandling() throws InterruptedException {
        // 并发访问不应导致系统崩溃
        int concurrentThreads = 10;
        Thread[] threads = new Thread[concurrentThreads];
        boolean[] ok = new boolean[concurrentThreads];

        for (int i = 0; i < concurrentThreads; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    ChatResponse response = chatAppService.chat(AGENT,
                            new ChatRequest("并发测试消息 " + idx));
                    ok[idx] = response != null && response.getReply() != null;
                } catch (Exception e) {
                    ok[idx] = false;
                }
            });
        }

        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(TimeUnit.MINUTES.toMillis(2));
        }

        for (int i = 0; i < concurrentThreads; i++) {
            assertTrue(ok[i], "并发环境下会话服务应正常工作，线程 " + i + " 失败");
        }
    }
}
