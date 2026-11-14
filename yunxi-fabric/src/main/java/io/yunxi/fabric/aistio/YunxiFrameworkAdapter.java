package io.yunxi.fabric.aistio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.controlplane.FrameworkAdapter;
import io.agentscope.extensions.controlplane.adapter.AgentScopeAdapter;
import io.agentscope.extensions.controlplane.adapter.AgentTaskStarter;
import io.agentscope.extensions.controlplane.model.AgentTaskAssignment;
import io.agentscope.extensions.controlplane.model.ContextSnapshot;
import io.agentscope.extensions.controlplane.model.SessionEvent;
import io.yunxi.runtime.Json;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * aistio 集成适配层（yunxi 产品平面，单一集成点之一）。
 *
 * <p>薄封装框架官方 {@link AgentScopeAdapter}：仅叠加 yunxi 的两处产品关切——优雅下线闸门与
 * {@code correlationId} 回读——其余五个抽象方法（frameworkName / canHandle / attach / detach / extractContext）
 * 与任务执行器全部委托官方实现。</p>
 *
 * <p>框架 {@link AgentScopeAdapter} 在 {@code attach} 时绑定到具体 Agent（覆盖其内部 {@code agent} 字段），
 * 故本适配器按 Agent 实例化为每 Agent 一份，由 {@link AistioConnector} 在 {@code instrument} 时创建，
 * 不注册为 Spring 单例。</p>
 */
public final class YunxiFrameworkAdapter implements FrameworkAdapter {

    private static final ObjectMapper MAPPER = Json.objectMapper();
    private static final Logger log = Logger.getLogger(YunxiFrameworkAdapter.class.getName());
    private final AgentScopeAdapter delegate = new AgentScopeAdapter();
    private final AistioLifecycle lifecycle;
    private final Map<String, String> correlationIds = new ConcurrentHashMap<>();

    /** 注入共享生命周期闸门（用于优雅下线时拒绝新任务）。 */
    public YunxiFrameworkAdapter(AistioLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public String frameworkName() {
        return delegate.frameworkName();
    }

    @Override
    public boolean canHandle(Object target) {
        return delegate.canHandle(target);
    }

    @Override
    public void attach(Object target, Consumer<SessionEvent> emit) {
        delegate.attach(target, emit);
    }

    @Override
    public void detach() {
        delegate.detach();
    }

    @Override
    public Mono<ContextSnapshot> extractContext(String sessionId) {
        return delegate.extractContext(sessionId);
    }

    @Override
    public Mono<Void> handleAgentTask(AgentTaskAssignment assignment) {
        String correlationId = readCorrelationId(assignment.payload());
        if (correlationId != null && !correlationId.isBlank()) {
            correlationIds.put(assignment.agentTaskId(), correlationId);
            log.info("AgentTask " + assignment.agentTaskId() + " correlationId=" + correlationId);
        }
        if (!lifecycle.isAccepting()) {
            return Mono.error(new IllegalStateException(
                    "yunxi aistio 优雅下线中，拒绝新 AgentTask: " + assignment.agentTaskId()));
        }
        return delegate.handleAgentTask(assignment);
    }

    /** 取已接活任务的 correlationId（供可观测关联）；框架 RuntimeContext 由执行器自建、yunxi 无法注入，故仅索引留存。 */
    public String correlationIdOf(String agentTaskId) {
        return correlationIds.get(agentTaskId);
    }

    /** 装配框架官方任务执行器（声明 agent-task 能力）。 */
    public void setAgentTaskStarter(AgentTaskStarter starter) {
        delegate.setAgentTaskStarter(starter);
    }

    /** 供运行时在 Agent build 期挂上的事件流观测中间件（yunxi 不创建同名类）。 */
    public MiddlewareBase observerMiddleware() {
        return delegate.middleware();
    }

    private static String readCorrelationId(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(payload);
            JsonNode cid = node.path("_yunxi").path("correlationId");
            return cid.isMissingNode() || cid.isNull() ? null : cid.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
