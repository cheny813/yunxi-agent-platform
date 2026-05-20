package io.yunxi.platform.business.nutrition.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.yunxi.platform.framework.sync.McpQueryService;
import io.yunxi.platform.shared.util.TextParserUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 菜品数据查询服务
 *
 * <p>
 * 封装从 MCP 数据库查询菜品、食材、营养成分的逻辑，
 * 从 SchoolDishVectorSyncService 中提取。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
public class DishQueryService {

    /** MCP 数据库查询服务 */
    private final McpQueryService mcpQueryService;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** MCP 数据库主机地址 */
    @Value("${dish-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${dish-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /**
     * 构造函数，注入 MCP 查询服务
     *
     * @param mcpQueryService MCP 数据库查询服务
     */
    public DishQueryService(McpQueryService mcpQueryService) {
        this.mcpQueryService = mcpQueryService;
    }

    // ==================== 查询方法 ====================

    /**
     * 分页查询学校菜品
     *
     * @param schoolId 学校ID
     * @return 该学校的菜品列表
     */
    public List<Dish> querySchoolDishesWithPagination(Long schoolId) {
        List<Dish> allDishes = new ArrayList<>();
        int offset = 0;
        int pageSize = 1000;
        boolean hasMore = true;

        while (hasMore) {
            // 使用 dish_library 表，通过 school_id 字段筛选学校菜品
            String sql = String.format(
                    "SELECT id, name, type, update_time " +
                            "FROM dish_library " +
                            "WHERE school_id = %d AND deleted = 0 " +
                            "ORDER BY id LIMIT %d OFFSET %d",
                    schoolId, pageSize, offset);

            log.info("MCP地址: {}:{}", mcpDbHost, mcpDbPort);
            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);
            List<Dish> dishes = parseDishes(response);

            if (dishes.size() < pageSize) {
                hasMore = false;
            }
            allDishes.addAll(dishes);
            offset += pageSize;

            if (dishes.isEmpty()) {
                break;
            }
        }

        log.info("学校 {} 共查询到 {} 道菜品（分页查询）", schoolId, allDishes.size());
        return allDishes;
    }

