package io.yunxi.platform.intent.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.agentscope.core.agent.Agent;
import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.agent.service.AgentService;
import io.yunxi.platform.intent.IntentResult;
import io.yunxi.platform.intent.RouteHint;
import io.yunxi.platform.intent.config.IntentProperties;

/**
 * 意图路由解析器（M2.1：识别→路由闭环）。
 *
 * <p>在 Profile 路由之后、默认 Agent 之前消费 {@link RouteHint}，决定是否改道。
 * 永远 advisory：开关关闭 / 无 hint / 分数不足 / 目标 agent 不存在，一律返回未改道，
 * 由调用方走默认解析链。采纳率全量日志（{@code route.adopted=0/1 reason=...}）
 * 便于观测、误判可回滚。</p>
 *
 * <p>路由优先级链（M2.1 落位）：显式 agent → Profile 路由 →【意图路由】→ 默认 agent。</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Component
public class IntentAwareAgentResolver {

    private static final Logger log = LoggerFactory.getLogger(IntentAwareAgentResolver.class);

    private final IntentProperties properties;
    private final AgentService agentService;
    private final ProfileRouter profileRouter;

    public IntentAwareAgentResolver(IntentProperties properties, AgentService agentService,
            ProfileRouter profileRouter) {
        this.properties = properties;
        this.agentService = agentService;
        this.profileRouter = profileRouter;
    }

    /**
     * 解析意图路由决策。
     *
     * @param baseName     当前会话绑定的 Agent 名（原路由，仅用于日志）
     * @param profile      Profile 名（可 null；非空时命中目标 agent 仍尊重 Profile 路由）
     * @param userId       用户 ID（预留：M2.2 领域解析用）
     * @param intentResult 意图分析结果（routeHint 为 null / empty 时视为无路由建议）
     * @return 路由决策（adopted=false 时调用方保持原路由）
     */
    public RouteDecision resolve(String baseName, String profile, String userId, IntentResult intentResult) {
        // 1. 开关关闭：不参与路由
        if (!properties.isRoutingEnabled()) {
            log.debug("[INTENT] route.adopted=0 reason=disabled agent={}", baseName);
            return RouteDecision.notAdopted();
        }
        // 2. 无路由建议
        if (intentResult == null || intentResult.routeHint() == null || intentResult.routeHint().isEmpty()) {
            log.debug("[INTENT] route.adopted=0 reason=no-hint agent={}", baseName);
            return RouteDecision.notAdopted();
        }
        RouteHint hint = intentResult.routeHint();
        // 3. 分数不足：低于门槛不改道
        if (hint.score() < properties.getRouting().getMinRouteScore()) {
            log.info("[INTENT] route.adopted=0 reason=low-score score={} min={} agent={}",
                    hint.score(), properties.getRouting().getMinRouteScore(), baseName);
            return RouteDecision.notAdopted();
        }
        // 4. 目标 agent 必须真实存在：AgentService.getAgentInstance 不存在时抛
        //    NotFoundException（永不返回 null），与 ProfileRouter.resolve 的降级 try-catch 一致
        Agent target;
        try {
            target = agentService.getAgentInstance(hint.suggestedAgent());
        } catch (Exception e) {
            log.warn("[INTENT] 意图路由目标 agent 不存在，保持原路由: {}", hint.suggestedAgent());
            return RouteDecision.notAdopted();
        }
        // 5. 命中目标 agent 仍尊重 Profile 路由（Profile 优先级高于意图路由）
        Agent finalAgent = (profile != null && !profile.isBlank())
                ? profileRouter.resolve(hint.suggestedAgent(), profile)
                : target;
        log.info("[INTENT] route.adopted=1 reason=intent from={} to={} score={} experts={} toolGroups={} skills={}",
                baseName, hint.suggestedAgent(), hint.score(),
                hint.suggestedExperts(), hint.toolGroups(), hint.skills());
        return RouteDecision.adopted(finalAgent, hint.suggestedExperts(), hint.toolGroups(), hint.skills());
    }
}
