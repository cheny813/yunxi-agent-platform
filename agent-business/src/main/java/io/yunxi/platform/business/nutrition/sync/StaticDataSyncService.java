package io.yunxi.platform.business.nutrition.sync;

import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.BaseSyncService;
import io.yunxi.platform.framework.sync.McpQueryService;
import io.yunxi.platform.framework.sync.MilvusCollectionService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import io.yunxi.platform.infra.milvus.MilvusOperations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

/**
 * 静态数据同步服务（编排器）
 *
 * <p>
 * 从营养数据库同步静态数据到 Milvus，供 AI 查询使用。
 * 各集合的同步逻辑已拆分到独立的 {@link SyncHandler} 实现中，
 * 本类负责编排：集合初始化、Schema 迁移、按需同步。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "static-sync.enabled", havingValue = "true")
public class StaticDataSyncService extends BaseSyncService {

    /** 营养标准集合名称 */
    private static final String NUTRITION_STANDARDS_COLLECTION = "nutrition_standards";
    /** 食材营养成分集合名称 */
    private static final String INGREDIENT_NUTRIENTS_COLLECTION = "ingredient_nutrients";
    /** 评分指标集合名称 */
    private static final String SCORE_INDEX_COLLECTION = "cook_book_score_index";
    /** 食材分类集合名称 */
    private static final String INGREDIENT_CLASSES_COLLECTION = "ingredient_classes";
    /** 菜谱分类集合名称 */
    private static final String DISH_CLASSES_COLLECTION = "dish_classes";
    /** 营养素详情集合名称 */
    private static final String NUTRIENT_STANDARD_DETAILS_COLLECTION = "nutrient_standard_details";

    /** Handler：营养标准同步 */
    @Autowired
    private NutritionStandardsSyncHandler nutritionStandardsSyncHandler;

    /** Handler：食材营养成分同步 */
    @Autowired
    private IngredientNutrientsSyncHandler ingredientNutrientsSyncHandler;

    /** Handler：评分指标同步 */
    @Autowired
    private ScoreIndexSyncHandler scoreIndexSyncHandler;

    /** Handler：食材分类同步 */
    @Autowired
    private IngredientClassesSyncHandler ingredientClassesSyncHandler;

    /** Handler：菜谱分类同步 */
    @Autowired
    private DishClassesSyncHandler dishClassesSyncHandler;

    /** Handler：营养素详情同步 */
    @Autowired
    private NutrientStandardDetailsSyncHandler nutrientStandardDetailsSyncHandler;

    /** Handler：区域菜系字典（可选注入，启用时才存在） */
    @Autowired(required = false)
    private RegionalCuisineSyncHandler regionalCuisineSyncHandler;

    /** Handler：食材季节性属性（可选注入，启用时才存在） */
    @Autowired(required = false)
    private IngredientSeasonalitySyncHandler ingredientSeasonalitySyncHandler;

    /** Handler：学校民族配置（可选注入，启用时才存在） */
    @Autowired(required = false)
    private SchoolEthnicConfigSyncHandler schoolEthnicConfigHandler;

    /** MCP 数据库主机地址 */
    @Value("${static-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${static-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 批量同步的批次大小 */
    @Value("${static-sync.batch-size:100}")
    private int batchSize;

    /** 向量格式（默认 json_array） */
    @Value("${static-sync.embedding-format:json_array}")
    private String embeddingFormat;

    /** 是否启用异步同步 */
    @Value("${static-sync.async:false}")
    private boolean asyncSync;

    /**
     * 构造函数，注入依赖服务
     *
     * @param milvusOps                Milvus 操作门面
     * @param embeddingService         嵌入向量服务
     * @param mcpQueryService          MCP 数据库查询服务
     * @param milvusCollectionService  Milvus 集合操作服务
     * @param embeddingBatchService    批量嵌入服务
     */
    public StaticDataSyncService(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        super(milvusOps, embeddingService, mcpQueryService, milvusCollectionService, embeddingBatchService);
    }

    /**
     * 启动时初始化
     */
    @PostConstruct
    public void init() {
        log.info("========== 初始化静态数据同步服务 (async: {}) ==========", asyncSync);

        try {
            // 0.5. 检测并迁移 schema 变更（仅在不匹配时才重建）
            migrateSchemaChanges();

            // 1. 确保所有集合存在
            ensureCollectionsExist();

            // 2. 检查是否需要同步
            if (needSync()) {
                if (asyncSync) {
                    log.info("异步执行数据同步...");
                    performSyncAsync();
                } else {
                    log.info("同步执行数据同步...");
                    syncAll();
                }
            } else {
                log.info("静态数据已是最新，跳过同步");
            }

        } catch (Exception e) {
            log.error("静态数据同步服务初始化失败", e);
        }
    }

    /**
     * 检测并迁移 schema 变更（仅在 schema 不匹配时才重建集合）
     */
    private void migrateSchemaChanges() {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过 Schema 迁移检查");
            return;
        }
        try {
            migrateCollectionIfNeeded(NUTRIENT_STANDARD_DETAILS_COLLECTION, "autoID");
            migrateCollectionIfNeeded(NUTRIENT_STANDARD_DETAILS_COLLECTION, "nullable_nsc_id");
            migrateCollectionIfNeeded(INGREDIENT_CLASSES_COLLECTION, "nullable_parent_id");
            migrateCollectionIfNeeded(DISH_CLASSES_COLLECTION, "nullable_parent_id");
            migrateCollectionIfNeeded(SCORE_INDEX_COLLECTION, "score_index_schema_v2");
        } catch (Exception e) {
            log.error("Schema 迁移失败", e);
        }
    }

    /**
     * 检测集合并按需重建
     *
     * @param collectionName 集合名称
     * @param migrationType  迁移类型标识
     */
    private void migrateCollectionIfNeeded(String collectionName, String migrationType) {
        try {
            if (!milvusOps.isAvailable()) {
                log.warn("Milvus 不可用，跳过集合迁移检查: {}", collectionName);
                return;
            }

            if (!isCollectionExists(collectionName)) {
                return;
            }

            boolean needsMigration = false;
            DescribeCollectionResp resp = milvusOps.getRawClient().describeCollection(
                    DescribeCollectionReq.builder()
                            .collectionName(collectionName)
                            .build());

            switch (migrationType) {
                case "autoID":
                    for (var field : resp.getCollectionSchema().getFieldSchemaList()) {
                        if (field.getIsPrimaryKey() && field.getAutoID()) {
                            needsMigration = true;
                            break;
                        }
                    }
                    break;

                case "nullable_nsc_id":
                    for (var field : resp.getCollectionSchema().getFieldSchemaList()) {
                        if ("nsc_id".equals(field.getName()) && !field.getIsNullable()) {
                            needsMigration = true;
                            break;
                        }
                    }
                    break;

                case "nullable_parent_id":
                    for (var field : resp.getCollectionSchema().getFieldSchemaList()) {
                        if ("parent_id".equals(field.getName()) && !field.getIsNullable()) {
                            needsMigration = true;
                            break;
                        }
                    }
                    break;

                case "score_index_schema_v2":
                    // 检查是否包含 detail 表结构字段
                    boolean hasDetailFields = false;
                    for (var field : resp.getCollectionSchema().getFieldSchemaList()) {
                        if ("index_class_id".equals(field.getName())) {
                            hasDetailFields = true;
                            break;
                        }
                    }
                    if (!hasDetailFields) {
                        needsMigration = true;
                    }
                    break;
            }

            if (needsMigration) {
                log.info("集合 {} 需要迁移 (type={})，重建集合...", collectionName, migrationType);
                milvusOps.dropCollection(collectionName);
                CreateCollectionReq req = getSchemaForCollection(collectionName);
                milvusOps.createCollection(collectionName, req.getCollectionSchema(), req.getIndexParams());
                log.info("集合 {} 迁移完成", collectionName);
            }

        } catch (Exception e) {
            log.error("迁移集合 {} 失败 (type={})", collectionName, migrationType, e);
        }
    }

    /**
     * 确保所有集合存在
     */
    private void ensureCollectionsExist() {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过集合初始化");
            return;
        }

        ensureCollection(NUTRITION_STANDARDS_COLLECTION);
        ensureCollection(INGREDIENT_NUTRIENTS_COLLECTION);
        ensureCollection(SCORE_INDEX_COLLECTION);
        ensureCollection(INGREDIENT_CLASSES_COLLECTION);
        ensureCollection(DISH_CLASSES_COLLECTION);
        ensureCollection(NUTRIENT_STANDARD_DETAILS_COLLECTION);

        if (regionalCuisineSyncHandler != null) {
            ensureCollection(RegionalCuisineSyncHandler.REGIONAL_CUISINE_DICTIONARY_COLLECTION);
        }
        if (ingredientSeasonalitySyncHandler != null) {
            ensureCollection(IngredientSeasonalitySyncHandler.INGREDIENT_SEASONALITY_COLLECTION);
        }
        if (schoolEthnicConfigHandler != null) {
            ensureCollection(SchoolEthnicConfigSyncHandler.SCHOOL_ETHNIC_CONFIG_COLLECTION);
        }
    }

    /**
     * 确保指定集合存在，不存在则创建
     *
     * @param collectionName 集合名称
     */
    private void ensureCollection(String collectionName) {
        try {
            if (!isCollectionExists(collectionName)) {
                CreateCollectionReq req = getSchemaForCollection(collectionName);
                milvusOps.createCollection(collectionName, req.getCollectionSchema(), req.getIndexParams());
                log.info("创建 Milvus 集合: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("确保集合存在失败: {}", collectionName, e);
        }
    }

    /**
     * 根据集合名称获取对应的 Schema
     */
    private CreateCollectionReq getSchemaForCollection(String collectionName) {
        switch (collectionName) {
            case NUTRITION_STANDARDS_COLLECTION:
                return nutritionStandardsSyncHandler.createSchema();
            case INGREDIENT_NUTRIENTS_COLLECTION:
                return ingredientNutrientsSyncHandler.createSchema();
            case SCORE_INDEX_COLLECTION:
                return scoreIndexSyncHandler.createSchema();
            case INGREDIENT_CLASSES_COLLECTION:
                return ingredientClassesSyncHandler.createSchema();
            case DISH_CLASSES_COLLECTION:
                return dishClassesSyncHandler.createSchema();
            case NUTRIENT_STANDARD_DETAILS_COLLECTION:
                return nutrientStandardDetailsSyncHandler.createSchema();
            case RegionalCuisineSyncHandler.REGIONAL_CUISINE_DICTIONARY_COLLECTION:
                if (regionalCuisineSyncHandler != null) {
                    return regionalCuisineSyncHandler.createSchema();
                }
            case IngredientSeasonalitySyncHandler.INGREDIENT_SEASONALITY_COLLECTION:
                if (ingredientSeasonalitySyncHandler != null) {
                    return ingredientSeasonalitySyncHandler.createSchema();
                }
            case SchoolEthnicConfigSyncHandler.SCHOOL_ETHNIC_CONFIG_COLLECTION:
                if (schoolEthnicConfigHandler != null) {
                    return schoolEthnicConfigHandler.createSchema();
                }
            default:
                return CreateCollectionReq.builder()
                        .collectionName(collectionName)
                        .description("未知集合")
                        .build();
        }
    }

    /**
     * 获取需要同步的集合列表
     *
     * @return 需要同步的集合名称列表（空集合视为需要同步）
     */
    private List<String> getCollectionsToSync() {
        List<String> collections = new ArrayList<>();
        String[] allCollections = {
                NUTRITION_STANDARDS_COLLECTION,
                INGREDIENT_NUTRIENTS_COLLECTION,
                SCORE_INDEX_COLLECTION,
                INGREDIENT_CLASSES_COLLECTION,
                DISH_CLASSES_COLLECTION,
                NUTRIENT_STANDARD_DETAILS_COLLECTION
        };

        for (String collection : allCollections) {
            long count = getCollectionCount(collection);
            if (count <= 0) {
                log.info("集合 {} 无数据 (count={})，需要同步", collection, count);
                collections.add(collection);
            } else {
                log.info("集合 {} 已有数据 (count={})，跳过同步", collection, count);
            }
        }

        if (regionalCuisineSyncHandler != null) {
            String extCollection = RegionalCuisineSyncHandler.REGIONAL_CUISINE_DICTIONARY_COLLECTION;
            long count = getCollectionCount(extCollection);
            if (count <= 0) {
                log.info("扩展集合 {} 无数据 (count={})，需要同步", extCollection, count);
                collections.add(extCollection);
            }
        }

        if (ingredientSeasonalitySyncHandler != null) {
            String extCollection = IngredientSeasonalitySyncHandler.INGREDIENT_SEASONALITY_COLLECTION;
            long count = getCollectionCount(extCollection);
            if (count <= 0) {
                log.info("扩展集合 {} 无数据 (count={})，需要同步", extCollection, count);
                collections.add(extCollection);
            }
        }

        if (schoolEthnicConfigHandler != null) {
            String extCollection = SchoolEthnicConfigSyncHandler.SCHOOL_ETHNIC_CONFIG_COLLECTION;
            long count = getCollectionCount(extCollection);
            if (count <= 0) {
                log.info("扩展集合 {} 无数据 (count={})，需要同步", extCollection, count);
                collections.add(extCollection);
            }
        }

        return collections;
    }

    /**
     * 判断是否需要同步
     *
     * @return true 表示存在空集合需要同步，false 表示所有集合已有数据
     */
    private boolean needSync() {
        return !getCollectionsToSync().isEmpty();
    }

    /**
     * 异步执行全量同步
     */
    @Async
    public void performSyncAsync() {
        syncAll();
    }

    /**
     * 同步所有静态数据（按需增量同步：只同步空集合）
     */
    public void syncAll() {
        List<String> toSync = getCollectionsToSync();
        if (toSync.isEmpty()) {
            log.info("所有集合已有数据，跳过同步");
            return;
        }

        log.info("========== 开始增量同步，需同步 {} 个集合: {} ==========", toSync.size(), toSync);
        long startTime = System.currentTimeMillis();

        // 1. 同步营养标准
        if (toSync.contains(NUTRITION_STANDARDS_COLLECTION)) {
            try {
                nutritionStandardsSyncHandler.sync();
                log.info("营养标准同步完成");
            } catch (Exception e) {
                log.error("营养标准同步失败", e);
            }
        }

        // 2. 同步食材营养
        if (toSync.contains(INGREDIENT_NUTRIENTS_COLLECTION)) {
            try {
                ingredientNutrientsSyncHandler.sync();
                log.info("食材营养同步完成");
            } catch (Exception e) {
                log.error("食材营养同步失败", e);
            }
        }

        // 3. 同步评分指标
        if (toSync.contains(SCORE_INDEX_COLLECTION)) {
            try {
                scoreIndexSyncHandler.sync();
                log.info("评分指标同步完成");
            } catch (Exception e) {
                log.error("评分指标同步失败", e);
            }
        }

        // 4. 同步食材分类
        if (toSync.contains(INGREDIENT_CLASSES_COLLECTION)) {
            try {
                ingredientClassesSyncHandler.sync();
                log.info("食材分类同步完成");
            } catch (Exception e) {
                log.error("食材分类同步失败", e);
            }
        }

        // 5. 同步菜品分类
        if (toSync.contains(DISH_CLASSES_COLLECTION)) {
            try {
                dishClassesSyncHandler.sync();
                log.info("菜品分类同步完成");
            } catch (Exception e) {
                log.error("菜品分类同步失败", e);
            }
        }

        // 6. 同步营养素详情
        if (toSync.contains(NUTRIENT_STANDARD_DETAILS_COLLECTION)) {
            try {
                nutrientStandardDetailsSyncHandler.sync();
                log.info("营养素详情同步完成");
            } catch (Exception e) {
                log.error("营养素详情同步失败", e);
            }
        }

        // 7. 扩展同步：区域菜系字典
        if (regionalCuisineSyncHandler != null
                && toSync.contains(RegionalCuisineSyncHandler.REGIONAL_CUISINE_DICTIONARY_COLLECTION)) {
            try {
                int count = regionalCuisineSyncHandler.sync();
                log.info("区域菜系字典同步完成，新增 {} 条", count);
            } catch (Exception e) {
                log.error("区域菜系字典同步失败", e);
            }
        }

        // 8. 扩展同步：食材季节性属性
        if (ingredientSeasonalitySyncHandler != null
                && toSync.contains(IngredientSeasonalitySyncHandler.INGREDIENT_SEASONALITY_COLLECTION)) {
            try {
                int count = ingredientSeasonalitySyncHandler.sync();
                log.info("食材季节性属性同步完成，新增 {} 条", count);
            } catch (Exception e) {
                log.error("食材季节性属性同步失败", e);
            }
        }

        // 9. 扩展同步：学校民族配置
        if (schoolEthnicConfigHandler != null
                && toSync.contains(SchoolEthnicConfigSyncHandler.SCHOOL_ETHNIC_CONFIG_COLLECTION)) {
            try {
                int count = schoolEthnicConfigHandler.sync();
                log.info("学校民族配置同步完成，新增 {} 条", count);
            } catch (Exception e) {
                log.error("学校民族配置同步失败", e);
            }
        }

        // 记录同步完成时间
        saveSyncState();

        long duration = System.currentTimeMillis() - startTime;
        log.info("========== 增量同步完成，耗时 {} ms ==========", duration);
    }

    // ==================== 同步状态管理 ====================

    /**
     * 获取同步状态文件路径
     *
     * @return 同步状态文件路径
     */
    private String getSyncStateFilePath() {
        return System.getProperty("user.dir") + File.separator + "sync-state.properties";
    }

    /**
     * 保存同步状态到本地文件（记录各集合最后同步时间戳）
     */
    private void saveSyncState() {
        try {
            Properties props = new Properties();
            File file = new File(getSyncStateFilePath());
            if (file.exists()) {
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                }
            }
            String timestamp = java.time.LocalDateTime.now().toString();
            String[] allCollections = {
                    NUTRITION_STANDARDS_COLLECTION, INGREDIENT_NUTRIENTS_COLLECTION,
                    SCORE_INDEX_COLLECTION, INGREDIENT_CLASSES_COLLECTION,
                    DISH_CLASSES_COLLECTION, NUTRIENT_STANDARD_DETAILS_COLLECTION
            };
            for (String collection : allCollections) {
                props.setProperty(collection, timestamp);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "Last sync timestamps");
            }
            log.info("同步状态已保存: {}", timestamp);
        } catch (Exception e) {
            log.warn("保存同步状态失败", e);
        }
    }
}
