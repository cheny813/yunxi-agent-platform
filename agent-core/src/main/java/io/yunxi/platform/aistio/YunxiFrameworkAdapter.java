package io.yunxi.platform.aistio;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.aistio.FrameworkAdapter;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.SessionEvent;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.intent.IntentContext;
import io.yunxi.platform.intent.IntentEngine;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.RouteHint;

/**
 * yunxi 的 aistio 框架适配实现。
 *
 * <p>AgentScope-Java 的官方参考实现 {@link AgentScopeAdapter} 是 {@code final} 类，无法继承；
 * 这里采用组合方式复用它的契约/上下文能力（{@code capabilities}、{@code extractContext}、
 * {@code buildAgentConfig}、{@code canHandle}/{@code attach}/{@code detach}），仅覆写
 * {@link #runConversationTurn(String, String)} 以接入 yunxi 的多租户模型与意图引擎分流。</p>
 *
 * <p>yunxi 的对话模型是「每次调用新建 Agent 实例」（{@code AgentService.getAgentInstance} 返回新实例，
 * 保证线程安全），因此本适配器在每次对话轮次中按路由结果取得一个全新 Agent，并携带
 * {@code (userId, sessionId)} 的 {@link RuntimeContext} 调用——与 yunxi 既有 web 聊天路径完全一致，
 * 不同 aistio 会话由唯一 {@code sessionId} 隔离，控制台用户统一落到 {@code consoleUserId} 状态槽。</p>
 *
 * <p>路由策略：每一轮对话都先做意图识别（复用本仓意图引擎 + 意图映射表），按
 * {@link RouteHint#suggestedAgent()} 改道；意图无建议或意图引擎降级时回落 {@code defaultAgentName}。
 * 这与 web 路径的 {@code IntentAwareAgentResolver} 行为对齐，使 aistio 控制台与 web 入口路由一致。</p>
 *
 * <p>只声明 {@link FrameworkAdapter#CAP_CONVERSATION_INBOUND} 能力：端到端对话所需的唯一能力；
 * 其余治理/查询能力由控制面按能力清单驱动，过度声明会产生会失败的按钮，故不声明。</p>
 */
public class YunxiFrameworkAdapter implements FrameworkAdapter {

    private static final Logger log = LoggerFactory.getLogger(YunxiFrameworkAdapter.class);

    /** 复用官方参考实现处理契约/上下文等通用能力 */
    private final AgentScopeAdapter delegate = new AgentScopeAdapter();

    private final AgentService agentService;
    private final IntentEngine intentEngine;
    /** 回落默认 agent（未命中意图或意图引擎降级时使用）；多 agent 注册下即该 bridge 对应的 agent 名 */
    private final String defaultAgentName;
    private final String consoleUserId;

    public YunxiFrameworkAdapter(AgentService agentService,
            IntentEngine intentEngine,
            String defaultAgentName,
            String consoleUserId) {
        this.agentService = agentService;
        this.intentEngine = intentEngine;
        this.defaultAgentName = defaultAgentName;
        this.consoleUserId = consoleUserId;
    }

    @Override
    public String frameworkName() {
        return "yunxi-agent-platform";
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
    public Map<String, Object> buildAgentConfig() {
        return delegate.buildAgentConfig();
    }

    @Override
    public Set<String> capabilities() {
        // 仅声明端到端对话所需能力；避免过度声明导致控制面出现会失败的治理按钮
        return Set.of(CAP_CONVERSATION_INBOUND);
    }

    @Override
    public Mono<String> runConversationTurn(String sessionId, String content) {
        try {
            String targetAgent = resolveTargetAgent(sessionId, content);
            Agent agent = agentService.getAgentInstance(targetAgent);
            Msg msg = Msg.builder().textContent(content).build();
            RuntimeContext rc = RuntimeContext.builder().userId(consoleUserId).sessionId(sessionId).build();
            Msg result = ((HarnessAgent) agent).call(msg, rc).block();
            String text = (result != null && result.getTextContent() != null) ? result.getTextContent() : "";
            return Mono.just(text);
        } catch (Throwable t) {
            log.error("aistio 对话轮次执行失败 sessionId={}: {}", sessionId, t.getMessage(), t);
            return Mono.error(t);
        }
    }

    /**
     * 按意图识别结果选定本轮执行的 agent。
     *
     * <p>意图引擎返回 {@link RouteHint#suggestedAgent()} 且该 agent 在 yunxi 侧真实存在时，改道到建议 agent；
     * 否则（无建议 / 建议 agent 不存在 / 意图引擎异常降级）回落 {@code defaultAgentName}，保证对话不中断。</p>
     */
    private String resolveTargetAgent(String sessionId, String content) {
        try {
            IntentContext ctx = new IntentContext(
                    content, defaultAgentName, consoleUserId, sessionId, List.of(), null, null);
            IntentResult result = intentEngine.analyze(ctx);
            RouteHint hint = result.routeHint();
            if (hint != null && !hint.isEmpty()) {
                String suggested = hint.suggestedAgent();
                if (suggested != null && !suggested.isBlank() && agentService.findAgent(suggested) != null) {
                    if (!suggested.equals(defaultAgentName)) {
                        String intentId = result.intent() != null ? result.intent().intentId() : "-";
                        log.info("[AISTIO-ROUTE] session={} intent={} 改道 {} -> {}",
                                sessionId, intentId, defaultAgentName, suggested);
                    }
                    return suggested;
                }
            }
        } catch (Exception e) {
            log.warn("[AISTIO-ROUTE] 意图识别失败，回落默认 agent {}: {}", defaultAgentName, e.getMessage());
        }
        return defaultAgentName;
    }
}
