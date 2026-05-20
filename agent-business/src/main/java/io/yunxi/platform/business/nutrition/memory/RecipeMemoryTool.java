package io.yunxi.platform.business.nutrition.memory;

import io.yunxi.platform.business.nutrition.memory.RecipeMemoryService.RecipeData;
import io.yunxi.platform.business.nutrition.memory.RecipeMemoryService.RecipeMemory;
import io.yunxi.platform.business.nutrition.memory.RecipeMemoryService.RecipeStats;
import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 食谱记忆工具
 *
 * <p>
 * 提供 Agent 对食谱历史记忆的访问能力，支持：
 * <ul>
 * <li>搜索历史食谱（语义检索）</li>
 * <li>获取近期食谱（避免重复）</li>
 * <li>保存新食谱记忆</li>
 * <li>检测食谱重复</li>
 * <li>获取高分食谱参考</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>使用场景</b>：
 * <pre>
 * 1. 生成新食谱前：search_recipe_history 检索历史，避免重复
 * 2. 生成新食谱后：save_recipe_memory 保存记忆
 * 3. 优化食谱时：get_top_scored_recipes 参考高分食谱
 * </pre>
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Slf4j
@Component
@ConditionalOnBean(RecipeMemoryService.class)
public class RecipeMemoryTool implements Tool {

    /** 食谱记忆服务 */
    private final RecipeMemoryService recipeMemoryService;

