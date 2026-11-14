package io.yunxi.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.yunxi.capability.ConfigurableModelRoutingPolicy;
import io.yunxi.capability.RoutedModel;
import io.yunxi.capability.TaskSignals;
import java.util.ArrayList;
import java.util.List;
import reactor.core.publisher.Flux;

/**
 * 自动选型模型 provider：实现 AgentScope-Java 的 {@link Model} 接口，每次 {@link #stream} 调用时
 * 按任务信号动态选择候选模型并委托执行，从而让「构建期固定模型」的 Agent 获得「请求期动态性」
 * 而不修改框架；候选模型经框架注册表按 provider:model 解析。
 *
 * <p>选型决策在 {@link ConfigurableModelRoutingPolicy#resolveAuto}（能力平面，协议无关），
 * 候选模型解析复用现有 {@link ModelFactory}（与固定路由一致的解析路径）；多级回退复用现有
 * {@link FailoverModelProvider}，避免重复实现。
 */
public class AutoModelProvider implements Model {

    private final String agentId;
    private final ConfigurableModelRoutingPolicy policy;
    private final ModelFactory modelFactory;
    private final int representativeContextWindow;

    public AutoModelProvider(String agentId, ConfigurableModelRoutingPolicy policy, ModelFactory modelFactory) {
        this.agentId = agentId;
        this.policy = policy;
        this.modelFactory = modelFactory;
        this.representativeContextWindow = policy.maxCandidateContextWindow();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        TaskSignals signals = TaskSignals.from(messages, tools, options);
        RoutedModel routed = policy.resolveAuto(agentId, signals);
        List<Model> candidates = new ArrayList<>();
        Model primary = modelFactory.create(routed.primaryModelId());
        if (primary != null) {
            candidates.add(primary);
        }
        if (routed.fallbackModelIds() != null) {
            for (String id : routed.fallbackModelIds()) {
                Model m = modelFactory.create(id);
                if (m != null) {
                    candidates.add(m);
                }
            }
        }
        if (candidates.isEmpty()) {
            return Flux.error(new IllegalStateException(
                    "auto 模型选择未解析出任何可用候选（agent=" + agentId
                            + "），请检查 yunxi.models.catalog 中 llm 项的 provider 是否在 classpath 且 API Key 已注入。"));
        }
        return FailoverModelProvider.of(candidates.get(0), candidates.subList(1, candidates.size()))
                .stream(messages, tools, options);
    }

    @Override
    public String getModelName() {
        return "auto:" + agentId;
    }

    /**
     * 返回候选池最大上下文窗口作为兜底值（压缩中间件据此判断是否触压）；精确窗口以实际选中模型为准。
     */
    @Override
    public int getContextWindowSize() {
        return representativeContextWindow;
    }
}
