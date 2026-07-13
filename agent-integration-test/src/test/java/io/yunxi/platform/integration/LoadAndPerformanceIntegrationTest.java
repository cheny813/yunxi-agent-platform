package io.yunxi.platform.integration;

import io.yunxi.platform.AgentPlatformApplication;
import io.yunxi.platform.agent.profile.ConceptRegistry;
import io.yunxi.platform.conversation.ChatAppService;
import io.yunxi.platform.shared.dto.ChatRequest;
import io.yunxi.platform.shared.dto.ChatResponse;
import io.yunxi.platform.shared.spi.SseNotificationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 负载与性能集成测试（新架构）。
 *
 * <p>概念识别部分为纯本地计算（不依赖 LLM），可高并发压测；
 * 会话吞吐与 SSE 扩展性部分依赖真实 LLM / SSE，断言较宽松。</p>
 */
@SpringBootTest(classes = {AgentPlatformApplication.class, IntegrationTestConfig.class})
class LoadAndPerformanceIntegrationTest {

    private static final String AGENT = "general-assistant";

    @Autowired
    private ConceptRegistry conceptRegistry;

    @Autowired
    private ChatAppService chatAppService;

    @Autowired
    private SseNotificationProvider sseNotificationProvider;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testHighConcurrentConceptRecognition() {
        int concurrentUsers = 20;
        int requestsPerUser = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < concurrentUsers; i++) {
            final int userIndex = i;
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < requestsPerUser; j++) {
                    var identities = conceptRegistry.detectIdentities(
                            "用户" + userIndex + "查询" + j + ": 营养健康测试");
                    assertNotNull(identities, "概念识别在高并发下不应返回 null");
                }
            }, executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsed = System.currentTimeMillis() - startTime;
        int total = concurrentUsers * requestsPerUser;
        System.out.println("并发概念识别: 总请求=" + total + ", 耗时=" + elapsed + "ms");
        assertTrue(elapsed < 30000, "并发概念识别应在30秒内完成，实际: " + elapsed + "ms");
        executor.shutdown();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testSseConnectionScalability() {
        int connectionCount = 50;
        List<String> sessionIds = new ArrayList<>();

        long start = System.currentTimeMillis();
        for (int i = 0; i < connectionCount; i++) {
            String sessionId = "sse-scale-" + i;
            assertNotNull(sseNotificationProvider.createEmitter(sessionId), "应能够创建 SSE 连接 " + i);
            sessionIds.add(sessionId);
        }
        long createTime = System.currentTimeMillis() - start;

        for (String sessionId : sessionIds) {
            assertTrue(sseNotificationProvider.hasEmitter(sessionId), "SSE 连接应保持活跃: " + sessionId);
        }

        long broadcastStart = System.currentTimeMillis();
        int successfulBroadcasts = 0;
        for (String sessionId : sessionIds) {
            if (sseNotificationProvider.send(sessionId, "scalability_test", "测试广播消息")) {
                successfulBroadcasts++;
            }
        }
        long broadcastTime = System.currentTimeMillis() - broadcastStart;

        for (String sessionId : sessionIds) {
            sseNotificationProvider.removeEmitter(sessionId);
        }

        System.out.println("SSE 扩展性: 创建=" + createTime + "ms, 广播=" + broadcastTime
                + "ms, 成功广播=" + successfulBroadcasts + "/" + connectionCount);
        assertTrue(createTime < 10000, "创建连接时间应小于10秒，实际: " + createTime + "ms");
        assertTrue(successfulBroadcasts >= connectionCount * 0.8,
                "成功广播率应超过80%，实际: " + successfulBroadcasts + "/" + connectionCount);
    }

    @Test
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    void testChatThroughput() {
        int users = 5;
        int messagesPerUser = 3;
        ExecutorService executor = Executors.newFixedThreadPool(users);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        long startTime = System.currentTimeMillis();
        for (int i = 0; i < users; i++) {
            final int userIndex = i;
            futures.add(CompletableFuture.runAsync(() -> {
                for (int j = 0; j < messagesPerUser; j++) {
                    ChatResponse response = chatAppService.chat(AGENT,
                            new ChatRequest("用户" + userIndex + "消息" + j + ": 请简单介绍蛋白质的作用"));
                    assertNotNull(response, "会话服务在高负载下应返回有效响应");
                    assertNotNull(response.getReply(), "AI 回复不应为空");
                }
            }, executor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        long elapsed = System.currentTimeMillis() - startTime;
        int total = users * messagesPerUser;
        System.out.println("会话吞吐: 总消息=" + total + ", 耗时=" + elapsed + "ms");
        // 本机 qwen-plus 真实 LLM 调用时延较高，仅做宽松上限断言，重点验证并发下不崩溃且均返回有效回复
        assertTrue(elapsed < 540000, "总体测试时间应小于540秒，实际: " + elapsed + "ms");
        executor.shutdown();
    }
}
