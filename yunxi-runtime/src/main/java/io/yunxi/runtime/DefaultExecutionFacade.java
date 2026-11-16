package io.yunxi.runtime;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.HarnessGateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.yunxi.runtime.TenantAgentAllocationService;
import io.yunxi.runtime.middleware.IntentSignalMiddleware;
import io.yunxi.runtime.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 运行时平面。默认 {@link ExecutionFacade}。统一经 AgentScope-Java {@link HarnessGateway#runStream} 驱动 Agent：
 * Agent 查找由网关 {@code agentRegistry} 承担（由 AgentProvisioner 在布建期统一注册），身份与富化由网关
 * {@code ChannelRuntimeContextResolver}（yunxi 实现 YunxiChannelRuntimeContextResolver）承担，会话轮次闸门由
 * 网关 {@code SessionTurnGate}（setSessionTurnGate 注入）承担。本类仅做「Command → MsgContext + Msg」薄翻译，
 * 不演化第二套推理 / 路由语义。
 *
 * <p>会话键映射：网关按 {@code canonicalKey()} 派生确定态 sessionId，与客户端 sessionId 解耦但确定，多轮连续；
 * {@code canonicalKey} 不含 userId，故将 {@code userId:sessionId} 编入 threadId 以复刻旧「同用户同会话串行」语义。</p>
 *
 * <p>租户授权：在 {@code gateway.findAgent} 之前调用 {@link TenantAgentAllocationService#assertAllowed}，由入口
 * （IngressGateway / Chat Completions）经 {@link TenantContext#withTenant} 于订阅线程绑定的租户做越权拦截。
 * 整个执行体包进 {@link Flux#defer}，确保越权检查与执行读取到与订阅同线程的租户（而非调用线程的 null），
 * 覆盖流式（同步执行段）与非流式（block 链路）两条路径。</p>
 */
public class DefaultExecutionFacade implements ExecutionFacade {

    private static final Logger log = LoggerFactory.getLogger(DefaultExecutionFacade.class);

    private static final String CHANNEL = "web";

    private final HarnessGateway gateway;
    private final TenantAgentAllocationService allocation;

    public DefaultExecutionFacade(HarnessGateway gateway, TenantAgentAllocationService allocation) {
        this.gateway = gateway;
        this.allocation = allocation;
    }

    @Override
    public Flux<AgentEvent> execute(AgentCommand command) {
        // 整体延迟到订阅后执行：租户由入口经 TenantContext.withTenant 于订阅线程绑定 ThreadLocal，
        // 此处读取到的才是与执行同线程的租户，而非调用线程的 null。assertAllowed 在 findAgent 之前拦截越权。
        return Flux.defer(() -> {
            String tenant = TenantContext.current();
            allocation.assertAllowed(tenant, command.agentId());
            if (gateway.findAgent(command.agentId()) == null) {
                return Flux.error(new NoSuchAgentException(command.agentId()));
            }
            MsgContext msgContext = new MsgContext(
                    CHANNEL,
                    null,
                    null,
                    turnThreadId(command),
                    null,
                    Map.of("agentId", command.agentId()),
                    command.userId());
            // 入口解析出的意图标签以 RuntimeContext 属性形态随 callerContext 带入网关，
            // 由 IntentSignalMiddleware 搬进模型调用 options 供按请求选型消费；不编入
            // MsgContext（extra 参与 canonicalKey，会话键不得随意图漂移）。意图缺失时
            // 保持 null callerContext，下游选型不受影响。
            RuntimeContext callerContext = intentCallerContext(command);
            // 网关 runStream 内部已套 SessionTurnGate（按 canonicalKey 串行化）+ 注册表解析 + 身份富化，
            // 返回与 streamEvents 同构的 AgentEvent 流，本协议适配器直接消费。
            return gateway.runStream(msgContext, command.messages(), null, callerContext);
        });
    }

    @Override
    public Mono<Settle> settle(AgentCommand command) {
        return execute(command)
                .filter(event -> event instanceof AgentResultEvent)
                .map(event -> ((AgentResultEvent) event).getResult())
                .map(result -> result == null ? "" : result.getTextContent())
                .last("")
                .map(text -> Settle.ok(command, text))
                .onErrorResume(error -> {
                    Settle settle = Settle.fromError(command, error);
                    // 仅真实执行故障（非客户端可预期的 NO_AGENT）记录完整堆栈，便于服务端排障，
                    // 同时根因已随 settle.description 透传到协议响应体，客户端无需依赖日志即可定位。
                    if (settle.status() == Settle.Status.EXECUTION_ERROR) {
                        log.error("execution failed: agentId={}, userId={}, sessionId={}, description={}",
                                command.agentId(), command.userId(), command.sessionId(),
                                settle.description(), error);
                    }
                    return Mono.just(settle);
                });
    }

    private String turnThreadId(AgentCommand command) {
        String sessionId = command.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = command.agentId();
        }
        return (command.userId() == null ? "" : command.userId()) + ":" + sessionId;
    }

    private RuntimeContext intentCallerContext(AgentCommand command) {
        if (command.intent() == null || command.intent().isBlank()) {
            return null;
        }
        return RuntimeContext.builder()
                .put(IntentSignalMiddleware.CTX_KEY, command.intent().strip())
                .build();
    }
}
