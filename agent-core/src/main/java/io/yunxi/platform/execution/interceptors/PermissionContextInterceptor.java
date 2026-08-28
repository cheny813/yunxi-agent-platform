package io.yunxi.platform.execution.interceptors;

import org.springframework.stereotype.Component;

import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.yunxi.platform.config.PermissionConfig;
import io.yunxi.platform.execution.ExecutionContext;
import io.yunxi.platform.execution.spi.ExecutionInterceptor;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.ExtensionConfig;
import io.yunxi.platform.shared.config.HITLConfig;

/**
 * 请求级权限上下文拦截器（order=400）。
 *
 * <p>判定逻辑与 AgentConfigurer 构建期注入 {@code Builder.permissionContext} 完全一致，
 * 复用 {@link PermissionConfig#build} 与 {@link PermissionConfig#hasAskTools} 两个入口。
 * 因 AgentScope GA 无 per-call 权限注入 API（仅构建时注入 → per-session AgentState），
 * 本拦截器落为"请求级权限快照"：供状态上报 / 审计等请求维度消费，
 * 不伪造调用时注入，实际权限执行仍由 AgentScope 构建期 PermissionContextState 承担。</p>
 *
 * <p>模式决定与 AgentConfigurer#injectHITLMiddlewares 完全一致：
 * 未配置 HITL → {@code DONT_ASK}（无人值守安全姿态，防工具挂起）；
 * 有 ASK 工具 → {@code DEFAULT}（挂起向用户确认）；否则 → {@code BYPASS}（全放行）。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class PermissionContextInterceptor implements ExecutionInterceptor {

    private final AgentDefinitionLoader agentDefinitionLoader;
    private final PermissionConfig permissionConfig;

    public PermissionContextInterceptor(AgentDefinitionLoader agentDefinitionLoader,
                                        PermissionConfig permissionConfig) {
        this.agentDefinitionLoader = agentDefinitionLoader;
        this.permissionConfig = permissionConfig;
    }

    @Override
    public int getOrder() {
        return 400;
    }

    @Override
    public void preHandle(ExecutionContext ctx) {
        AgentDefinition def = agentDefinitionLoader.getAgentDefinition(ctx.getAgentName());
        ExtensionConfig extensions = def == null ? null : def.getExtensions();
        HITLConfig hitl = extensions == null ? null : extensions.getHitl();
        if (hitl == null) {
            // 无人值守安全姿态：不配置 HITL 即不做人工确认（防工具调用挂起等待）
            ctx.setPermissionContext(PermissionContextState.builder().mode(PermissionMode.DONT_ASK).build());
            return;
        }
        PermissionMode mode = PermissionConfig.hasAskTools(hitl)
                ? PermissionMode.DEFAULT
                : PermissionMode.BYPASS;
        ctx.setPermissionContext(permissionConfig.build(hitl, mode));
    }
}
