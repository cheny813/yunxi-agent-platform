package io.yunxi.platform.business.nutrition.sync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.yunxi.platform.framework.sync.MilvusCollectionService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜品数据构建服务
 *
 * <p>
 * 封装菜品营养成分计算、Milvus 数据构建和 embedding 文本构建逻辑，
 * 从 SchoolDishVectorSyncService 中提取。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class DishDataBuilder {

    /** Gson 序列化工具 */
    private final Gson gson = new Gson();

    /**
     * 计算菜品营养成分（聚合各食材的营养贡献）
     *
     * @param dishIngredients 菜品食材关系
     * @param nutrientMap     食材营养成分映射
     * @return dishId → {nutrientName → totalAmount}
     */
    public Map<Long, Map<String, Double>> calculateDishNutrients(
            List<DishQueryService.DishIngredient> dishIngredients,
            Map<Long, List<DishQueryService.IngredientNutrient>> nutrientMap) {

        Map<Long, Map<String, Double>> result = new java.util.HashMap<>();

        // 按 dishId 分组
        Map<Long, List<DishQueryService.DishIngredient>> byDish = dishIngredients.stream()
                .collect(Collectors.groupingBy(DishQueryService.DishIngredient::getDishId));

        for (Map.Entry<Long, List<DishQueryService.DishIngredient>> entry : byDish.entrySet()) {
            Long dishId = entry.getKey();
            List<DishQueryService.DishIngredient> ingredients = entry.getValue();
            Map<String, Double> dishNutrients = new java.util.HashMap<>();

            for (DishQueryService.DishIngredient di : ingredients) {
                List<DishQueryService.IngredientNutrient> nutrients = nutrientMap.get(di.getIngredientId());
                if (nutrients == null) continue;

                for (DishQueryService.IngredientNutrient in : nutrients) {
                    if (in.getContent() != null && di.getDosage() != null) {
                        // 含量单位是 mg/100g，需要根据用量换算
                        double contribution = in.getContent() * di.getDosage() / 100.0;
                        dishNutrients.merge(in.getNutrientName(), contribution, Double::sum);
                    }
                }
            }

            result.put(dishId, dishNutrients);
        }

        return result;
    }

    /**
     * 构建 Milvus 向量数据
     *
     * @param dish           菜品基本信息
     * @param ingredients    菜品食材列表
     * @param dishNutrients  菜品营养成分映射
     * @param embedding      向量数据（可为 null，后续批量填入）
     * @param schoolId       学校ID，null 表示公共菜品
     * @return 构建好的 JsonObject 数据
     */
    public JsonObject buildDishVectorData(DishQueryService.Dish dish,
                                           List<DishQueryService.DishIngredient> ingredients,
                                           Map<String, Double> dishNutrients,
                                           List<Float> embedding,
                                           Long schoolId) {
        JsonObject data = new JsonObject();
        data.addProperty("id", dish.getId());
        data.addProperty("name", dish.getName());
        data.addProperty("type", dish.getType());
        data.addProperty("update_time", dish.getUpdateTime());

        // 食材名称列表
        String ingredientNames = ingredients.stream()
                .map(DishQueryService.DishIngredient::getIngredientName)
                .collect(Collectors.joining(","));
        data.addProperty("ingredients", ingredientNames);

        // 食材ID:名称映射
        String ingredientIds = ingredients.stream()
                .map(di -> di.getIngredientId() + ":" + di.getIngredientName())
                .collect(Collectors.joining(","));
        data.addProperty("ingredient_ids", ingredientIds);

        // 营养成分 JSON
        data.addProperty("nutrients", gson.toJson(dishNutrients));

        // 向量
        if (embedding != null) {
            data.add("embedding", gson.toJsonTree(embedding));
        }

        // 学校ID（公共菜品为0）
        data.addProperty("school_id", schoolId != null ? schoolId : 0L);

        return data;
    }

    /**
     * 构建菜品 embedding 文本
     *
     * @param dish         菜品基本信息
     * @param ingredients  菜品食材列表
     * @return 用于生成向量的文本字符串
     */
    public String buildDishEmbeddingText(DishQueryService.Dish dish,
                                          List<DishQueryService.DishIngredient> ingredients) {
        String ingredientNames = ingredients.stream()
                .map(DishQueryService.DishIngredient::getIngredientName)
                .collect(Collectors.joining(" "));
        return String.format("%s %s %s", dish.getName(), dish.getType(), ingredientNames);
    }
}
