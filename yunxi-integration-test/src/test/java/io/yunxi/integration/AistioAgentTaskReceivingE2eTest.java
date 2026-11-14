package io.yunxi.integration;

import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.controlplane.adapter.AgentScopeAdapter;
import io.agentscope.extensions.controlplane.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import io.agentscope.extensions.controlplane.transport.CollaborationClient;
import io.agentscope.extensions.controlplane.transport.ControlPlaneHttpClient;
import io.agentscope.extensions.model.dashscope.DashScopeModelProvider;
import io.agentscope.harness.agent.HarnessAgent;
import io.yunxi.fabric.aistio.AistioLifecycle;
import io.yunxi.fabric.aistio.YunxiFrameworkAdapter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * aistio 任务接活端到端验证（默认禁用，仅 -Dreal.llm=true 激活，无需控制面 Docker）。
 *
 * <p>控制面在 AgentScope-Java 2.0.4 消费侧对齐的 2.1.0-BETA1 镜像中，没有可从外部驱动「创建任务 → 派发」
 * 的 REST 入口（{@code POST /api/v1/agent-tasks} 返回 not found，Teams/Issues 写接口无法绑定字段），任务由
 * 控制面自身编排内部生成。因此本测试对「控制面协作传输」用进程内 mock HTTP 服务器替代（返回构造的
 * 任务上下文 / 工具清单 / 完成回报），而 yunxi 侧接活链路完全真实：
 * {@code YunxiFrameworkAdapter.handleAgentTask}（即 gRPC {@code onExecutionAttempt} 实际调用的入口）
 * → 框架 {@code HarnessAgentTaskStarter} → 真实 {@link HarnessAgent} → 真实大模型执行 → 真实回报完成。
 * 这证明 yunxi 产品平面在「任务接活」上的接线与执行器可用。</p>
 *
 * <p>能力声明验证：装配任务执行器后，框架适配器应将 {@code agent-task} 能力上报控制面，作为被派发的前提。</p>
 */
@EnabledIfSystemProperty(named = "real.llm", matches = "true")
class AistioAgentTaskReceivingE2eTest {

    private static final String AGENT_KEY = "it-agent";

    /** 资源与观察状态共享对象。 */
    private static final class MockPlane {
        final AtomicBoolean taskContextCalled = new AtomicBoolean();
        final AtomicBoolean completeCalled = new AtomicBoolean();
        final AtomicBoolean failCalled = new AtomicBoolean();
        final AtomicReference<String> completeBody = new AtomicReference<>();
        final AtomicReference<String> failBody = new AtomicReference<>();
        HttpServer server;
    }

    /** 任务下推时控制面下发的执行上下文（与 HarnessAgentTaskStarter 解析契约一致）。 */
    private static final String ENVELOPE = "{"
            + "\"task\":{\"version\":1,\"status\":\"running\",\"teamId\":\"\",\"leaderTask\":false},"
            + "\"inputs\":[],"
            + "\"availableActions\":[],"
            + "\"runtimeBinding\":{}"
            + "}";

