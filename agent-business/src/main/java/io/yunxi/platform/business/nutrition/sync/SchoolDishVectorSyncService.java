package io.yunxi.platform.business.nutrition.sync;

import com.google.gson.JsonObject;

import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.BaseSyncService;
import io.yunxi.platform.framework.sync.McpQueryService;
import io.yunxi.platform.framework.sync.MilvusCollectionService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import io.yunxi.platform.infra.milvus.MilvusOperations;
import io.yunxi.platform.shared.util.TextParserUtil;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 学校菜品向量同步服务
 *
 * <p>
 * 定时从营养数据库同步菜品数据，进行向量化处理后存储到Milvus
 * 支持公共菜品库（school_id=0）和学校专属菜品库（school_id>0）
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Service
@ConditionalOnProperty(name = "dish-sync.enabled", havingValue = "true")
public class SchoolDishVectorSyncService extends BaseSyncService {

    /** 公共菜品集合名称 */
    private static final String SCHOOL_COMMON_DISHES = "school_common_dishes";

    /** MCP 数据库主机地址 */
    @Value("${dish-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${dish-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 批量同步的批次大小 */
    @Value("${dish-sync.batch-size:100}")
    private int batchSize;

    /** 学校专属菜品集合名称前缀 */
    @Value("${dish-sync.collection-prefix:school_dishes_}")
    private String collectionPrefix;

    /** 分页查询的每页大小 */
    @Value("${dish-sync.query-page-size:1000}")
    private int queryPageSize;

    /** 是否在应用启动时执行同步 */
    @Value("${dish-sync.sync-on-startup:false}")
    private boolean syncOnStartup;

    /** 是否启用学校菜品同步（查询所有学校并逐个同步） */
    @Value("${dish-sync.school-sync-enabled:true}")
    private boolean schoolSyncEnabled;

    /** 按学校ID独立的防重入标记，避免不同学校之间互相阻塞（0L=公共菜品） */
    private final ConcurrentHashMap<Long, AtomicBoolean> syncingSchools = new ConcurrentHashMap<>();

    /** 菜品数据查询服务 */
    private final DishQueryService dishQueryService;
    /** 菜品数据构建服务 */
    private final DishDataBuilder dishDataBuilder;

    /**
     * 构造函数，注入依赖服务
     *
     * @param milvusOps               Milvus 操作门面
     * @param embeddingService        嵌入向量服务
     * @param mcpQueryService         MCP 数据库查询服务
     * @param milvusCollectionService Milvus 集合操作服务
     * @param embeddingBatchService   批量嵌入服务
     * @param dishQueryService        菜品数据查询服务
     * @param dishDataBuilder         菜品数据构建服务
     */
    public SchoolDishVectorSyncService(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService,
            DishQueryService dishQueryService,
            DishDataBuilder dishDataBuilder) {
        super(milvusOps, embeddingService, mcpQueryService, milvusCollectionService, embeddingBatchService);
        this.dishQueryService = dishQueryService;
        this.dishDataBuilder = dishDataBuilder;
    }

    /**
     * 应用启动完成后异步检查同步
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        log.info("应用启动完成，开始检查学校菜品向量同步状态...");
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过学校菜品向量同步");
            return;
        }
        try {
            if (syncOnStartup) {
                log.info("配置要求启动时同步（sync-on-startup=true），执行全量同步");
                syncAllSchoolDishes();
            } else {
                // 分别检查公共菜品和学校菜品的同步状态
                boolean commonDishesNeedSync = needSync(SCHOOL_COMMON_DISHES);
                boolean schoolDishesNeedSync = schoolSyncEnabled && needSyncSchoolDishes();

                if (commonDishesNeedSync || schoolDishesNeedSync) {
                    log.info("公共菜品需要同步: {}, 学校菜品需要同步: {}，执行同步", commonDishesNeedSync, schoolDishesNeedSync);
                    syncAllSchoolDishes();
                } else {
                    log.info("公共菜品和学校菜品都已有数据，跳过启动同步（定时任务将在凌晨2点执行）");
                }
            }
        } catch (Exception e) {
            log.error("启动时同步检查失败: {}", e.getMessage());
        }
    }

    /**
     * 检查学校菜品是否需要同步
     * 检查是否存在任何学校菜品集合，如果没有则返回 true
     */
    private boolean needSyncSchoolDishes() {
        List<Long> schoolIds = queryAllSchoolIds();
        if (schoolIds.isEmpty()) {
            log.info("未查询到任何学校，跳过学校菜品同步检查");
            return false;
        }

        // 检查前5个学校的集合是否存在数据
        int checkCount = Math.min(5, schoolIds.size());
        int emptyCount = 0;

        for (int i = 0; i < checkCount; i++) {
            String collectionName = collectionPrefix + schoolIds.get(i);
            long count = getCollectionCount(collectionName);
            if (count <= 0) {
                emptyCount++;
            }
        }

        // 如果超过一半的学校集合为空，则认为需要同步
        boolean needSync = emptyCount > checkCount / 2;
        log.info("学校菜品同步检查: 检查 {} 所学校, {} 个集合为空, 需要同步: {}", checkCount, emptyCount, needSync);
        return needSync;
    }

    /**
     * 检查集合是否需要同步（集合为空时需要同步）
     *
     * @param collectionName 集合名称
     * @return true-需要同步，false-已有数据无需同步
     */
    private boolean needSync(String collectionName) {
        long count = getCollectionCount(collectionName);
        if (count < 0) {
            log.warn("无法获取集合 {} 的记录数，默认需要同步", collectionName);
            return true;
        }
        if (count == 0) {
            log.info("集合 {} 无数据，需要同步", collectionName);
            return true;
        }
        log.info("集合 {} 已有 {} 条数据，无需同步", collectionName, count);
        return false;
    }

    /**
     * 定时同步任务（公共菜品库 + 学校专属菜品库）
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${dish-sync.cron:0 0 2 * * ?}")
    public void syncDishVectors() {
        syncAllSchoolDishes();
    }

    /**
     * 同步所有学校菜品（公共菜品 + 各学校专属菜品）
     *
     * <p>
     * 先同步公共菜品库，再查询所有学校列表，逐个同步学校专属菜品。
     * 单个学校同步失败不影响其他学校。
     * </p>
     */
    public void syncAllSchoolDishes() {
        // 1. 同步公共菜品
        syncSchoolDishes(null);

        // 2. 同步各学校专属菜品
        if (!schoolSyncEnabled) {
            log.info("学校菜品同步已关闭（dish-sync.school-sync-enabled=false），跳过学校菜品同步");
            return;
        }

        List<Long> schoolIds = queryAllSchoolIds();
        if (schoolIds.isEmpty()) {
            log.info("未查询到任何学校，跳过学校菜品同步");
            return;
        }

        log.info("开始同步 {} 所学校的专属菜品", schoolIds.size());
        int successCount = 0;
        int failCount = 0;

        for (Long schoolId : schoolIds) {
            try {
                syncSchoolDishes(schoolId);
                successCount++;
                // 每个学校同步间隔1秒，避免对 Milvus 和 MCP 数据库造成压力
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("学校菜品同步被中断");
                break;
            } catch (Exception e) {
                failCount++;
                log.error("学校{}菜品同步失败，继续同步下一个学校", schoolId, e);
            }
        }

        log.info("学校菜品同步完成：成功 {} 所，失败 {} 所", successCount, failCount);
    }

    /**
     * 查询所有学校ID列表（通过 MCP 数据库工具查询营养数据库的 institution 表）
     *
     * @return 学校ID列表，查询失败返回空列表
     */
    private List<Long> queryAllSchoolIds() {
        try {
            // 使用 institution 表查询学校，type='SCHOOL' 表示学校类型
            String sql = "SELECT id FROM institution WHERE type = 'SCHOOL' AND deleted = 0";
            String result = callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);

            if (result == null || result.isEmpty()) {
                log.warn("MCP 查询学校列表响应为空");
                return Collections.emptyList();
            }

            List<Long> schoolIds = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(result);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Long id = TextParserUtil.parseLong(row, "id");
                    if (id != null) {
                        schoolIds.add(id);
                    }
                }
            }