    /**
     * 构造方法
     *
     * @param recipeMemoryService 食谱记忆服务
     */
    @Autowired
    public RecipeMemoryTool(RecipeMemoryService recipeMemoryService) {
        this.recipeMemoryService = recipeMemoryService;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "recipe_memory"
     */
    @Override
    public String getName() {
        return "recipe_memory";
    }

    /**
     * 获取工具描述信息
     *
     * @return 工具功能描述文本
     */
    @Override
    public String getDescription() {
        return """
                食谱历史记忆工具，用于管理学校食谱的长期记忆。
                
                功能：
                - search: 语义搜索历史食谱（如"上周食谱"、"低油低盐食谱"）
                - recent: 获取最近N周的食谱（用于避免重复）
                - save: 保存新食谱到记忆库
                - check_duplicate: 检测食谱是否与近期重复
                - top_scored: 获取评分最高的食谱（用于参考）
                - stats: 获取学校食谱统计信息
                
                使用场景：
                1. 生成新食谱前：调用 search 或 recent 检索历史，避免重复
                2. 生成新食谱后：调用 save 保存记忆
                3. 优化食谱时：调用 top_scored 参考高分食谱
                """;
    }

    /**
     * 获取工具参数的 JSON Schema 定义
     *
     * @return 参数 Schema JSON 字符串
     */
    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "action": {
                            "type": "string",
                            "enum": ["search", "recent", "save", "check_duplicate", "top_scored", "stats"],
                            "description": "操作类型"
                        },
                        "schoolId": {
                            "type": "string",
                            "description": "学校ID（必填）"
                        },
                        "query": {
                            "type": "string",
                            "description": "搜索查询文本（search 操作必填）"
                        },
                        "weeks": {
                            "type": "integer",
                            "default": 2,
                            "description": "周数（recent 操作）"
                        },
                        "topK": {
                            "type": "integer",
                            "default": 5,
                            "description": "返回数量（search/top_scored 操作）"
                        },
                        "recipeData": {
                            "type": "object",
                            "properties": {
                                "dateRange": {"type": "string", "description": "日期范围"},
                                "content": {"type": "string", "description": "食谱内容"},
                                "avgScore": {"type": "number", "description": "平均评分"},
                                "targetGroup": {"type": "string", "description": "目标人群"},
                                "highlight": {"type": "string", "description": "亮点/特色"}
                            },
                            "description": "食谱数据（save 操作必填）"
                        },
                        "recipeContent": {
                            "type": "string",
                            "description": "食谱内容文本（check_duplicate 操作必填）"
                        },
                        "similarityThreshold": {
                            "type": "number",
                            "default": 0.7,
                            "description": "相似度阈值（check_duplicate 操作）"
                        }
                    },
                    "required": ["action", "schoolId"]
                }
                """;
    }

    /**
     * 执行食谱记忆工具操作，根据 action 参数分发到具体处理方法
     *
     * @param input 工具输入参数，包含 action、schoolId 等
     * @return 工具执行结果
     * @throws ToolExecutionException 工具执行异常
     */
    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            String action = input.getString("action");
            String schoolId = input.getString("schoolId");

            if (action == null || schoolId == null) {
                return ToolResult.error("缺少必填参数: action 和 schoolId");
            }

            log.info("执行食谱记忆操作: action={}, schoolId={}", action, schoolId);

            Map<String, Object> result = switch (action) {
                case "search" -> executeSearch(schoolId, input);
                case "recent" -> executeRecent(schoolId, input);
                case "save" -> executeSave(schoolId, input);
                case "check_duplicate" -> executeCheckDuplicate(schoolId, input);
                case "top_scored" -> executeTopScored(schoolId, input);
                case "stats" -> executeStats(schoolId);
                default -> throw new ToolExecutionException(getName(), "未知操作类型: " + action);
            };

            ToolResult toolResult = ToolResult.success(result);
            toolResult.setDurationMs(System.currentTimeMillis() - startTime);
            return toolResult;

        } catch (Exception e) {
            log.error("食谱记忆操作失败", e);
            throw new ToolExecutionException(getName(), "执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行语义搜索历史食谱操作
     *
     * @param schoolId 学校ID
     * @param input    工具输入参数
     * @return 搜索结果 Map
     */
    private Map<String, Object> executeSearch(String schoolId, ToolInput input) {
        String query = input.getString("query");
        if (query == null || query.isBlank()) {
            return Map.of("success", false, "error", "缺少查询文本");
        }

        int topK = input.getInt("topK", 5);
        List<RecipeMemory> memories = recipeMemoryService.searchRecipes(schoolId, query, topK);

        return Map.of(
                "success", true,
                "query", query,
                "count", memories.size(),
                "recipes", memories.stream().map(this::toMap).toList()
        );
    }

    /**
     * 执行获取近期食谱操作
     *
     * @param schoolId 学校ID
     * @param input    工具输入参数
     * @return 近期食谱结果 Map
     */
    private Map<String, Object> executeRecent(String schoolId, ToolInput input) {
        int weeks = input.getInt("weeks", 2);
        List<RecipeMemory> memories = recipeMemoryService.getRecentRecipes(schoolId, weeks);

        return Map.of(
                "success", true,
                "weeks", weeks,
                "count", memories.size(),
                "recipes", memories.stream().map(this::toMap).toList()
        );
    }

    /**
     * 执行保存食谱记忆操作
     *
     * @param schoolId 学校ID
     * @param input    工具输入参数
     * @return 保存结果 Map
     */
    private Map<String, Object> executeSave(String schoolId, ToolInput input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> recipeDataMap = (Map<String, Object>) input.getParameters().get("recipeData");

        if (recipeDataMap == null) {
            return Map.of("success", false, "error", "缺少食谱数据");
        }

        RecipeData recipeData = new RecipeData();
        recipeData.setDateRange((String) recipeDataMap.get("dateRange"));
        recipeData.setContent((String) recipeDataMap.get("content"));
        recipeData.setTargetGroup((String) recipeDataMap.get("targetGroup"));
        recipeData.setHighlight((String) recipeDataMap.get("highlight"));

        if (recipeDataMap.get("avgScore") instanceof Number score) {
            recipeData.setAvgScore(score.doubleValue());
        }

        boolean saved = recipeMemoryService.saveRecipe(schoolId, recipeData);

        return Map.of(
                "success", saved,
                "message", saved ? "食谱记忆保存成功" : "保存失败"
        );
    }

    /**
     * 执行检测食谱重复操作
     *
     * @param schoolId 学校ID
     * @param input    工具输入参数
     * @return 重复检测结果 Map
     */
    private Map<String, Object> executeCheckDuplicate(String schoolId, ToolInput input) {
        String recipeContent = input.getString("recipeContent");
        if (recipeContent == null || recipeContent.isBlank()) {
            return Map.of("success", false, "error", "缺少食谱内容");
        }

        double threshold = input.getParameter("similarityThreshold", Double.class);
        if (threshold <= 0) threshold = 0.7;
        boolean isDuplicate = recipeMemoryService.isDuplicateRecipe(schoolId, recipeContent, threshold);

        return Map.of(
                "success", true,
                "isDuplicate", isDuplicate,
                "threshold", threshold,
                "message", isDuplicate ? "检测到相似食谱，建议调整" : "未检测到重复"
        );
    }

    /**
     * 执行获取高分食谱操作
     *
     * @param schoolId 学校ID
     * @param input    工具输入参数
     * @return 高分食谱结果 Map
     */
    private Map<String, Object> executeTopScored(String schoolId, ToolInput input) {
        int topK = input.getInt("topK", 3);
        List<RecipeMemory> memories = recipeMemoryService.getTopScoredRecipes(schoolId, topK);

        return Map.of(
                "success", true,
                "count", memories.size(),
                "recipes", memories.stream().map(this::toMap).toList()
        );
    }

    /**
     * 执行获取学校食谱统计信息操作
     *
     * @param schoolId 学校ID
     * @return 统计信息 Map
     */
    private Map<String, Object> executeStats(String schoolId) {
        RecipeStats stats = recipeMemoryService.getRecipeStats(schoolId);

        return Map.of(
                "success", true,
                "schoolId", stats.getSchoolId(),
                "totalRecipes", stats.getTotalRecipes(),
                "avgScore", stats.getAvgScore()
        );
    }

    /**
     * 将食谱记忆对象转换为 Map，用于返回给 Agent
     *
     * @param memory 食谱记忆对象
     * @return 包含关键信息的 Map（内容截断以节省 token）
     */
    private Map<String, Object> toMap(RecipeMemory memory) {
        Map<String, Object> map = new HashMap<>();
        RecipeData data = memory.getRecipeData();
        if (data != null) {
            map.put("dateRange", data.getDateRange());
            map.put("avgScore", data.getAvgScore());
            map.put("targetGroup", data.getTargetGroup());
            map.put("highlight", data.getHighlight());
            // 不返回完整内容，节省 token
            map.put("contentPreview", truncate(data.getContent(), 200));
        }
        return map;
    }

    /**
     * 截断文本到指定最大长度，超出部分用省略号替代
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 截断后的文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return null;
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
