package io.yunxi.platform.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.persistence.milvus.MilvusOperations;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 学校菜品同步运行器
 *
 * <p>
 * 处理公共菜品库和各校专属菜品库的同步
 * </p>
 *
 * <p>
 * 同步策略：
 * </p>
 * <ul>
 * <li>公共菜品库（school_id IS NULL）→ Milvus 集合 {@code school_common_dishes}</li>
 * <li>学校专属菜品库 — Milvus 集合 {@code school_dishes_{schoolId}}</li>
 * </ul>
 *
 * <p>
 * 同步时关联菜品食材和营养成分，支持分页查询定时增量同步
 * </p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "dish-sync.enabled", havingValue = "true")
public class SchoolDishSyncRunner implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchoolDishSyncRunner.class);

    /** 公共菜品集合名称 */
    private static final String SCHOOL_COMMON_DISHES = "school_common_dishes";

    /** 学校专属菜品集合名称前缀 */
    private static final String COLLECTION_PREFIX = "school_dishes_";

    private final MilvusOperations milvusOps;
    private final ExternalDbQueryService externalDbQueryService;
    private final EmbeddingBatchService embeddingBatchService;
    private final EmbeddingService embeddingService;
    private final MilvusCollectionService milvusCollectionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Gson gson = new GsonBuilder().create();

    @Value("${dish-sync.database.jdbc-url:jdbc:mysql://localhost:3306/nutrition}")
    private String dbJdbcUrl;

    @Value("${dish-sync.database.username:root}")
    private String dbUsername;

    @Value("${dish-sync.database.password:root}")
    private String dbPassword;

    @Value("${dish-sync.batch-size:100}")
    private int batchSize;

    @Value("${dish-sync.query-page-size:1000}")
    private int queryPageSize;

    public SchoolDishSyncRunner(
            MilvusOperations milvusOps,
            ExternalDbQueryService externalDbQueryService,
            EmbeddingBatchService embeddingBatchService,
            EmbeddingService embeddingService,
            MilvusCollectionService milvusCollectionService) {
        this.milvusOps = milvusOps;
        this.externalDbQueryService = externalDbQueryService;
        this.embeddingBatchService = embeddingBatchService;
        this.embeddingService = embeddingService;
        this.milvusCollectionService = milvusCollectionService;
    }

    @Override
    @PostConstruct
    public void afterPropertiesSet() {
        if (!milvusOps.isAvailable()) {
            log.warn("SchoolDishSyncRunner: Milvus 不可用跳过学校菜品同步");
            return;
        }
        log.info("========== SchoolDishSyncRunner: 开始学校菜品同步 ==========");
        syncAllSchoolDishes();
    }

    /**
     * 定时同步任务（默认凌晨2点执行）
     */
    @Scheduled(cron = "${dish-sync.cron:0 0 2 * * ?}")
    public void scheduledSync() {
        log.info("========== SchoolDishSyncRunner: 定时同步任务开始 ==========");
        syncAllSchoolDishes();
    }

    // ==================== 同步编排 ====================

    /**
     * 同步所有学校菜品
     * <ol>
     * <li>先同步公共菜品库（school_id IS NULL）</li>
     * <li>再查询所有学校逐个同步学校专属菜品</li>
     * </ol>
     */
    private void syncAllSchoolDishes() {
        try {
            // 1. Sync public dish library
            log.info("SchoolDishSyncRunner: syncing public dish library");
            syncSchoolDishes(null);

            // 2. Query all schools
            List<Long> schoolIds = queryAllSchoolIds();
            log.info("SchoolDishSyncRunner: found {} schools", schoolIds.size());

            // 3. Sync each school's dedicated dishes
            for (Long schoolId : schoolIds) {
                try {
                    syncSchoolDishes(schoolId);
                    Thread.sleep(1000); // 1s interval between schools to avoid overload
                } catch (Exception e) {
                    log.error("Failed to sync school {} dishes", schoolId, e);
                }
            }

            log.info("========== SchoolDishSyncRunner: school dish sync completed ==========");
        } catch (Exception e) {
            log.error("SchoolDishSyncRunner: sync failed", e);
        }
    }

    /**
     * 查询所有学校ID
     */
    private List<Long> queryAllSchoolIds() {
        try {
            String sql = "SELECT id FROM institution WHERE type = 'SCHOOL' AND deleted = 0";
            String response = externalDbQueryService.query(dbJdbcUrl, dbUsername, dbPassword, sql);
            return parseLongIds(response);
        } catch (Exception e) {
            log.error("Query school list failed", e);
            return Collections.emptyList();
        }
    }

    // ==================== 单个学校同步 ====================

    /**
     * 同步指定学校（或公共）菜品库
     *
     * @param schoolId 学校ID，null表示公共菜品库
     */
    private void syncSchoolDishes(Long schoolId) {
        String collectionName = schoolId == null ? SCHOOL_COMMON_DISHES : COLLECTION_PREFIX + schoolId;
        String taskDesc = schoolId == null ? "Public Dish Library" : "School " + schoolId + " Dedicated Dishes";

        log.info("  Start syncing {} -> collection {}", taskDesc, collectionName);

        try {
            // 1. Ensure collection exists
            ensureCollectionExists(collectionName);

            // 2. Skip if collection already has data (avoid duplicate sync on each startup)
            if (milvusCollectionService.getCollectionCount(collectionName) > 0) {
                log.info("  {} already has data, skipping", taskDesc);
                return;
            }

            // 2. Paginate query dish data
            List<Dish> dishes = queryDishesWithPagination(schoolId);
            if (dishes.isEmpty()) {
                log.info("  {} has no data, skipping", taskDesc);
                return;
            }
            log.info("  Found {} dishes", dishes.size());

            // 3. 查询菜品-食材关系
            List<Long> dishIds = dishes.stream().map(d -> d.id).collect(Collectors.toList());
            Map<Long, List<DishIngredient>> ingredientsByDish = queryIngredientsByDishIds(dishIds);

            // 4. 查询食材营养成分
            Set<Long> allIngredientIds = ingredientsByDish.values().stream()
                    .flatMap(Collection::stream)
                    .map(i -> i.ingredientId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Map<Long, List<IngredientNutrient>> ingredientNutrients = queryNutrientsByIngredientIds(allIngredientIds);

            // 5. Calculate aggregate nutrition for each dish
            Map<Long, Map<String, Double>> dishNutrients = calculateDishNutrients(
                    ingredientsByDish, ingredientNutrients);

            // 6. 构建 Milvus 数据
            List<JsonObject> dataList = buildDishDataList(
                    dishes, ingredientsByDish, dishNutrients, schoolId);

            // 7. 批量 embedding 并 upsert
            insertToMilvus(collectionName, dataList);

            log.info("  {} sync completed: {} items", taskDesc, dishes.size());
        } catch (Exception e) {
            log.error("  {} sync failed", taskDesc, e);
        }
    }

    // ==================== 分页查询菜品 ====================

    /**
     * 分页查询菜品数据
     */
    private List<Dish> queryDishesWithPagination(Long schoolId) {
        List<Dish> allDishes = new ArrayList<>();
        int offset = 0;

        while (true) {
            String schoolCondition = schoolId == null
                    ? "AND school_id IS NULL"
                    : "AND school_id = " + schoolId;

            String sql = String.format(
                    "SELECT id, name, type, update_time FROM dish_library " +
                            "WHERE deleted = 0 AND status = 'ENABLE' %s ORDER BY id LIMIT %d OFFSET %d",
                    schoolCondition, queryPageSize, offset);

            try {
                String response = externalDbQueryService.query(dbJdbcUrl, dbUsername, dbPassword, sql, queryPageSize);
                List<Dish> page = parseDishList(response);
                if (page.isEmpty()) {
                    break;
                }
                allDishes.addAll(page);
                offset += page.size();

                if (page.size() < queryPageSize) {
                    break;
                }
            } catch (Exception e) {
                log.error("分页查询菜品失败: offset={}", offset, e);
                break;
            }
        }
        return allDishes;
    }

    // ==================== 食材/营养查询 ====================

    /**
     * 查询菜品关联的食材
     */
    private Map<Long, List<DishIngredient>> queryIngredientsByDishIds(List<Long> dishIds) {
        Map<Long, List<DishIngredient>> result = new HashMap<>();
        if (dishIds.isEmpty())
            return result;

        // 分批查询（每批1000个ID）
        for (int i = 0; i < dishIds.size(); i += 1000) {
            List<Long> batch = dishIds.subList(i, Math.min(i + 1000, dishIds.size()));
            String idList = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
            String sql = "SELECT dfi.d_id, dfi.fi_id, fi.name AS ingredient_name, dfi.dosage "
                    + "FROM dish_food_ingredient dfi "
                    + "LEFT JOIN food_ingredient fi ON dfi.fi_id = fi.id "
                    + "WHERE dfi.d_id IN (" + idList + ")";

            try {
                String response = externalDbQueryService.query(dbJdbcUrl, dbUsername, dbPassword, sql);
                List<Map<String, Object>> rows = parseJsonRows(response);
                for (Map<String, Object> row : rows) {
                    DishIngredient di = new DishIngredient();
                    di.dishId = longValue(row.get("d_id"));
                    di.ingredientId = longValue(row.get("fi_id"));
                    di.ingredientName = strValue(row.get("ingredient_name"));
                    di.dosage = doubleValue(row.get("dosage"));
                    result.computeIfAbsent(di.dishId, k -> new ArrayList<>()).add(di);
                }
            } catch (Exception e) {
                log.error("Query ingredient correlation failed", e);
            }
        }
        return result;
    }

    /**
     * Query ingredient nutrients
     */
    private Map<Long, List<IngredientNutrient>> queryNutrientsByIngredientIds(Set<Long> ingredientIds) {
        Map<Long, List<IngredientNutrient>> result = new HashMap<>();
        if (ingredientIds.isEmpty())
            return result;

        List<Long> ids = new ArrayList<>(ingredientIds);
        for (int i = 0; i < ids.size(); i += 1000) {
            List<Long> batch = ids.subList(i, Math.min(i + 1000, ids.size()));
            String idList = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
            String sql = "SELECT fin.fi_id, n.name AS nutrient_name, n.unit, fin.nutrient_content "
                    + "FROM food_ingredient_nutrient fin "
                    + "LEFT JOIN nutrient n ON fin.nutrient_id = n.id "
                    + "WHERE fin.fi_id IN (" + idList + ")";

            try {
                String response = externalDbQueryService.query(dbJdbcUrl, dbUsername, dbPassword, sql);
                List<Map<String, Object>> rows = parseJsonRows(response);
                for (Map<String, Object> row : rows) {
                    IngredientNutrient in = new IngredientNutrient();
                    in.ingredientId = longValue(row.get("fi_id"));
                    in.nutrientName = strValue(row.get("nutrient_name"));
                    in.unit = strValue(row.get("unit"));
                    in.content = doubleValue(row.get("nutrient_content"));
                    result.computeIfAbsent(in.ingredientId, k -> new ArrayList<>()).add(in);
                }
            } catch (Exception e) {
                log.error("Query nutrients failed", e);
            }
        }
        return result;
    }

    /**
     * Calculate dish aggregate nutrition (weighted by ingredient dosage)
     */
    private Map<Long, Map<String, Double>> calculateDishNutrients(
            Map<Long, List<DishIngredient>> ingredientsByDish,
            Map<Long, List<IngredientNutrient>> ingredientNutrients) {
        Map<Long, Map<String, Double>> result = new HashMap<>();

        for (Map.Entry<Long, List<DishIngredient>> entry : ingredientsByDish.entrySet()) {
            Long dishId = entry.getKey();
            Map<String, Double> nutrients = new LinkedHashMap<>();

            for (DishIngredient di : entry.getValue()) {
                List<IngredientNutrient> ins = ingredientNutrients.get(di.ingredientId);
                if (ins == null || di.dosage == null)
                    continue;

                for (IngredientNutrient in : ins) {
                    double amount = in.content * di.dosage / 100.0;
                    nutrients.merge(in.nutrientName, amount, Double::sum);
                }
            }
            if (!nutrients.isEmpty()) {
                result.put(dishId, nutrients);
            }
        }
        return result;
    }

    // ==================== 数据构建 ====================

    /**
     * 构建 Milvus 数据列表
     */
    private List<JsonObject> buildDishDataList(
            List<Dish> dishes,
            Map<Long, List<DishIngredient>> ingredientsByDish,
            Map<Long, Map<String, Double>> dishNutrients,
            Long schoolId) {

        return dishes.stream().map(dish -> {
            JsonObject data = new JsonObject();
            data.addProperty("id", dish.id);
            data.addProperty("name", dish.name);
            data.addProperty("type", dish.type != null ? dish.type : "");

            // 食材清单
            List<DishIngredient> ingredients = ingredientsByDish.getOrDefault(dish.id, Collections.emptyList());
            String ingredientNames = ingredients.stream()
                    .map(i -> i.ingredientName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            String ingredientIds = ingredients.stream()
                    .map(i -> i.ingredientId != null ? String.valueOf(i.ingredientId) : "")
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(","));
            data.addProperty("ingredients", ingredientNames);
            data.addProperty("ingredient_ids", ingredientIds);

            // 营养成分 JSON
            Map<String, Double> nutrients = dishNutrients.get(dish.id);
            if (nutrients != null && !nutrients.isEmpty()) {
                try {
                    data.addProperty("nutrients", objectMapper.writeValueAsString(nutrients));
                } catch (Exception e) {
                    data.addProperty("nutrients", "{}");
                }
            } else {
                data.addProperty("nutrients", "{}");
            }

            data.addProperty("school_id", schoolId == null ? 0L : schoolId);
            data.addProperty("update_time", dish.updateTime != null ? dish.updateTime : "");

            // embedding 文本（稍后由 insertToMilvus 灌入向量）
            String embedText = (dish.name != null ? dish.name : "")
                    + " " + (dish.type != null ? dish.type : "")
                    + " " + ingredientNames;
            data.addProperty("embedding_text", embedText);

            return data;
        }).collect(Collectors.toList());
    }

    /**
     * 批量生成嵌入向量并 upsert 到 Milvus
     */
    private void insertToMilvus(String collectionName, List<JsonObject> dataList) {
        if (dataList.isEmpty())
            return;

        // 提取 embedding 文本
        List<String> texts = dataList.stream()
                .map(d -> d.get("embedding_text").getAsString())
                .collect(Collectors.toList());

        // 批量生成嵌入向量
        List<List<Float>> embeddings = embeddingBatchService.embedBatchWithRetry(texts);

        // 将向量写回数据
        for (int i = 0; i < dataList.size(); i++) {
            if (i < embeddings.size() && !embeddings.get(i).isEmpty()) {
                dataList.get(i).add("embedding", gson.toJsonTree(embeddings.get(i)));
            }
        }

        // 批量 upsert
        milvusCollectionService.upsertBatch(collectionName, dataList, batchSize);
    }

    // ==================== 集合管理 ====================

    /**
     * 确保集合存在
     */
    private void ensureCollectionExists(String collectionName) {
        if (milvusOps.hasCollection(collectionName)) {
            return;
        }

        int dimension = embeddingService.getDimension();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field("id", DataType.Int64, true, "菜品ID"),
                        field("name", DataType.VarChar, 200, "菜品名称"),
                        field("type", DataType.VarChar, 20, "菜品类型(SYSTEM/CUSTOM)"),
                        field("ingredients", DataType.VarChar, 5000, "食材名称列表"),
                        field("ingredient_ids", DataType.VarChar, 2000, "食材ID列表"),
                        field("nutrients", DataType.VarChar, 5000, "营养成分JSON"),
                        field("school_id", DataType.Int64, "学校ID(公共为空)"),
                        field("update_time", DataType.VarChar, 50, "更新时间"),
                        field("embedding_text", DataType.VarChar, 5000, "向量化文本"),
                        vecField("embedding", dimension, "菜品向量")))
                .build();

        List<IndexParam> indexParams = Collections.singletonList(IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build());

        String description = collectionName.startsWith(COLLECTION_PREFIX)
                ? "学校专属菜品向量数据"
                : "公共菜品向量数据";

        milvusOps.createCollection(collectionName, description, schema, indexParams);
        log.info("  创建集合: {} - {}", collectionName, description);
    }

    // ==================== 数据解析 ====================

    /**
     * 将查询结果 JSON 解析为菜品列表。
     *
     * @param json 查询返回的 JSON 数组字符串
     * @return 菜品列表
     */
    private List<Dish> parseDishList(String json) {
        List<Map<String, Object>> rows = parseJsonRows(json);
        return rows.stream().map(row -> {
            Dish d = new Dish();
            d.id = longValue(row.get("id"));
            d.name = strValue(row.get("name"));
            d.type = strValue(row.get("type"));
            d.updateTime = strValue(row.get("update_time"));
            return d;
        }).collect(Collectors.toList());
    }

    /**
     * 将查询结果 JSON 解析为学校 ID 列表。
     *
     * @param json 查询返回的 JSON 数组字符串
     * @return 学校 ID 列表（过滤 null）
     */
    private List<Long> parseLongIds(String json) {
        List<Map<String, Object>> rows = parseJsonRows(json);
        return rows.stream()
                .map(row -> longValue(row.get("id")))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * 解析查询结果为 Map 行列表（容错空串与解析失败）。
     *
     * @param json JSON 数组字符串
     * @return 行列表，空或失败时返回空列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonRows(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("解析JSON结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 类型转换 ====================

    /**
     * 将任意对象安全转为 Long（数字直接取值，字符串解析）。
     *
     * @param v 原始值
     * @return Long 或 null
     */
    private static Long longValue(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number)
            return ((Number) v).longValue();
        return Long.parseLong(v.toString());
    }

    /**
     * 将任意对象安全转为 Double。
     *
     * @param v 原始值
     * @return Double 或 null
     */
    private static Double doubleValue(Object v) {
        if (v == null)
            return null;
        if (v instanceof Number)
            return ((Number) v).doubleValue();
        return Double.parseDouble(v.toString());
    }

    /**
     * 将任意对象转为字符串（null 转空串）。
     *
     * @param v 原始值
     * @return 字符串
     */
    private static String strValue(Object v) {
        return v != null ? v.toString() : "";
    }

    // ==================== Schema 构建工具 ====================

    /** 构建普通字段 schema（无长度/主键）。 */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).description(desc).build();
    }

    /** 构建带长度限制的 VarChar 字段 schema。 */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, int maxLength, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).maxLength(maxLength).description(desc).build();
    }

    /** 构建主键字段 schema（自定主键，autoID=false）。 */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, boolean primaryKey, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).isPrimaryKey(primaryKey).autoID(false).description(desc).build();
    }

    /** 构建向量字段 schema（FloatVector）。 */
    private static CreateCollectionReq.FieldSchema vecField(String name, int dimension, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(DataType.FloatVector).dimension(dimension).description(desc).build();
    }

    // ==================== 内部数据类型 ====================

    /** 菜品数据载体，承载同步过程中单条菜品的核心字段。 */
    static class Dish {
        Long id;
        String name;
        String type;
        String updateTime;
    }

    /** 菜品-食材关联关系数据载体，描述某菜品包含的食材及其用量。 */
    static class DishIngredient {
        Long dishId;
        Long ingredientId;
        String ingredientName;
        Double dosage;
    }

    /** 食材营养成分数据载体，描述某食材单条营养素及其含量与单位。 */
    static class IngredientNutrient {
        Long ingredientId;
        String nutrientName;
        String unit;
        Double content;
    }
}
