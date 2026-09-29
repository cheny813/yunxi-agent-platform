package io.yunxi.platform.execution.spi;

import java.util.List;

import io.agentscope.core.middleware.MiddlewareBase;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.mapper.ConversationMapper;

/**
 * 执行引擎横切中间件贡献者 SPI（驻留 agent-core）。
 *
 * <p>agent-execution 实现本接口，把"指标 / 阶段 / 审计"等执行引擎私有的横切中间件贡献给
 * agent-core 的 {@code AgentConfigurer}，从而避免在 agent-core 中反向 import agent-execution
 * 的具体中间件类，从根上断开循环依赖。装配点仍是 {@code AgentConfigurer.configureMiddlewares}，
 * 由它收集全部 {@code AgentMiddlewareContributor} 并逐个 {@code contribute} 挂到每个 Agent。</p>
 *
 * @author yunxi-agent-platform
 */
public interface AgentMiddlewareContributor {

    /**
     * 贡献该 Agent 运行时所需的中间件。
     *
     * @param def                  Agent 定义（含 agent 名称）
     * @param conversationMapper   会话 Mapper；为 null 时贡献者不应产出审计等依赖落库的中间件
     * @return 中间件列表（顺序由各中间件自身 {@code order()} 决定，调用方按序挂接即可）
     */
    List<MiddlewareBase> contribute(AgentDefinition def, ConversationMapper conversationMapper);
}
