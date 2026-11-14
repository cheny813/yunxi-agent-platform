package io.yunxi.capability;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力层。单个 Agent 的模型路由（纯数据，由 {@code yunxi.models.routing} 绑定）。
 *
 * <p>primary 为目录中的逻辑模型名；fallbacks 为同目录逻辑名清单，熔断 / 限流时按顺序兜底。</p>
 */
public class AgentRoute {

    /** 主模型的逻辑名（对应 {@code yunxi.models.catalog} 的键）。 */
    private String primary;

    /** 兜底模型的逻辑名清单（按顺序尝试）。 */
    private List<String> fallbacks = new ArrayList<>();

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary;
    }

    public List<String> getFallbacks() {
        return fallbacks;
    }

    public void setFallbacks(List<String> fallbacks) {
        this.fallbacks = fallbacks;
    }
}
