package io.yunxi.platform.framework.spi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 领域贡献器 SPI
 *
 * <p>业务层实现此接口，贡献自定义领域的关键词模式和 Agent 能力映射。</p>
 * <p>框架层 TaskAnalyzer、AgentSelector 通过收集所有实现来支持可扩展的领域体系。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface DomainContributor {

    /**
     * 贡献的领域关键词模式
     *
     * @return Map&lt;领域名称, 关键词模式列表&gt;，如 {"nutrition": [Pattern.compile("营养|食谱"), ...]}
     */
    Map<String, List<Pattern>> getDomainPatterns();

    /**
     * 贡献的 Agent 能力映射
     *
     * @return Map&lt;Agent名称, 能力集合&gt;，如 {"nutrition-assistant": Set.of("search", "calculation")}
     */
    Map<String, Set<String>> getAgentCapabilities();
}
