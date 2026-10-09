package io.yunxi.platform.execution;

import java.util.ArrayList;
import java.util.List;

import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.execution.middleware.AuditMiddleware;
import io.yunxi.platform.execution.middleware.MetricsMiddleware;
import io.yunxi.platform.execution.middleware.PhaseMiddleware;
import io.yunxi.platform.execution.spi.AgentMiddlewareContributor;
import io.yunxi.platform.chat.ChatMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 执行引擎横切中间件贡献者（agent-execution 侧实现）。
 *
 * <p>把指标 / 阶段 / 审计三类执行引擎私有的横切中间件贡献给 agent-core 的 {@code AgentConfigurer}，
 * 避免在 agent-core 中反向 import agent-execution 的具体实现，从根上断开循环依赖。装配点仍是
 * {@code AgentConfigurer.configureMiddlewares}，由它收集全部 {@code AgentMiddlewareContributor}
 * 并逐个 {@code contribute} 挂到每个 Agent。</p>
 *
 * <p>{@code PhaseMiddleware} 同时是 {@code PhaseQuery} 的 Spring Bean（入口网关据此查询阶段轨迹），
 * 故此处注入其单例并复用，确保「记录轨迹的中间件实例」与「被查询的 bean 实例」为同一对象，
 * 否则控制器会读到另一个空实例。</p>
 *
 * @author yunxi-agent-platform
 */
@Component
public class ExecutionMiddlewareContributor implements AgentMiddlewareContributor {

    private final PhaseMiddleware phaseMiddleware;
    private final MetricsMiddleware metricsMiddleware = new MetricsMiddleware();

    @Autowired
    public ExecutionMiddlewareContributor(PhaseMiddleware phaseMiddleware) {
        this.phaseMiddleware = phaseMiddleware;
    }

    @Override
    public List<MiddlewareBase> contribute(AgentDefinition def, ChatMapper chatMapper) {
        List<MiddlewareBase> middlewares = new ArrayList<>();
        middlewares.add(metricsMiddleware);
        middlewares.add(phaseMiddleware);
        if (chatMapper != null) {
            middlewares.add(new AuditMiddleware(chatMapper, def.getName()));
        }
        return middlewares;
    }
}
