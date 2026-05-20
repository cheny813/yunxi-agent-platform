package io.yunxi.platform.business.nutrition.constraint;

import io.yunxi.platform.framework.tool.Tool;
import io.yunxi.platform.framework.tool.ToolExecutionException;
import io.yunxi.platform.framework.tool.ToolInput;
import io.yunxi.platform.framework.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配餐约束 AgentTool
 *
 * <p>
 * 注册为 Agent 工具 {@code query_meal_constraints}，供 Agent 主动查询配餐约束策略。
 * 与 {@link NutritionConstraintEnricher} 互补：Enricher 自动注入上下文，
 * Tool 允许 Agent 在需要时主动查询更详细的约束信息。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.constraint.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.1.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "nutrition-extension.constraint.enabled", havingValue = "true")
public class NutritionConstraintTool implements Tool {

    /** 动态配餐约束服务，用于构建约束策略 */
    private final DynamicConstraintService constraintService;

    /**
     * 构造函数
     *
     * @param constraintService 配餐约束服务
     */
    public NutritionConstraintTool(DynamicConstraintService constraintService) {
        this.constraintService = constraintService;
    }

    /**
     * 获取工具名称
     *
     * @return 工具名称 "query_meal_constraints"
     */
    @Override
    public String getName() {
        return "query_meal_constraints";
    }

    /**
     * 获取工具描述
     *
     * @return 工具的中文描述，包含约束维度和适用场景
     */
    @Override
    public String getDescription() {
        return """
                查询配餐约束策略工具。根据学校信息查询以下约束维度：
                - 民族饮食禁忌（如回族禁猪肉）
                - 区域菜系推荐（如川菜、粤菜）
                - 当季/反季食材（按月份判断）
                - 价格模式（集中采购/市场波动）及天气影响因子

                适用场景：生成食谱前查询约束条件，避免推荐禁忌食材，优先推荐当季和区域特色菜品。
                """;
    }

    /**
     * 获取工具参数的JSON Schema定义
     *
     * @return 参数Schema，包含schoolId(必填)、districtCode、provinceCode、mealType
     */
    @Override
    public String getParameterSchema() {
        return """
                {
                    "type": "object",
                    "properties": {
                        "schoolId": {
                            "type": "integer",
                            "description": "学校ID（必填）"
                        },
                        "districtCode": {
                            "type": "string",
                            "description": "区县编码（可选，用于价格模式查询）"
                        },
                        "provinceCode": {
                            "type": "string",
                            "description": "省份编码（可选，用于菜系推荐）"
                        },
                        "mealType": {
                            "type": "string",
                            "enum": ["DAY", "WEEK"],
                            "description": "食谱类型: DAY(当日) / WEEK(周食谱)，默认DAY"
                        }
                    },
                    "required": ["schoolId"]
                }
                """;
    }

    /**
     * 执行配餐约束策略查询
     *
     * @param input 工具输入参数，包含schoolId、districtCode、provinceCode、mealType
     * @return 查询结果，包含约束策略的各个维度信息
     * @throws ToolExecutionException 当查询执行失败时抛出
     */
    @Override
    public ToolResult execute(ToolInput input) throws ToolExecutionException {
        long startTime = System.currentTimeMillis();

        try {
            Long schoolId = input.getParameter("schoolId", Long.class);
            String districtCode = input.getString("districtCode");
            String provinceCode = input.getString("provinceCode");
            String mealType = input.getString("mealType", "DAY");

            if (schoolId == null) {
                return ToolResult.error("缺少必填参数: schoolId");
            }

            log.info("查询配餐约束策略: schoolId={}, districtCode={}, provinceCode={}, mealType={}",
                    schoolId, districtCode, provinceCode, mealType);

            ConstraintRequest request = ConstraintRequest.builder()
                    .schoolId(schoolId)
                    .districtCode(districtCode)
                    .provinceCode(provinceCode)
                    .mealType(mealType)
                    .build();

            RecommendationStrategy strategy = constraintService.buildStrategy(request);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schoolId", schoolId);
            result.put("priceMode", strategy.getPriceMode());
            result.put("weatherImpactFactor", strategy.getWeatherImpactFactor());
            result.put("preferredCuisines", strategy.getPreferredCuisines());
            result.put("restrictedIngredients", strategy.getRestrictedIngredients());
            result.put("seasonalIngredients", strategy.getSeasonalIngredients());
            result.put("offSeasonIngredients", strategy.getOffSeasonIngredients());
            result.put("strategyDescription", strategy.getStrategyDescription());

            ToolResult toolResult = ToolResult.success(result);
            toolResult.setDurationMs(System.currentTimeMillis() - startTime);
            return toolResult;

        } catch (Exception e) {
            log.error("查询配餐约束策略失败", e);
            throw new ToolExecutionException(getName(), "执行失败: " + e.getMessage(), e);
        }
    }
}
