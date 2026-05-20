package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.business.nutrition.memory.RecipeMemoryService;
import io.yunxi.platform.business.nutrition.sync.SchoolDishVectorSyncService;
import io.yunxi.platform.framework.spi.ContextEnricher;
import io.yunxi.platform.framework.sync.McpQueryService;
import io.yunxi.platform.shared.util.TextParserUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 食谱上下文增强器
 *
 * <p>
 * 实现 ContextEnricher SPI，为食谱配平场景提供向量搜索增强、数据格式化和提示追加。
 * </p>
 * <p>
 * 原 ChatAppService 中食谱相关的硬编码逻辑全部迁移至此。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class RecipeContextEnricher implements ContextEnricher {

    /** 食谱配平场景标识 */
    private static final String PAGE_TYPE_RECIPE_MAKE = "recipe-make";

    /** 食谱相关上下文 key */
    private static final Set<String> RECIPE_KEYS = Set.of(
            "selectedRecipes", "scoreInfo", "nutritionGuidelines",
            "ingredientIds", "schoolId");

    /** 中文关键词提取模式 */
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    /** 菜品向量同步搜索服务 */
    @Autowired
    private SchoolDishVectorSyncService dishVectorSyncService;

    /** 食谱记忆服务（可选依赖） */
    @Autowired(required = false)
    private RecipeMemoryService recipeMemoryService;

    /** MCP 数据库查询服务 */
    @Autowired(required = false)
    private McpQueryService mcpQueryService;

    /** MCP 数据库主机地址 */
    @Value("${dish-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口 */
    @Value("${dish-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 学校信息本地缓存（学校ID -> 学校信息Map） */
    private final ConcurrentHashMap<Long, Map<String, String>> schoolInfoCache = new ConcurrentHashMap<>();

    /**
     * 判断是否支持给定的上下文数据
     *
     * @param contextData 上下文数据
     * @return 若页面类型为食谱配平或包含食谱相关 key 则返回 true，否则返回 false
     */
    @Override
    public boolean supports(Map<String, Object> contextData) {
        if (contextData == null)
            return false;
        Object pageType = contextData.get("pageType");
        if (PAGE_TYPE_RECIPE_MAKE.equals(pageType))
            return true;
        // 也支持包含食谱相关 key 的场景
        return RECIPE_KEYS.stream().anyMatch(contextData::containsKey);
    }

    /**
     * 增强上下文：提取关键词搜索菜品、构建食材信息、注入食谱记忆
     *
     * @param contextData 上下文数据
     * @param userMessage 用户消息
     * @return 增强后的上下文字符串
     */
    @Override
    public String enrich(Map<String, Object> contextData, String userMessage) {
        StringBuilder enriched = new StringBuilder();

        // 1. 提取关键词并搜索相关菜品
        Long schoolId = extractSchoolId(contextData);
        List<String> keywords = extractKeywords(userMessage);
        if (!keywords.isEmpty() && schoolId != null) {
            String searchQuery = String.join(" ", keywords);
            List<SchoolDishVectorSyncService.DishData> dishes = dishVectorSyncService.searchDishesBySchool(schoolId,
                    searchQuery, 5);
            if (!dishes.isEmpty()) {
                enriched.append("\n【相关菜品参考】\n");
                for (SchoolDishVectorSyncService.DishData dish : dishes) {
                    enriched.append("- ").append(dish.getName());
                    if (dish.getIngredientIds() != null && !dish.getIngredientIds().isEmpty()) {
                        enriched.append(" (食材: ").append(dish.getIngredientIds()).append(")");
                    }
                    enriched.append("\n");
                }
            }
        }

        // 2. 从食材ID列表构建食材信息
        Object ingredientIdsObj = contextData.get("ingredientIds");
        if (ingredientIdsObj != null) {
            String fiList = buildFiListFromIngredientIds(ingredientIdsObj);
            if (!fiList.isEmpty()) {
                enriched.append("\n【食材信息】\n").append(fiList).append("\n");
            }
        }

        // 3. 注入食谱记忆上下文
        if (schoolId != null && recipeMemoryService != null) {
            try {
                String recipeContext = recipeMemoryService.buildRecipeContextPrompt(String.valueOf(schoolId));
                if (recipeContext != null && !recipeContext.isBlank()) {
                    enriched.append("\n").append(recipeContext);
                }
            } catch (Exception e) {
                log.debug("注入食谱记忆失败: {}", e.getMessage());
            }
        }

        // 4. 预查询学校信息（名称、区域、类型），避免Agent动态探索数据库
        if (schoolId != null) {
            Map<String, String> schoolInfo = getSchoolInfo(schoolId);
            if (!schoolInfo.isEmpty()) {
                enriched.append("\n【学校信息（预置）】\n");
                schoolInfo.forEach(
                        (key, value) -> enriched.append("- ").append(key).append(": ").append(value).append("\n"));
            }
        }

        return enriched.toString();
    }

    /**
     * 格式化指定 key 的值为可读字符串
     *
     * @param key   上下文键名
     * @param value 对应的值
     * @return 格式化后的字符串，若 key 不属于食谱相关则返回 null
     */
    @Override
    public String formatKey(String key, Object value) {
        if (!RECIPE_KEYS.contains(key))
            return null;

        return switch (key) {
            case "selectedRecipes" -> "已选菜品: " + formatValue(value);
            case "scoreInfo" -> "评分信息: " + formatValue(value);
            case "nutritionGuidelines" -> "营养标准: " + formatValue(value);
            case "ingredientIds" -> "食材ID列表: " + formatValue(value);
            case "schoolId" -> "学校ID: " + value;
            default -> null;
        };
    }

    /**
     * 追加配平优化策略和数据返回要求提示词
     *
     * @param contextData 上下文数据
     * @return 追加的提示词字符串，若非食谱配平页面则返回 null
     */
    @Override
    public String appendPrompt(Map<String, Object> contextData) {
        Object pageType = contextData.get("pageType");
        if (!PAGE_TYPE_RECIPE_MAKE.equals(pageType))
            return null;

        StringBuilder prompt = new StringBuilder();
        prompt.append("\n【配平优化策略】\n");
        prompt.append("1. 确保每餐蛋白质、脂肪、碳水化合物比例合理\n");
        prompt.append("2. 优先选择当季新鲜食材\n");
        prompt.append("3. 避免连续两天出现相同菜品\n");
        prompt.append("4. 关注钙、铁、维生素等微量营养素达标\n");
        prompt.append("\n【返回数据要求】\n");
        prompt.append("请以 JSON 格式返回食谱数据，包含：菜品名称、食材用量、营养分析、评分\n");
        return prompt.toString();
    }

    // ==================== 私有方法 ====================

    /**
     * 从上下文数据中提取学校ID
     *
     * @param contextData 上下文数据
     * @return 学校ID，提取失败则返回 null
     */
    private Long extractSchoolId(Map<String, Object> contextData) {
        Object schoolId = contextData.get("schoolId");
        if (schoolId == null) {
            // 尝试从 configSummary 中提取
            Object configSummary = contextData.get("configSummary");
            if (configSummary instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) configSummary;
                schoolId = config.get("schoolId");
            }
        }
        if (schoolId instanceof Number) {
            return ((Number) schoolId).longValue();
        }
        if (schoolId instanceof String) {
            try {
                return Long.parseLong((String) schoolId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从用户消息中提取中文关键词
     *
     * @param userMessage 用户消息文本
     * @return 去重后的关键词列表（最多5个）
     */
    private List<String> extractKeywords(String userMessage) {
        if (userMessage == null || userMessage.isBlank())
            return List.of();

        List<String> keywords = new ArrayList<>();
        Matcher matcher = CHINESE_PATTERN.matcher(userMessage);
        while (matcher.find()) {
            keywords.add(matcher.group());
        }
        return keywords.stream().distinct().limit(5).collect(Collectors.toList());
    }

    /**
     * 从食材ID列表构建食材信息字符串
     *
     * @param ingredientIdsObj 食材ID列表对象（List 或 String）
     * @return 逗号分隔的食材ID字符串，无效输入返回空字符串
     */
    private String buildFiListFromIngredientIds(Object ingredientIdsObj) {
        if (ingredientIdsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> ids = (List<Object>) ingredientIdsObj;
            return ids.stream().map(Object::toString).collect(Collectors.joining(", "));
        }
        if (ingredientIdsObj instanceof String) {
            return (String) ingredientIdsObj;
        }
        return "";
    }

    /**
     * 格式化值为可读字符串
     *
     * @param value 待格式化的值
     * @return 格式化后的字符串，null 返回空字符串
     */
    private String formatValue(Object value) {
        if (value == null)
            return "";
        if (value instanceof Map || value instanceof List) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(value);
            } catch (Exception e) {
                return value.toString();
            }
        }
        return value.toString();
    }

    /**
     * 获取学校信息（带缓存）
     *
     * <p>
     * 通过 MCP 数据库查询学校名称、区域信息、学校类型，结果缓存避免重复查询。
     * </p>
     *
     * @param schoolId 学校ID
     * @return 学校信息Map（key: 中文描述, value: 值）
     */
    private Map<String, String> getSchoolInfo(Long schoolId) {
        // 先从缓存获取
        Map<String, String> cached = schoolInfoCache.get(schoolId);
        if (cached != null) {
            return cached;
        }

        if (mcpQueryService == null) {
            return Map.of();
        }

        try {
            Map<String, String> info = new LinkedHashMap<>();

            // 查询学校信息
            String schoolSql = "SELECT id, name, district_id FROM school_user WHERE id = " + schoolId
                    + " AND deleted = 0";
            String schoolResult = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, schoolSql);
            if (schoolResult != null && !schoolResult.contains("(无数据)")) {
                String schoolName = TextParserUtil.parseString(schoolResult, "name");
                String districtId = TextParserUtil.parseString(schoolResult, "district_id");
                if (schoolName != null)
                    info.put("学校名称", schoolName);

                // 查询区域信息
                if (districtId != null && !districtId.isEmpty()) {
                    String districtSql = "SELECT id, district_name, province_code, district_code, school_type FROM district_info WHERE id = "
                            + districtId;
                    String districtResult = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, districtSql);
                    if (districtResult != null && !districtResult.contains("(无数据)")) {
                        String districtName = TextParserUtil.parseString(districtResult, "district_name");
                        String schoolType = TextParserUtil.parseString(districtResult, "school_type");
                        if (districtName != null)
                            info.put("区域名称", districtName);
                        if (schoolType != null)
                            info.put("学校类型", schoolType);
                    }
                }
            }

            // 写入缓存
            if (!info.isEmpty()) {
                schoolInfoCache.put(schoolId, info);
            }
            return info;

        } catch (Exception e) {
            log.warn("查询学校信息失败: schoolId={}", schoolId, e);
            return Map.of();
        }
    }
}