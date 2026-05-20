package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.business.nutrition.service.RecipeStreamService;
import io.yunxi.platform.framework.spi.DomainContributor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校园餐领域贡献器
 *
 * <p>实现 DomainContributor SPI，贡献校园餐领域的关键词模式和 Agent 能力。</p>
 * <p>覆盖校园餐7个业务模块：食品安全、营养配餐、经费管理、集采-校园、集采-国企、消费管理、预警</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class SchoolMealDomainContributor implements DomainContributor {

    /** 校园餐领域关键词模式 */
    private static final Map<String, List<Pattern>> DOMAIN_PATTERNS = Map.of(
            "school-meal", List.of(
                    Pattern.compile("(?i)(营养|食谱|菜品|配餐|配平|膳食|食材|菜单)"),
                    Pattern.compile("(?i)(校园餐|食堂|供餐|用餐|餐次)"),
                    Pattern.compile("(?i)(食品安全|溯源|卫生|抽检|过期)"),
                    Pattern.compile("(?i)(经费|成本|预算|核算|费用|伙食费)"),
                    Pattern.compile("(?i)(集采|采购|供应商|招标)"),
                    Pattern.compile("(?i)(消费|充值|余额|消费记录)"),
                    Pattern.compile("(?i)(预警|超标|异常|告警|风险)")
            )
    );

    /** nutrition-assistant Agent 能力 */
    private static final Map<String, Set<String>> AGENT_CAPABILITIES = Map.of(
            RecipeStreamService.NUTRITION_AGENT_NAME, Set.of(
                    "knowledge_query", "data_analysis", "tool_calling", "planning",
                    "search", "database_access", "calculation"
            )
    );

    /**
     * 获取校园餐领域关键词模式映射
     *
     * @return 领域名称到正则模式列表的映射
     */
    @Override
    public Map<String, List<Pattern>> getDomainPatterns() {
        return DOMAIN_PATTERNS;
    }

    /**
     * 获取 Agent 能力映射
     *
     * @return Agent 名称到能力集合的映射
     */
    @Override
    public Map<String, Set<String>> getAgentCapabilities() {
        return AGENT_CAPABILITIES;
    }
}
