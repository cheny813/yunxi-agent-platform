package io.yunxi.capability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 能力层。模型目录配置（外部化到 {@code application.yml}，不再硬编码于代码）。
 *
 * <p>三层结构：
 * <ul>
 *     <li>{@code catalog}：命名模型清单（provider / model / apiKeyEnv / type）；</li>
 *     <li>{@code routing}：agentId → 主模型 + 兜底；</li>
 *     <li>{@code defaults}：各用途分类的默认模型（llm / decision / embedding）。</li>
 * </ul>
 * 未来接入本地决策模型、本地矢量模型时，仅需在此新增 catalog 条目与 defaults 键，无需改代码。</p>
 */
@ConfigurationProperties(prefix = "yunxi.models")
public class ModelProperties {

    private Map<String, ModelEntry> catalog = new HashMap<>();

    private Map<String, AgentRoute> routing = new HashMap<>();

    private Map<String, String> defaults = new HashMap<>();

    public Map<String, ModelEntry> getCatalog() {
        return catalog;
    }

    public void setCatalog(Map<String, ModelEntry> catalog) {
        this.catalog = catalog;
    }

    public Map<String, AgentRoute> getRouting() {
        return routing;
    }

    public void setRouting(Map<String, AgentRoute> routing) {
        this.routing = routing;
    }

    public Map<String, String> getDefaults() {
        return defaults;
    }

    public void setDefaults(Map<String, String> defaults) {
        this.defaults = defaults;
    }
}