    /**
     * 批量查询菜品食材
     *
     * @param dishIds 菜品ID列表
     * @return 菜品食材关系列表
     */
    public List<DishIngredient> queryDishIngredientsFromDatabase(List<Long> dishIds) {
        List<DishIngredient> allIngredients = new ArrayList<>();
        int batchSize = 1000;

        for (int i = 0; i < dishIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, dishIds.size());
            List<Long> batch = dishIds.subList(i, end);

            String idsStr = batch.toString().replace("[", "").replace("]", "");
            // 使用 dish_food_ingredient 表查询菜品食材
            String sql = String.format(
                    "SELECT dfi.d_id as d_id, dfi.fi_id, fi.name as ingredient_name, dfi.dosage " +
                            "FROM dish_food_ingredient dfi " +
                            "LEFT JOIN food_ingredient fi ON dfi.fi_id = fi.id " +
                            "WHERE dfi.d_id IN (%s)",
                    idsStr);

            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);
            List<DishIngredient> ingredients = parseDishIngredients(response);
            allIngredients.addAll(ingredients);
        }

        log.info("共查询到 {} 条菜品食材关系", allIngredients.size());
        return allIngredients;
    }

    /**
     * 批量查询食材营养成分
     *
     * @param ingredientIds 食材ID列表
     * @return 食材ID到营养成分列表的映射
     */
    public Map<Long, List<IngredientNutrient>> queryIngredientNutrientsFromDatabase(List<Long> ingredientIds) {
        Map<Long, List<IngredientNutrient>> result = new HashMap<>();
        int batchSize = 1000;

        for (int i = 0; i < ingredientIds.size(); i += batchSize) {
            int end = Math.min(i + batchSize, ingredientIds.size());
            List<Long> batch = ingredientIds.subList(i, end);

            String idsStr = batch.toString().replace("[", "").replace("]", "");
            String sql = String.format(
                    "SELECT fin.fi_id, n.name as nutrient_name, n.unit, fin.nutrient_content " +
                            "FROM food_ingredient_nutrient fin " +
                            "LEFT JOIN nutrient n ON fin.nutrient_id = n.id " +
                            "WHERE fin.fi_id IN (%s)",
                    idsStr);

            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);
            Map<Long, List<IngredientNutrient>> batchResult = parseIngredientNutrients(response);
            result.putAll(batchResult);
        }

        log.info("共查询到 {} 种食材的营养成分", result.size());
        return result;
    }

    // ==================== 解析方法 ====================

    /**
     * 解析 MCP 查询返回的菜品数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 菜品列表
     */
    private List<Dish> parseDishes(String response) {
        List<Dish> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();

                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Dish dish = new Dish();
                    dish.setId(TextParserUtil.parseLong(row, "id"));
                    dish.setName(TextParserUtil.parseString(row, "name"));
                    dish.setType(TextParserUtil.parseString(row, "type"));
                    dish.setUpdateTime(TextParserUtil.parseString(row, "update_time"));

                    if (dish.getId() != null) {
                        result.add(dish);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析菜品数据失败", e);
        }
        return result;
    }

    /**
     * 解析 MCP 查询返回的菜品食材数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 菜品食材关系列表
     */
    private List<DishIngredient> parseDishIngredients(String response) {
        List<DishIngredient> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();

                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    DishIngredient di = new DishIngredient();
                    di.setDishId(TextParserUtil.parseLong(row, "d_id"));
                    di.setIngredientId(TextParserUtil.parseLong(row, "fi_id"));
                    di.setIngredientName(TextParserUtil.parseString(row, "ingredient_name"));

                    String dosageStr = TextParserUtil.parseString(row, "dosage");
                    if (dosageStr != null) {
                        try {
                            di.setDosage(Double.parseDouble(dosageStr));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if (di.getDishId() != null && di.getIngredientId() != null) {
                        result.add(di);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析菜品食材数据失败", e);
        }
        return result;
    }

    /**
     * 解析 MCP 查询返回的食材营养成分数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 食材ID到营养成分列表的映射
     */
    private Map<Long, List<IngredientNutrient>> parseIngredientNutrients(String response) {
        Map<Long, List<IngredientNutrient>> result = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();

                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    IngredientNutrient in = new IngredientNutrient();
                    Long ingredientId = TextParserUtil.parseLong(row, "fi_id");
                    in.setIngredientId(ingredientId);
                    in.setNutrientName(TextParserUtil.parseString(row, "nutrient_name"));
                    in.setUnit(TextParserUtil.parseString(row, "unit"));

                    String contentStr = TextParserUtil.parseString(row, "nutrient_content");
                    if (contentStr != null) {
                        try {
                            in.setContent(Double.parseDouble(contentStr));
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    if (ingredientId != null) {
                        result.computeIfAbsent(ingredientId, k -> new ArrayList<>()).add(in);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析食材营养成分数据失败", e);
        }
        return result;
    }

    // ==================== DTO ====================

    /** 菜品数据传输对象 */
    @Data
    public static class Dish {
        /** 菜品ID */
        private Long id;
        /** 菜品名称 */
        private String name;
        /** 菜品类型 */
        private String type;
        /** 更新时间 */
        private String updateTime;
    }

    /** 菜品食材关系数据传输对象 */
    @Data
    public static class DishIngredient {
        /** 菜品ID */
        private Long dishId;
        /** 食材ID */
        private Long ingredientId;
        /** 食材名称 */
        private String ingredientName;
        /** 用量（g） */
        private Double dosage;
    }

    /** 食材营养成分数据传输对象 */
    @Data
    public static class IngredientNutrient {
        /** 食材ID */
        private Long ingredientId;
        /** 营养素名称 */
        private String nutrientName;
        /** 营养素单位 */
        private String unit;
        /** 营养素含量（mg/100g） */
        private Double content;
    }
}
