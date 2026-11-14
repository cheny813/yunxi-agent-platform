package io.yunxi.runtime;

import io.agentscope.core.agent.RuntimeContext;
import java.util.List;

/**
 * 运行时平面。{@link RuntimeContextEnricher} 的组合，按注册顺序依次应用。
 */
public class RuntimeContextEnrichers {

    private final List<RuntimeContextEnricher> enrichers;

    public RuntimeContextEnrichers(List<RuntimeContextEnricher> enrichers) {
        this.enrichers = enrichers != null ? enrichers : List.of();
    }

    public void enrich(RuntimeContext.Builder builder, AgentCommand command) {
        for (RuntimeContextEnricher enricher : enrichers) {
            enricher.enrich(builder, command);
        }
    }

    /** 返回本次组合持有的全部 {@link RuntimeContextEnricher}（供调试 / 扩展只读访问）。 */
    public List<RuntimeContextEnricher> all() {
        return enrichers;
    }
}