            log.info("查询到 {} 所学校", schoolIds.size());
            return schoolIds;
        } catch (Exception e) {
            log.error("查询学校列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取已同步的学校列表（查询 Milvus 中 school_dishes_* 前缀的集合）
     *
     * @return 已同步的学校ID列表
     */
    public List<Long> getSyncedSchools() {
        if (!milvusOps.isAvailable()) {
            return Collections.emptyList();
        }
        try {
            List<String> collections = milvusOps.listCollections();
            return collections.stream()
                    .filter(name -> name.startsWith(collectionPrefix))
                    .map(name -> {
                        try {
                            return Long.parseLong(name.substring(collectionPrefix.length()));
                        } catch (NumberFormatException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (Exception e) {
            log.error("查询已同步学校列表失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 同步指定学校的菜品
     *
     * @param schoolId 学校ID，null表示同步公共菜品库
     */
    public void syncSchoolDishes(Long schoolId) {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过学校菜品向量同步任务");
            return;
        }
        // 按学校ID独立锁定，公共菜品用0L
        long lockKey = schoolId == null ? 0L : schoolId;
        AtomicBoolean schoolSyncing = syncingSchools.computeIfAbsent(lockKey, k -> new AtomicBoolean(false));
        if (!schoolSyncing.compareAndSet(false, true)) {
            log.warn("学校{}菜品向量同步任务正在执行中，跳过本次请求", lockKey);
            return;
        }
        String collectionName = schoolId == null ? SCHOOL_COMMON_DISHES : collectionPrefix + schoolId;
        String taskDesc = schoolId == null ? "公共菜品库" : "学校" + schoolId + "专属菜品库";

        log.info("========== 开始{}菜品向量同步任务 ==========", taskDesc);
        long startTime = System.currentTimeMillis();

        try {
            // 1. 确保集合存在
            if (schoolId == null) {
                ensureCollectionExists(SCHOOL_COMMON_DISHES);
            } else {
                ensureSchoolDishesCollection(schoolId);
            }

            // 2. 分页查询所有菜品（支持超过10000条记录）
            List<DishQueryService.Dish> dishes = dishQueryService.querySchoolDishesWithPagination(schoolId);
            if (dishes.isEmpty()) {
                log.info("{}菜品数据为空，跳过同步", taskDesc);
                return;
            }
            // 3. 查询菜品食材
            List<Long> dishIds = dishes.stream().map(DishQueryService.Dish::getId).toList();
            List<DishQueryService.DishIngredient> dishIngredients = dishQueryService
                    .queryDishIngredientsFromDatabase(dishIds);

            // 4. 查询食材营养成分
            List<Long> ingredientIds = dishIngredients.stream()
                    .map(DishQueryService.DishIngredient::getIngredientId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            Map<Long, List<DishQueryService.IngredientNutrient>> ingredientNutrients = dishQueryService
                    .queryIngredientNutrientsFromDatabase(
                            ingredientIds);

            // 5. 计算每个菜品的总营养成分
            Map<Long, Map<String, Double>> dishNutrients = dishDataBuilder.calculateDishNutrients(dishIngredients,
                    ingredientNutrients);

            // 6. 构建菜品数据
            List<JsonObject> dishDataList = new ArrayList<>();
            Map<Long, List<DishQueryService.DishIngredient>> ingredientsByDish = dishIngredients.stream()
                    .collect(java.util.stream.Collectors.groupingBy(DishQueryService.DishIngredient::getDishId));

            for (DishQueryService.Dish dish : dishes) {
                Long dishId = dish.getId();
                List<DishQueryService.DishIngredient> ingredients = ingredientsByDish.getOrDefault(dishId,
                        Collections.emptyList());
                Map<String, Double> nutrients = dishNutrients.getOrDefault(dishId, Collections.emptyMap());

                // 构建向量数据（embedding在insertDishesToMilvus中批量生成）
                JsonObject dishData = dishDataBuilder.buildDishVectorData(dish, ingredients, nutrients, null, schoolId);
                dishDataList.add(dishData);
            }

            // 7. 插入 Milvus
            insertDishesToMilvus(collectionName, dishDataList);

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== {}菜品向量同步任务完成，共同步 {} 条菜品，耗时 {} ms ==========",
                    taskDesc, dishDataList.size(), duration);

        } catch (Exception e) {
            log.error("{}菜品向量同步任务异常", taskDesc, e);
        } finally {
            schoolSyncing.set(false);
        }
    }

    /**
     * 按学校ID搜索菜品
     *
     * @param schoolId  学校ID
     * @param queryText 查询文本
     * @param topK      返回结果数量
     * @return 搜索到的菜品数据列表
     */
    public List<DishData> searchDishesBySchool(Long schoolId, String queryText, int topK) {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，无法搜索菜品");
            return new ArrayList<>();
        }

        String collectionName = collectionPrefix + schoolId;
        log.info("搜索菜品: schoolId={}, queryText={}, topK={}", schoolId, queryText, topK);

        try {
            if (isCollectionExists(collectionName)) {
                return searchDishes(collectionName, queryText, topK, schoolId);
            }
        } catch (Exception e) {
            log.warn("学校专属菜品库搜索失败: {}", e.getMessage());
        }

        // Fallback 到公共菜品库
        try {
            return searchDishes(SCHOOL_COMMON_DISHES, queryText, topK, null);
        } catch (Exception e) {
            log.error("公共菜品库搜索失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 在指定集合中搜索菜品
     *
     * @param collectionName 集合名称
     * @param queryText      查询文本
     * @param topK           返回结果数量
     * @param filterSchoolId 过滤条件中的学校ID，null表示不过滤
     * @return 搜索到的菜品数据列表
     */
    private List<DishData> searchDishes(String collectionName, String queryText, int topK, Long filterSchoolId) {
        try {
            List<Float> embedding = embeddingService.embed(queryText);

            String filterExpr = filterSchoolId != null ? "school_id == " + filterSchoolId : null;
            List<SearchResp.SearchResult> searchResults = milvusOps.search(
                    collectionName,
                    embedding,
                    "embedding",
                    topK,
                    filterExpr,
                    Arrays.asList("id", "name", "type", "ingredients", "ingredient_ids"));

            List<DishData> results = new ArrayList<>();
            for (SearchResp.SearchResult hit : searchResults) {
                Map<String, Object> entity = hit.getEntity();
                DishData dish = new DishData();
                dish.setId(getLongValue(entity, "id"));
                dish.setName(getStringValue(entity, "name"));
                dish.setType(getStringValue(entity, "type"));
                dish.setIngredients(getStringValue(entity, "ingredients"));
                dish.setIngredientIds(getStringValue(entity, "ingredient_ids"));
                results.add(dish);
            }

            return results;
        } catch (Exception e) {
            log.error("搜索菜品失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== MCP 数据库调用（使用基类方法） ====================

    /**
     * 从实体映射中获取 Long 类型值
     *
     * @param entity 实体属性映射
     * @param key    属性键名
     * @return Long 值，不存在或类型不匹配返回 null
     */
    private Long getLongValue(Map<String, Object> entity, String key) {
        Object value = entity.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return null;
    }

    /**
     * 从实体映射中获取 String 类型值
     *
     * @param entity 实体属性映射
     * @param key    属性键名
     * @return 字符串值，不存在返回 null
     */
    private String getStringValue(Map<String, Object> entity, String key) {
        Object value = entity.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 获取同步状态信息
     *
     * @return 状态信息Map
     */
    public Map<String, Object> getSyncStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        status.put("syncOnStartup", syncOnStartup);
        status.put("schoolSyncEnabled", schoolSyncEnabled);

        // 当前正在同步的学校列表
        List<Long> syncingList = syncingSchools.entrySet().stream()
                .filter(e -> e.getValue().get())
                .map(Map.Entry::getKey)
                .toList();
        status.put("syncingSchools", syncingList);
        status.put("isSyncing", !syncingList.isEmpty());

        if (milvusOps.isAvailable()) {
            long count = getCollectionCount(SCHOOL_COMMON_DISHES);
            status.put("collectionName", SCHOOL_COMMON_DISHES);
            status.put("recordCount", count);
            status.put("needsSync", count <= 0);

            // 已同步的学校列表
            List<Long> syncedSchools = getSyncedSchools();
            status.put("syncedSchoolCount", syncedSchools.size());
            status.put("syncedSchools", syncedSchools);
        } else {
            status.put("collectionName", SCHOOL_COMMON_DISHES);
            status.put("recordCount", -1);
            status.put("needsSync", true);
            status.put("milvusAvailable", false);
        }

        return status;
    }

    /**
     * 手动触发同步（供API调用）
     *
     * @param force 是否强制同步（true时跳过needSync检查）
     * @return 同步结果
     */
    public SyncResult manualSync(boolean force) {
        if (!milvusOps.isAvailable()) {
            SyncResult result = new SyncResult();
            result.setSuccess(false);
            result.setDuration(0);
            result.setMessage("Milvus 不可用，无法执行同步");
            return result;
        }

        if (!force && !needSync(SCHOOL_COMMON_DISHES)) {
            SyncResult result = new SyncResult();
            result.setSuccess(true);
            result.setDuration(0);
            result.setMessage("集合已有数据，无需同步。如需强制同步请使用 force=true");
            return result;
        }

        long startTime = System.currentTimeMillis();
        syncAllSchoolDishes();
        long duration = System.currentTimeMillis() - startTime;

        SyncResult result = new SyncResult();
        result.setSuccess(true);
        result.setDuration(duration);
        result.setMessage(force ? "强制同步完成" : "同步完成");
        return result;
    }

    /**
     * 菜品数据模型
     */
    @Data
    public static class DishData {
        /** 菜品ID */
        private Long id;
        /** 菜品名称 */
        private String name;
        /** 菜品类型 */
        private String type;
        /** 食材列表（逗号分隔） */
        private String ingredients;
        /** 食材ID列表（格式：id1:name1,id2:name2） */
        private String ingredientIds;
        /** 营养成分（JSON格式） */
        private String nutrients;
        /** 学校ID */
        private Long schoolId;
        /** 更新时间 */
        private String updateTime;
        /** 是否为公共菜品 */
        private boolean isCommon;
    }

    /** 菜品基本信息（内部使用） */
    @Data
    static class Dish {
        /** 菜品ID */
        Long id;
        /** 菜品名称 */
        String name;
        /** 菜品类型 */
        String type;
        /** 更新时间 */
        String updateTime;
    }

    /** 菜品食材关系（内部使用） */
    @Data
    static class DishIngredient {
        /** 菜品ID */
        Long dishId;
        /** 食材ID */
        Long ingredientId;
        /** 食材名称 */
        String ingredientName;
        /** 用量（g） */
        Double dosage;
    }

    /** 食材营养成分（内部使用） */
    @Data
    static class IngredientNutrient {
        /** 食材ID */
        Long ingredientId;
        /** 营养素名称 */
        String nutrientName;
        /** 营养素单位 */
        String unit;
        /** 含量（mg/100g） */
        Double content;
    }

    /** 同步结果数据传输对象 */
    @Data
    public static class SyncResult {
        /** 是否成功 */
        private boolean success;
        /** 同步耗时（毫秒） */
        private long duration;
        /** 结果消息 */
        private String message;
    }

    // ==================== 工具方法（使用基类方法） ====================

    // ==================== 数据解析方法 ====================

    // ==================== 数据库查询方法 ====================

    /**
     * 查询菜品数据
     *
     * @param schoolId 学校ID，null表示查询公共菜品库
     * @return 菜品列表
     */
    private List<Dish> queryDishesFromDatabase(Long schoolId) {
        try {
            String sql;
            if (schoolId == null) {
                // 查询公共菜品库：school_id IS NULL
                sql = "SELECT id, name, type, update_time FROM dish_library WHERE deleted = 0 AND status = 'ENABLE' AND school_id IS NULL";
            } else {
                // 查询学校专属菜品库
                sql = "SELECT id, name, type, update_time FROM dish_library WHERE deleted = 0 AND status = 'ENABLE' AND school_id = "
                        + schoolId;
            }

            log.info("========== 开始查询菜品数据 ==========");
            log.info("MCP地址: {}:{}", mcpDbHost, mcpDbPort);
            log.info("执行SQL: {}", sql);

            String response = callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);

            log.info("MCP调用完成，响应长度: {}", response != null ? response.length() : "null");

            if (response == null) {
                log.error("MCP响应为null");
                return Collections.emptyList();
            }

            log.info("MCP响应前500字符: {}", response.length() > 500 ? response.substring(0, 500) : response);

            List<Dish> dishes = parseDishes(response);
            log.info("解析完成，查询到 {} 个菜品", dishes.size());
            log.info("========== 查询菜品数据结束 ==========");
            return dishes;
        } catch (Exception e) {
            log.error("查询菜品数据失败，异常信息: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 分页查询菜品数据
     *
     * @param schoolId 学校ID，null表示查询公共菜品库
     * @return 菜品列表（支持超过10000条记录）
     */
    private List<Dish> querySchoolDishesWithPagination(Long schoolId) {
        List<Dish> allDishes = new ArrayList<>();
        int offset = 0;
        int pageNumber = 1;

        try {
            String schoolCondition;
            if (schoolId == null) {
                // 公共菜品库：school_id IS NULL（系统菜品没有关联学校）
                schoolCondition = "AND school_id IS NULL";
            } else {
                schoolCondition = "AND school_id = " + schoolId;
            }

            log.info("========== 开始分页查询菜品数据 ==========");
            log.info("MCP地址: {}:{}", mcpDbHost, mcpDbPort);
            log.info("分页大小: {}", queryPageSize);
            log.info("查询条件: {}", schoolCondition);

            while (true) {
                String sql = String.format(
                        "SELECT id, name, type, update_time FROM dish_library " +
                                "WHERE deleted = 0 AND status = 'ENABLE' %s LIMIT %d OFFSET %d",
                        schoolCondition, queryPageSize, offset);

                log.info("执行SQL (第{}页): {}", pageNumber, sql);

                String response = callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);

                if (response == null) {
                    log.error("MCP响应为null，分页查询中止");
                    break;
                }

                List<Dish> dishes = parseDishes(response);
                log.info("第{}页查询到 {} 个菜品，累计 {} 个",
                        pageNumber, dishes.size(), allDishes.size() + dishes.size());

                if (dishes.isEmpty()) {
                    log.info("第{}页无数据，分页查询结束", pageNumber);
                    break;
                }

                allDishes.addAll(dishes);

                // 如果返回的数据量小于分页大小，说明已经是最后一页
                if (dishes.size() < queryPageSize) {
                    log.info("第{}页数据量 {} 小于分页大小 {}，分页查询完成",
                            pageNumber, dishes.size(), queryPageSize);
                    break;
                }

                offset += queryPageSize;
                pageNumber++;
            }

            log.info("分页查询完成，总共查询到 {} 个菜品", allDishes.size());
            log.info("========== 分页查询菜品数据结束 ==========");
            return allDishes;

        } catch (Exception e) {
            log.error("分页查询菜品数据失败，异常信息: {}", e.getMessage(), e);
            // 返回已查询到的数据
            return allDishes;
        }
    }

    /**
     * 批量查询菜品食材数据
     *
     * @param dishIds 菜品ID列表
     * @return 菜品食材关系列表
     */
    private List<DishIngredient> queryDishIngredientsFromDatabase(List<Long> dishIds) {
        if (dishIds == null || dishIds.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 分批查询，避免 IN 子句过长
            List<DishIngredient> result = new ArrayList<>();
            int batchSize = 1000;
            for (int i = 0; i < dishIds.size(); i += batchSize) {
                int end = Math.min(i + batchSize, dishIds.size());
                List<Long> batch = dishIds.subList(i, end);

                String idsStr = batch.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                // dish_food_ingredient表没有deleted字段
                // 不过滤food_ingredient.deleted，确保能查到所有菜品关联的食材
                String sql = String.format(
                        "SELECT dfi.d_id, dfi.fi_id, dfi.dosage, fi.name AS ingredient_name " +
                                "FROM dish_food_ingredient dfi " +
                                "LEFT JOIN food_ingredient fi ON dfi.fi_id = fi.id " +
                                "WHERE dfi.d_id IN (%s)",
                        idsStr);

                String response = callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);
                result.addAll(parseDishIngredients(response));
            }

            log.info("查询到 {} 条菜品食材关联数据", result.size());
            return result;
        } catch (Exception e) {
            log.error("批量查询菜品食材失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 批量查询食材营养成分数据
     *
     * @param ingredientIds 食材ID列表
     * @return 食材ID到营养成分列表的映射
     */
    private Map<Long, List<IngredientNutrient>> queryIngredientNutrientsFromDatabase(List<Long> ingredientIds) {
        if (ingredientIds == null || ingredientIds.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            // 分批查询
            Map<Long, List<IngredientNutrient>> result = new HashMap<>();
            int batchSize = 1000;
            for (int i = 0; i < ingredientIds.size(); i += batchSize) {
                int end = Math.min(i + batchSize, ingredientIds.size());
                List<Long> batch = ingredientIds.subList(i, end);

                String idsStr = batch.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                // food_ingredient_nutrient表没有deleted字段
                // 不过滤nutrient.deleted，确保能查到所有食材的营养成分
                String sql = String.format(
                        "SELECT fin.fi_id, n.name AS nutrient_name, n.unit, fin.nutrient_content " +
                                "FROM food_ingredient_nutrient fin " +
                                "LEFT JOIN nutrient n ON fin.nutrient_id = n.id " +
                                "WHERE fin.fi_id IN (%s)",
                        idsStr);

                String response = callMcpDatabase(mcpDbHost, mcpDbPort, sql, 10000);
                result.putAll(parseIngredientNutrients(response));
            }

            log.info("查询到 {} 个食材的营养成分数据", result.size());
            return result;
        } catch (Exception e) {
            log.error("批量查询食材营养成分失败", e);
            return Collections.emptyMap();
        }
    }

    // ==================== 营养成分计算 ====================

    /**
     * 计算菜品总营养成分
     *
     * @param dishIngredients 菜品的食材列表
     * @param nutrientMap     食材营养成分映射
     * @return Map<菜品ID, Map<营养素名称, 总含量>>
     */
    private Map<Long, Map<String, Double>> calculateDishNutrients(
            List<DishIngredient> dishIngredients,
            Map<Long, List<IngredientNutrient>> nutrientMap) {

        Map<Long, Map<String, Double>> result = new HashMap<>();

        // 按菜品ID分组
        Map<Long, List<DishIngredient>> ingredientsByDish = new HashMap<>();
        for (DishIngredient di : dishIngredients) {
            ingredientsByDish.computeIfAbsent(di.getDishId(), k -> new ArrayList<>()).add(di);
        }

        // 计算每个菜品的总营养成分
        for (Map.Entry<Long, List<DishIngredient>> entry : ingredientsByDish.entrySet()) {
            Long dishId = entry.getKey();
            List<DishIngredient> ingredients = entry.getValue();
            Map<String, Double> dishNutrients = new HashMap<>();

            for (DishIngredient di : ingredients) {
                Long ingredientId = di.getIngredientId();
                double dosage = di.getDosage() != null ? di.getDosage() : 0.0;

                // 获取该食材的营养成分
                List<IngredientNutrient> nutrients = nutrientMap.get(ingredientId);
                if (nutrients == null || nutrients.isEmpty()) {
                    log.debug("食材 {} (用量={}) 没有营养成分数据，跳过", ingredientId, dosage);
                    continue;
                }

                // 计算每种营养素的贡献
                for (IngredientNutrient in : nutrients) {
                    String nutrientName = in.getNutrientName();
                    double contentPer100g = in.getContent() != null ? in.getContent() : 0.0;

                    // 菜品总营养 = 食材营养含量 × 用量 / 100
                    double contribution = contentPer100g * dosage / 100.0;

                    // 累加到菜品总营养
                    dishNutrients.merge(nutrientName, contribution, Double::sum);
                }
            }

            result.put(dishId, dishNutrients);
        }

        log.info("计算完成 {} 个菜品的营养成分", result.size());
        return result;
    }

    // ==================== 数据构建和向量化 ====================

    /**
     * 构建菜品向量数据
     *
     * @param dish          菜品基本信息
     * @param ingredients   菜品食材列表
     * @param dishNutrients 菜品营养成分
     * @param embedding     向量
     * @param schoolId      学校ID，null表示公共菜品库
     * @return JsonObject
     */
    private JsonObject buildDishVectorData(
            Dish dish,
            List<DishIngredient> ingredients,
            Map<String, Double> dishNutrients,
            List<Float> embedding,
            Long schoolId) {

        JsonObject data = new JsonObject();

        // 基本字段
        data.addProperty("id", dish.getId());
        data.addProperty("name", dish.getName());
        data.addProperty("type", dish.getType() != null ? dish.getType() : "");
        data.addProperty("update_time", dish.getUpdateTime() != null ? dish.getUpdateTime() : "");

        // 食材列表（逗号分隔）
        String ingredientNames = ingredients.stream()
                .map(DishIngredient::getIngredientName)
                .filter(name -> name != null && !name.isEmpty())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        data.addProperty("ingredients", ingredientNames);

        // 食材ID列表（格式：id1:name1,id2:name2）
        String ingredientIds = ingredients.stream()
                .filter(di -> di.getIngredientId() != null && di.getIngredientName() != null)
                .map(di -> di.getIngredientId() + ":" + di.getIngredientName())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        data.addProperty("ingredient_ids", ingredientIds);

        // 营养成分（JSON格式）
        String nutrientsJson = gson.toJson(dishNutrients);
        data.addProperty("nutrients", nutrientsJson);

        // 向量（在insertDishesToMilvus中批量生成后填入）
        if (embedding != null) {
            data.add("embedding", gson.toJsonTree(embedding));
        }

        // 学校ID（null或0表示公共菜品）
        data.addProperty("school_id", schoolId == null ? 0L : schoolId);

        return data;
    }

    /**
     * 生成菜品文本描述（用于向量化）
     *
     * @param dish        菜品基本信息
     * @param ingredients 菜品食材列表
     * @return 用于生成向量的文本字符串
     */
    private String buildDishEmbeddingText(Dish dish, List<DishIngredient> ingredients) {
        String ingredientNames = ingredients.stream()
                .map(DishIngredient::getIngredientName)
                .filter(name -> name != null && !name.isEmpty())
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        return String.format("%s %s %s",
                dish.getName(),
                dish.getType() != null ? dish.getType() : "",
                ingredientNames);
    }

    // ==================== Milvus 集合管理 ====================

    /**
     * 确保集合存在
     *
     * @param collectionName 集合名称
     */
    private void ensureCollectionExists(String collectionName) {
        try {
            if (!milvusOps.hasCollection(collectionName)) {
                log.info("创建菜品集合: {}", collectionName);
                CreateCollectionReq createReq = createCommonDishesSchema(collectionName);
                milvusOps.createCollection(collectionName, createReq.getCollectionSchema(), createReq.getIndexParams());
                log.info("集合创建成功: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
        }
    }

    /**
     * 确保学校专属菜品集合存在
     *
     * @param schoolId 学校ID
     */
    private void ensureSchoolDishesCollection(Long schoolId) {
        String collectionName = collectionPrefix + schoolId;
        try {
            if (!milvusOps.hasCollection(collectionName)) {
                log.info("创建学校{}专属菜品集合: {}", schoolId, collectionName);
                CreateCollectionReq createReq = createCommonDishesSchema(collectionName);
                milvusOps.createCollection(collectionName, createReq.getCollectionSchema(), createReq.getIndexParams());
                log.info("学校{}专属菜品集合创建成功: {}", schoolId, collectionName);
            }
        } catch (Exception e) {
            log.error("确保学校{}专属菜品集合存在失败: {}", schoolId, collectionName, e);
        }
    }

    /**
     * 创建菜品集合 Schema
     *
     * @param collectionName 集合名称
     * @return Milvus 集合创建请求对象
     */
    private CreateCollectionReq createCommonDishesSchema(String collectionName) {
        int dimension = embeddingService.getDimension();

        // 主键 ID 字段
        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.Int64)
                .isPrimaryKey(true)
                .autoID(false)
                .description("菜品ID")
                .build();

        // 菜品名称
        CreateCollectionReq.FieldSchema nameField = CreateCollectionReq.FieldSchema.builder()
                .name("name")
                .dataType(DataType.VarChar)
                .maxLength(200)
                .description("菜品名称")
                .build();

        // 菜品类型
        CreateCollectionReq.FieldSchema typeField = CreateCollectionReq.FieldSchema.builder()
                .name("type")
                .dataType(DataType.VarChar)
                .maxLength(100)
                .description("菜品类型")
                .build();

        // 食材列表
        CreateCollectionReq.FieldSchema ingredientsField = CreateCollectionReq.FieldSchema.builder()
                .name("ingredients")
                .dataType(DataType.VarChar)
                .maxLength(2000)
                .description("食材列表（逗号分隔）")
                .build();

        // 食材ID列表
        CreateCollectionReq.FieldSchema ingredientIdsField = CreateCollectionReq.FieldSchema.builder()
                .name("ingredient_ids")
                .dataType(DataType.VarChar)
                .maxLength(2000)
                .description("食材ID列表（格式：id1:name1,id2:name2）")
                .build();

        // 营养成分
        CreateCollectionReq.FieldSchema nutrientsField = CreateCollectionReq.FieldSchema.builder()
                .name("nutrients")
                .dataType(DataType.VarChar)
                .maxLength(5000)
                .description("营养成分（JSON格式）")
                .build();

        // 学校ID
        CreateCollectionReq.FieldSchema schoolIdField = CreateCollectionReq.FieldSchema.builder()
                .name("school_id")
                .dataType(DataType.Int64)
                .description("学校ID（公共菜品为0，学校专属菜品为学校ID）")
                .build();

        // 更新时间
        CreateCollectionReq.FieldSchema updateTimeField = CreateCollectionReq.FieldSchema.builder()
                .name("update_time")
                .dataType(DataType.VarChar)
                .maxLength(50)
                .description("更新时间")
                .build();

        // 向量字段
        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .description("向量表示")
                .build();

        // 创建集合 Schema
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        idField, nameField, typeField, ingredientsField, ingredientIdsField,
                        nutrientsField, schoolIdField, updateTimeField, embeddingField))
                .build();

        // 创建索引参数
        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(collectionName)
                .description("菜品向量数据（支持公共菜品和学校专属菜品）")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 批量插入菜品数据到 Milvus
     *
     * @param collectionName Milvus 集合名称
     * @param dishes         菜品数据列表
     */
    private void insertDishesToMilvus(String collectionName, List<JsonObject> dishes) {
        if (dishes == null || dishes.isEmpty()) {
            return;
        }

        log.info("开始向量化并插入 {} 条菜品数据到 {}", dishes.size(), collectionName);

        // 1. 准备文本列表用于批量向量化
        List<String> texts = dishes.stream()
                .map(d -> d.get("name").getAsString() + " " +
                        d.get("type").getAsString() + " " +
                        d.get("ingredients").getAsString())
                .toList();

        // 2. 批量生成 embedding
        List<List<Float>> allEmbeddings = embedBatchWithRetry(texts);

        // 3. 将 embedding 添加到数据中
        for (int i = 0; i < dishes.size(); i++) {
            dishes.get(i).add("embedding", gson.toJsonTree(allEmbeddings.get(i)));
        }

        // 4. 批量 upsert Milvus（避免重启时产生重复数据）
        upsertBatch(collectionName, dishes, batchSize);

        log.info("菜品数据插入完成");
    }

    /**
     * 解析菜品数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 菜品列表
     */
    private List<Dish> parseDishes(String response) {
        List<Dish> result = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");

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
     * 解析菜品食材数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 菜品食材关系列表
     */
    private List<DishIngredient> parseDishIngredients(String response) {
        List<DishIngredient> result = new ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");

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

                    // 解析用量（可能是数字字符串）
                    String dosageStr = TextParserUtil.parseString(row, "dosage");
                    if (dosageStr != null) {
                        try {
                            di.setDosage(Double.parseDouble(dosageStr));
                        } catch (NumberFormatException e) {
                            di.setDosage(0.0);
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
     * 解析食材营养成分数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 食材ID到营养成分列表的映射
     */
    private Map<Long, List<IngredientNutrient>> parseIngredientNutrients(String response) {
        Map<Long, List<IngredientNutrient>> result = new HashMap<>();
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(response);
            com.fasterxml.jackson.databind.JsonNode content = root.path("result").path("content");

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

                    // 解析含量（可能是数字字符串）
                    String contentStr = TextParserUtil.parseString(row, "nutrient_content");
                    if (contentStr != null) {
                        try {
                            in.setContent(Double.parseDouble(contentStr));
                        } catch (NumberFormatException e) {
                            in.setContent(0.0);
                        }
                    }

                    if (ingredientId != null && in.getNutrientName() != null) {
                        result.computeIfAbsent(ingredientId, k -> new ArrayList<>()).add(in);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析食材营养成分数据失败", e);
        }
        return result;
    }
}
