package io.yunxi.platform.execution.interceptors;

import org.springframework.stereotype.Component;

import io.yunxi.platform.agent.profile.ProfileRouter;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.security.auth.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 拦截器 100：认证与 Agent 解析。
 *
 * <p>职责：
 * <ol>
 *   <li>从 {@link SecurityContext} 解析当前用户 ID 写入 ctx（用户不可用时降级为 null）；</li>
 *   <li>通过 {@link ProfileRouter#resolve(String, String)} 解析最终执行的 Agent 实例写入 ctx。</li>
 * </ol>
 * 这是所有后续拦截器的前置依赖（Memory/IntentPipeline 都需要 agentName 与 userId）。
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class AuthResolveInterceptor implements ExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthResolveInterceptor.class);

    private final SecurityContext securityContext;
    private final ProfileRouter profileRouter;

    public AuthResolveInterceptor(SecurityContext securityContext, ProfileRouter profileRouter) {
        this.securityContext = securityContext;
        this.profileRouter = profileRouter;
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        // 用户 ID 解析（不可用时降级为 null）
        String userId = ctx.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = securityContext.getCurrentUserId();
            // 回写 ctx，确保下游拦截器/策略/审计一致读到解析结果（attribute 仅为日志兼容保留）
            ctx.setUserId(userId);
            ctx.setAttribute("userId", userId);
        }
        log.debug("AuthResolve: agentName={}, profile={}, userId={}",
                ctx.getAgentName(), ctx.getRequest().getProfile(), userId);

        // 解析最终 Agent 实例（支持 profile 叠加与用户工作区隔离）
        io.agentscope.core.agent.Agent agent =
                profileRouter.resolve(ctx.getAgentName(), ctx.getRequest().getProfile());
        ctx.setResolvedAgent(agent);
    }
}
