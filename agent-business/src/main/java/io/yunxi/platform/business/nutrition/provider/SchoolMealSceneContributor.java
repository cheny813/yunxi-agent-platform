package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.business.nutrition.memory.RecipeMemoryService;
import io.yunxi.platform.framework.spi.SceneContributor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 校园餐场景贡献器
 *
 * <p>实现 SceneContributor SPI，贡献校园餐各业务模块场景的关键词、提取提示词和上下文组装逻辑。</p>
 * <p>覆盖7个业务模块：食品安全、营养配餐、经费管理、集采-校园、集采-国企、消费管理、预警</p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class SchoolMealSceneContributor implements SceneContributor {

    /** 校园餐场景关键词 */
    private static final Map<String, List<String>> SCENE_KEYWORDS = Map.of(
            "SCHOOL_MEAL", List.of(
                    "食谱", "菜品", "营养", "配餐", "配平", "膳食",
                    "食材", "菜单", "餐次", "厨师", "营养师",
                    "校园餐", "学校餐", "食堂", "供餐", "用餐",
                    "早餐", "午餐", "晚餐", "加餐", "点心",
                    "食品安全", "溯源", "卫生", "抽检", "过期",
                    "经费", "成本", "预算", "核算", "伙食费",
                    "集采", "采购", "供应商", "招标",
                    "消费", "充值", "余额", "消费记录",
                    "预警", "超标", "异常", "告警", "风险"
            )
    );

    /** 食谱记忆服务（可选依赖） */
    @Autowired(required = false)
    private RecipeMemoryService recipeMemoryService;

    /**
     * 获取校园餐场景关键词映射
     *
     * @return 场景名称到关键词列表的映射
     */
    @Override
    public Map<String, List<String>> getSceneKeywords() {
        return SCENE_KEYWORDS;
    }

    /**
     * 获取指定场景的信息提取提示词
     *
     * @param sceneName 场景名称
     * @return 提取提示词，若非校园餐场景则返回 null
     */
    @Override
    public String getExtractionPrompt(String sceneName) {
        if (!"SCHOOL_MEAL".equals(sceneName)) return null;

        return """
                重点提取以下校园餐相关信息：
                1. 食谱偏好：菜品、食材、口味偏好
                2. 营养搭配：营养标准和搭配要求
                3. 食材采购：常用食材和采购需求
                4. 供餐要求：餐次安排、人群分组、供餐量
                5. 食品安全：溯源、卫生、合规要求
                6. 经费管理：成本控制、预算约束
                7. 过敏忌口：需要避免的食材或菜品
                """;
    }

    /**
     * 组装校园餐场景上下文信息，包括用户记忆和配餐专业知识
     *
     * @param sceneName 场景名称
     * @param userId    用户ID
     * @param query     用户查询文本
     * @return 组装后的上下文字符串，若非校园餐场景则返回 null
     */
    @Override
    public String assembleContext(String sceneName, String userId, String query) {
        if (!"SCHOOL_MEAL".equals(sceneName)) return null;

        StringBuilder context = new StringBuilder();
        context.append("【校园餐上下文】\n");
        context.append("用户正在使用校园餐管理系统。\n\n");

        // 注入食谱历史记忆
        if (recipeMemoryService != null && userId != null) {
            try {
                String recipeContext = recipeMemoryService.buildRecipeContextPrompt(userId);
                if (recipeContext != null && !recipeContext.isBlank()) {
                    context.append(recipeContext).append("\n");
                }
            } catch (Exception e) {
                log.debug("组装食谱记忆上下文失败: {}", e.getMessage());
            }
        }

        // 注入配餐专业知识
        context.append("【配餐专业知识】\n");
        context.append("- 学龄儿童每日营养需求因年龄段而异\n");
        context.append("- 每餐应包含主食、蛋白质食物和蔬菜水果\n");
        context.append("- 注意钙、铁、维生素A/D等微量营养素摄入\n");
        context.append("- 避免高油高盐高糖的加工食品\n");

        return context.toString();
    }
}
