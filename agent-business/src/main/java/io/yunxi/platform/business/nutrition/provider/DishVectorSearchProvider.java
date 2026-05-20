package io.yunxi.platform.business.nutrition.provider;

import io.yunxi.platform.business.nutrition.sync.SchoolDishVectorSyncService;
import io.yunxi.platform.spi.vector.VectorSearchProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜品向量搜索提供者
 *
 * <p>
 * 实现框架层定义的 VectorSearchProvider 接口，提供菜品向量搜索能力。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class DishVectorSearchProvider implements VectorSearchProvider {

    /** 菜品向量同步搜索服务 */
    @Autowired
    private SchoolDishVectorSyncService dishVectorSyncService;

    /**
     * 执行菜品向量搜索
     *
     * @param contextId 上下文ID（通常为学校ID）
     * @param query     搜索查询文本
     * @param topK      返回结果数量上限
     * @return 向量搜索结果列表
     */
    @Override
    public List<VectorData> search(Long contextId, String query, int topK) {
        // 调用业务层的搜索方法
        List<SchoolDishVectorSyncService.DishData> dishes = dishVectorSyncService.searchDishesBySchool(contextId, query,
                topK);

        // 转换为框架层的通用数据结构
        return dishes.stream()
                .map(this::convertToVectorData)
                .toList();
    }

    /**
     * 转换菜品数据为向量数据
     *
     * @param dish 菜品数据
     * @return 框架层通用的向量数据结构
     */
    private VectorData convertToVectorData(SchoolDishVectorSyncService.DishData dish) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", dish.getType() != null ? dish.getType() : "dish");
        metadata.put("ingredientIds", dish.getIngredientIds());
        metadata.put("nutrients", dish.getNutrients());

        // 构建内容字符串（用于注入上下文）
        String content = buildDishContent(dish);

        return new VectorData(
                String.valueOf(dish.getId()),
                dish.getName(),
                dish.getType() != null ? dish.getType() : "dish",
                metadata,
                content);
    }

    /**
     * 构建菜品内容字符串，用于注入上下文
     *
     * @param dish 菜品数据
     * @return 包含菜品名称、ID、食材列表和营养成分的描述字符串
     */
    private String buildDishContent(SchoolDishVectorSyncService.DishData dish) {
        StringBuilder sb = new StringBuilder();
        sb.append("菜品名称: ").append(dish.getName()).append("\n");
        sb.append("菜品ID: ").append(dish.getId()).append("\n");

        if (dish.getIngredientIds() != null && !dish.getIngredientIds().isEmpty()) {
            sb.append("食材列表: ").append(dish.getIngredientIds()).append("\n");
        }

        if (dish.getNutrients() != null && !dish.getNutrients().isEmpty()) {
            sb.append("营养成分: ").append(dish.getNutrients()).append("\n");
        }

        return sb.toString();
    }
}
