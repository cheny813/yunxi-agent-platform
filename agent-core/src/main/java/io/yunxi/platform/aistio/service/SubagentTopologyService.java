package io.yunxi.platform.aistio.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.yunxi.platform.aistio.dto.SubagentInfo;
import io.yunxi.platform.shared.config.AgentDefinition;
import io.yunxi.platform.shared.config.AgentDefinitionLoader;
import io.yunxi.platform.shared.config.ExpertConfig;
import io.yunxi.platform.shared.config.OrchestrationConfig;
import lombok.RequiredArgsConstructor;

/**
 * 构建子智能体 / 编排拓扑（{@code GET /agentscope/subagents}）。
 *
 * <p>数据源为运行时 {@code AgentDefinition.orchestration}（由 {@link AgentDefinitionLoader} 加载），
 * <b>不是</b> {@code AgentInfoDto}（后者不含 orchestration 字段，由 aistio 子智能体拓扑补充）。
 * supervisor / routing 模式的 experts 作为递归子节点展开。</p>
 */
@Component
@RequiredArgsConstructor
public class SubagentTopologyService {

    private final AgentDefinitionLoader definitionLoader;

    /**
     * 获取全部 Agent 的编排拓扑。
     *
     * @return 子智能体信息列表
     */
    public List<SubagentInfo> getTopology() {
        List<SubagentInfo> out = new ArrayList<>();
        for (AgentDefinition def : definitionLoader.getAgentDefinitions()) {
            out.add(toInfo(def));
        }
        return out;
    }

    private SubagentInfo toInfo(AgentDefinition def) {
        SubagentInfo info = new SubagentInfo();
        info.setName(def.getName());
        info.setDescription(def.getDescription());

        OrchestrationConfig orch = def.getOrchestration();
        if (orch != null) {
            info.setPattern(orch.getPattern());
            List<ExpertConfig> experts = orch.getExperts();
            if (experts != null && !experts.isEmpty()) {
                List<SubagentInfo> children = new ArrayList<>();
                for (ExpertConfig expert : experts) {
                    SubagentInfo child = new SubagentInfo();
                    child.setName(expert.getName());
                    child.setDescription(expert.getDescription());
                    child.setPattern("single");
                    children.add(child);
                }
                info.setChildren(children);
            }
        } else {
            info.setPattern("single");
        }
        return info;
    }
}