    @Test
    void receivesAgentTaskAndRunsRealAgent() throws Exception {
        String modelId = System.getProperty("llm.model", "qwen3.8-27b");
        Model model = new DashScopeModelProvider().create(modelId);

        MockPlane plane = new MockPlane();
        startMockPlane(plane);
        int port = plane.server.getAddress().getPort();
        try {
            // 真实协作客户端指向本地 mock 控制面（替代黑盒传输）
            CollaborationClient collaboration =
                    new CollaborationClient(new ControlPlaneHttpClient("http://localhost:" + port, "dummy"));
            // 构建真实 Agent（attach 与执行器共用同一实例，等价于 ControlPlane.instrument 绑定）
            HarnessAgent agent = HarnessAgent.builder().name(AGENT_KEY).model(model).build();
            // 真实框架任务执行器（声明 agent-task 能力的同一执行器）
            HarnessAgentTaskStarter starter = new HarnessAgentTaskStarter(() -> agent, collaboration);

            // yunxi 产品平面适配器（gRPC onExecutionAttempt 实际调用的入口）
            YunxiFrameworkAdapter adapter = new YunxiFrameworkAdapter(new AistioLifecycle());
            adapter.setAgentTaskStarter(starter);
            // 等价于 ControlPlane.instrument 内部的 attach：绑定 Agent 后才能接活
            adapter.attach(agent, event -> {});

            // 合成一次任务下推（等价于控制面经 gRPC 下发的 ExecutionAttemptCommand 解析产物）
            byte[] payload = ("{\"task\":\"Say the exact words HELLO_AGENT_TASK and then call task.submit_result "
                    + "with outcome 'succeeded' and result 'HELLO_AGENT_TASK'.\"}").getBytes(StandardCharsets.UTF_8);
            AgentTaskAssignment assignment = new AgentTaskAssignment(
                    UUID.randomUUID().toString(),        // attemptId
                    "at-" + UUID.randomUUID(),           // agentTaskId
                    "run-" + UUID.randomUUID(),          // runId
                    "node-1",                            // nodeId
                    1L,                                  // generation
                    "execute",                           // command
                    "",                                  // contextUrl
                    "tok-" + UUID.randomUUID(),          // taskToken
                    "atok-" + UUID.randomUUID(),         // attemptToken
                    "sess-" + UUID.randomUUID(),         // sessionId
                    payload,                             // payload
                    System.currentTimeMillis());         // timestamp

            // 接活：阻塞直到真实执行器跑完真实 Agent 并回报
            Mono<Void> result = adapter.handleAgentTask(assignment);
            assertNotNull(result, "handleAgentTask 应返回可订阅的 Mono");
            result.block(Duration.ofMinutes(3));

            // 接活入口确实拉取了任务上下文（等价于 onExecutionAttempt → starter 拉取 context）
            assertTrue(plane.taskContextCalled.get(),
                    "任务接活入口未拉取任务上下文（collaboration.taskContext 未调用）");
            // 执行完成并回报控制面（finish/complete 被调用）
            assertTrue(plane.completeCalled.get(),
                    "任务执行后未回报完成（collaboration.finish / complete 未调用）");
            // 无异常路径
            assertFalse(plane.failCalled.get(),
                    "任务执行异常（collaboration.fail 被调用）: " + plane.failBody.get());
            // 真实 Agent 产出非空结果（证明真实大模型被调用并产出）
            String body = plane.completeBody.get();
            assertNotNull(body, "完成回报体为空");
            assertTrue(body.contains("\"result\""),
                    "完成回报缺少 result 字段，真实 Agent 未产出结果: " + body);
            System.out.println("[aistio-task] complete body => " + body);
        } finally {
            plane.server.stop(0);
        }
    }

    @Test
    void declaresAgentTaskCapabilityWhenStarterWired() {
        // 框架适配器装配任务执行器后，应向控制面声明 agent-task 能力（被派发的前提）
        HarnessAgent dummyAgent = HarnessAgent.builder().name(AGENT_KEY)
                .model(new DashScopeModelProvider().create(System.getProperty("llm.model", "qwen3.8-27b")))
                .build();
        CollaborationClient collaboration =
                new CollaborationClient(new ControlPlaneHttpClient("http://localhost:1", "dummy"));
        HarnessAgentTaskStarter starter = new HarnessAgentTaskStarter(() -> dummyAgent, collaboration);

        AgentScopeAdapter adapter = new AgentScopeAdapter();
        adapter.setAgentTaskStarter(starter);

        Set<String> caps = adapter.capabilities();
        assertTrue(caps.contains("agent-task"),
                "装配任务执行器后未声明 agent-task 能力，控制面不会向本实例派发任务: " + caps);
        System.out.println("[aistio-task] declared capabilities => " + caps);
    }

    private static void startMockPlane(MockPlane plane) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] reqBody = exchange.getRequestBody().readAllBytes();
            String body = new String(reqBody, StandardCharsets.UTF_8);
            String resp;
            int code;
            if (path.endsWith("/context") && "GET".equals(method)) {
                plane.taskContextCalled.set(true);
                resp = ENVELOPE;
                code = 200;
            } else if (path.endsWith("/start") && "POST".equals(method)) {
                resp = "{\"task\":{\"version\":1,\"status\":\"running\"}}";
                code = 200;
            } else if ("/mcp/collaboration".equals(path) && "POST".equals(method)) {
                resp = "{\"result\":{\"tools\":[]}}";
                code = 200;
            } else if (path.endsWith("/complete") && "POST".equals(method)) {
                plane.completeCalled.set(true);
                plane.completeBody.set(body);
                resp = "{}";
                code = 200;
            } else if (path.endsWith("/fail") && "POST".equals(method)) {
                plane.failCalled.set(true);
                plane.failBody.set(body);
                resp = "{}";
                code = 200;
            } else {
                resp = "{}";
                code = 404;
            }
            byte[] r = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, r.length);
            exchange.getResponseBody().write(r);
            exchange.close();
        });
        server.start();
        plane.server = server;
    }
}
