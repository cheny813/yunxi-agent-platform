package io.yunxi.platform.business.nutrition.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 学校民族配置同步处理器
 *
 * <p>
 * 将学校民族配置数据同步到 Milvus 集合 {@code school_ethnic_config}，
 * 为配餐推荐提供民族饮食约束维度。数据来源为营养数据库的学校表，
 * 结合内置的民族→食材限制映射。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.school-ethnic-sync.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.school-ethnic-sync.enabled", havingValue = "true")
public class SchoolEthnicConfigSyncHandler extends BaseSyncService {

    /** Milvus 集合名称 */
    public static final String SCHOOL_ETHNIC_CONFIG_COLLECTION = "school_ethnic_config";

    /** Gson 序列化工具 */
    private final Gson gson = new GsonBuilder().create();

    /** MCP 数据库主机地址 */
    @Value("${static-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${static-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /**
     * 构造函数
     *
     * @param milvusOps       Milvus 操作门面
     * @param embeddingService 嵌入服务
     */
    public SchoolEthnicConfigSyncHandler(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        super(milvusOps, embeddingService, mcpQueryService, milvusCollectionService, embeddingBatchService);
    }

    // ==================== 民族→食材限制映射 ====================

    /** 民族饮食约束映射 */
    private static final Map<String, EthnicDietaryConstraint> ETHNIC_CONSTRAINTS = new LinkedHashMap<>();

    static {
        // 回族 — 禁食猪肉及相关制品
        ETHNIC_CONSTRAINTS.put("回族", new EthnicDietaryConstraint(
                "回族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血", "猪皮", "五花肉", "排骨(猪)", "火腿(猪肉)", "香肠(猪肉)", "培根", "腊肉(猪肉)"),
                Arrays.asList("清真菜", "牛羊肉菜", "鸡鸭菜", "鱼虾菜", "素食菜")
        ));
        // 维吾尔族 — 禁食猪肉及相关制品，饮食习惯与回族类似
        ETHNIC_CONSTRAINTS.put("维吾尔族", new EthnicDietaryConstraint(
                "维吾尔族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血", "猪皮", "五花肉", "排骨(猪)", "火腿(猪肉)", "香肠(猪肉)", "培根", "腊肉(猪肉)", "含酒精食材"),
                Arrays.asList("清真菜", "新疆菜", "牛羊肉菜", "面食")
        ));
        // 哈萨克族 — 禁食猪肉
        ETHNIC_CONSTRAINTS.put("哈萨克族", new EthnicDietaryConstraint(
                "哈萨克族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血", "猪皮", "五花肉", "火腿(猪肉)"),
                Arrays.asList("清真菜", "牛羊肉菜", "奶制品菜", "面食")
        ));
        // 塔吉克族 — 禁食猪肉
        ETHNIC_CONSTRAINTS.put("塔吉克族", new EthnicDietaryConstraint(
                "塔吉克族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血"),
                Arrays.asList("清真菜", "牛羊肉菜")
        ));
        // 柯尔克孜族 — 禁食猪肉
        ETHNIC_CONSTRAINTS.put("柯尔克孜族", new EthnicDietaryConstraint(
                "柯尔克孜族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血"),
                Arrays.asList("清真菜", "牛羊肉菜")
        ));
        // 乌孜别克族 — 禁食猪肉
        ETHNIC_CONSTRAINTS.put("乌孜别克族", new EthnicDietaryConstraint(
                "乌孜别克族",
                Arrays.asList("猪肉", "猪油", "猪骨", "猪血"),
                Arrays.asList("清真菜", "牛羊肉菜", "中亚菜")
        ));
        // 藏族 — 部分地区不吃鱼和水产
        ETHNIC_CONSTRAINTS.put("藏族", new EthnicDietaryConstraint(
                "藏族",
                Arrays.asList("鱼", "虾", "蟹", "水产(部分地区禁忌)"),
                Arrays.asList("藏菜", "牛羊肉菜", "奶制品菜")
        ));
        // 傣族 — 部分地区不吃牛肉
        ETHNIC_CONSTRAINTS.put("傣族", new EthnicDietaryConstraint(
                "傣族",
                Arrays.asList("牛肉(部分地区禁忌)"),
                Arrays.asList("傣菜", "酸辣菜", "竹笋菜", "热带水果入菜")
        ));
    }

    // ==================== Schema 创建 ====================

    /**
     * 创建学校民族配置集合的 Schema
     *
     * @return Milvus 集合创建请求
     */
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("主键ID").build();

        CreateCollectionReq.FieldSchema schoolIdField = CreateCollectionReq.FieldSchema.builder()
                .name("school_id").dataType(DataType.Int64)
                .description("学校ID").build();

        CreateCollectionReq.FieldSchema schoolNameField = CreateCollectionReq.FieldSchema.builder()
                .name("school_name").dataType(DataType.VarChar).maxLength(200)
                .description("学校名称").build();

        CreateCollectionReq.FieldSchema ethnicTypesField = CreateCollectionReq.FieldSchema.builder()
                .name("ethnic_types").dataType(DataType.VarChar).maxLength(200)
                .description("民族类型: 汉族/回族/维吾尔族等(逗号分隔)").build();

        CreateCollectionReq.FieldSchema restrictedIngredientsField = CreateCollectionReq.FieldSchema.builder()
                .name("restricted_ingredients").dataType(DataType.VarChar).maxLength(2000)
                .description("受限食材(逗号分隔)").build();

        CreateCollectionReq.FieldSchema allowedCuisinesField = CreateCollectionReq.FieldSchema.builder()
                .name("allowed_cuisines").dataType(DataType.VarChar).maxLength(500)
                .description("允许菜系(逗号分隔)").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(3000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, schoolIdField, schoolNameField,
                        ethnicTypesField, restrictedIngredientsField, allowedCuisinesField,
                        textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(SCHOOL_ETHNIC_CONFIG_COLLECTION)
                .description("学校民族配置 - 民族饮食约束与受限食材")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    // ==================== 同步逻辑 ====================

    /**
     * 执行学校民族配置同步
     *
     * <p>
     * 从营养数据库查询有民族配置的学校，结合民族→食材限制映射，
     * 批量嵌入并 upsert 到 Milvus。无民族配置的学校跳过。
     * </p>
     *
     * @return 同步记录数
     */
    public int sync() {
        log.info("开始同步学校民族配置到 Milvus 集合: {}", SCHOOL_ETHNIC_CONFIG_COLLECTION);

        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过学校民族配置同步");
            return 0;
        }

        try {
            // 确保集合存在
            if (!isCollectionExists(SCHOOL_ETHNIC_CONFIG_COLLECTION)) {
                CreateCollectionReq req = createSchema();
                milvusOps.createCollection(SCHOOL_ETHNIC_CONFIG_COLLECTION, req.getCollectionSchema(), req.getIndexParams());
                log.info("创建 Milvus 集合: {}", SCHOOL_ETHNIC_CONFIG_COLLECTION);
            }

            // 从营养数据库查询学校列表（含民族信息）
            String sql = "SELECT id, name, nation FROM school WHERE deleted = 0 AND nation IS NOT NULL AND nation != ''";
            String result = callMcpDatabase(mcpDbHost, mcpDbPort, sql);

            // 解析学校数据
            List<SchoolRecord> schools = parseSchools(result);
            log.info("从营养数据库获取 {} 所有学校记录，开始匹配民族配置", schools.size());

            // 构建数据：匹配民族约束
            List<JsonObject> dataList = new ArrayList<>();
            List<String> embeddingTexts = new ArrayList<>();
            long idCounter = 1;

            for (SchoolRecord school : schools) {
                // 解析学校民族类型
                List<String> ethnicTypes = parseEthnicTypes(school.getNation());

                // 收集所有受限食材和允许菜系
                Set<String> allRestricted = new LinkedHashSet<>();
                Set<String> allAllowed = new LinkedHashSet<>();
                for (String ethnic : ethnicTypes) {
                    EthnicDietaryConstraint constraint = ETHNIC_CONSTRAINTS.get(ethnic);
                    if (constraint != null) {
                        allRestricted.addAll(constraint.getRestrictedIngredients());
                        allAllowed.addAll(constraint.getAllowedCuisines());
                    }
                }

                // 无约束的学校跳过（纯汉族学校无需记录）
                if (allRestricted.isEmpty() && allAllowed.isEmpty()) {
                    continue;
                }

                JsonObject data = new JsonObject();
                data.addProperty("id", idCounter++);
                data.addProperty("school_id", school.getId());
                data.addProperty("school_name", school.getName());
                data.addProperty("ethnic_types", String.join(",", ethnicTypes));
                data.addProperty("restricted_ingredients", String.join(",", allRestricted));
                data.addProperty("allowed_cuisines", String.join(",", allAllowed));

                String embeddingText = String.format("学校: %s 民族: %s 禁忌食材: %s 推荐菜系: %s",
                        school.getName(), String.join(",", ethnicTypes),
                        String.join(",", allRestricted), String.join(",", allAllowed));
                data.addProperty("embedding_text", embeddingText);
                embeddingTexts.add(embeddingText);

                dataList.add(data);
            }

            if (dataList.isEmpty()) {
                log.info("无民族配置的学校数据需要同步");
                return 0;
            }

            // 批量嵌入
            List<List<Float>> embeddings = embedBatchWithRetry(embeddingTexts);
            for (int i = 0; i < dataList.size(); i++) {
                dataList.get(i).add("embedding", gson.toJsonTree(embeddings.get(i)));
            }

            // 批量 upsert
            upsertBatch(SCHOOL_ETHNIC_CONFIG_COLLECTION, dataList, 100);

            log.info("学校民族配置同步完成，共 {} 条记录", dataList.size());
            return dataList.size();
        } catch (Exception e) {
            log.error("学校民族配置同步失败", e);
            return 0;
        }
    }

    // ==================== 搜索方法 ====================

    /**
     * 查询学校民族约束
     *
     * @param schoolId 学校ID
     * @return 民族约束文本，无约束返回空字符串
     */
    public String searchEthnicConfig(Long schoolId) {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，无法查询学校民族配置");
            return "";
        }
        try {
            String searchText = "学校ID " + schoolId + " 民族配置 饮食禁忌";
            List<Float> queryEmbedding = embeddingService.embedBatch(Collections.singletonList(searchText)).get(0);

            List<SearchResp.SearchResult> searchResults = milvusOps.search(
                    SCHOOL_ETHNIC_CONFIG_COLLECTION,
                    queryEmbedding,
                    3,
                    Arrays.asList("school_id", "school_name", "ethnic_types", "restricted_ingredients", "allowed_cuisines"),
                    String.format("school_id == %d", schoolId));

            StringBuilder sb = new StringBuilder();
            for (SearchResp.SearchResult result : searchResults) {
                sb.append(String.format("【%s 民族饮食约束】民族: %s, 禁忌食材: %s, 推荐菜系: %s\n",
                        result.getEntity().get("school_name"),
                        result.getEntity().get("ethnic_types"),
                        result.getEntity().get("restricted_ingredients"),
                        result.getEntity().get("allowed_cuisines")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("查询学校民族配置失败: schoolId={}", schoolId, e);
            return "";
        }
    }

    /**
     * 获取民族的受限食材列表（本地查找）
     *
     * @param ethnicType 民族类型
     * @return 受限食材列表
     */
    public List<String> getRestrictedIngredients(String ethnicType) {
        EthnicDietaryConstraint constraint = ETHNIC_CONSTRAINTS.get(ethnicType);
        return constraint != null ? constraint.getRestrictedIngredients() : Collections.emptyList();
    }

    // ==================== 解析方法 ====================

    /**
     * 解析 MCP 查询返回的学校数据
     *
     * @param result MCP 查询返回的 JSON 字符串
     * @return 学校记录列表
     */
    private List<SchoolRecord> parseSchools(String result) {
        List<SchoolRecord> schools = new ArrayList<>();
        if (result == null || result.isEmpty()) {
            return schools;
        }

        Pattern rowPattern = Pattern.compile("Row \\d+: \\{([^}]+)\\}");
        Matcher matcher = rowPattern.matcher(result);

        while (matcher.find()) {
            String rowContent = matcher.group(1);
            Long id = TextParserUtil.parseLong(rowContent, "id");
            String name = TextParserUtil.parseString(rowContent, "name");
            String nation = TextParserUtil.parseString(rowContent, "nation");

            if (id != null && name != null) {
                schools.add(new SchoolRecord(id, name, nation != null ? nation : ""));
            }
        }

        return schools;
    }

    /**
     * 解析学校民族类型字符串
     *
     * <p>
     * 支持格式: "回族/汉族", "回族", "汉族,维吾尔族" 等
     * 过滤掉"汉族"（无约束），只保留有约束的民族类型
     * </p>
     */
    private List<String> parseEthnicTypes(String nationStr) {
        if (nationStr == null || nationStr.trim().isEmpty()) {
            return Collections.emptyList();
        }

        return Arrays.stream(nationStr.split("[/,，、]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !"汉族".equals(s))
                .filter(ETHNIC_CONSTRAINTS::containsKey)
                .collect(Collectors.toList());
    }

    // ==================== 内部数据模型 ====================

    /**
     * 学校记录（从 MCP 查询解析）
     */
    @Data
    private static class SchoolRecord {
        /** 学校ID */
        private final Long id;
        /** 学校名称 */
        private final String name;
        /** 民族信息字符串 */
        private final String nation;

        SchoolRecord(Long id, String name, String nation) {
            this.id = id;
            this.name = name;
            this.nation = nation;
        }
    }

    /**
     * 民族饮食约束
     */
    @Data
    private static class EthnicDietaryConstraint {
        /** 民族类型 */
        private final String ethnicType;
        /** 受限食材列表 */
        private final List<String> restrictedIngredients;
        /** 允许菜系列表 */
        private final List<String> allowedCuisines;

        EthnicDietaryConstraint(String ethnicType, List<String> restrictedIngredients, List<String> allowedCuisines) {
            this.ethnicType = ethnicType;
            this.restrictedIngredients = restrictedIngredients;
            this.allowedCuisines = allowedCuisines;
        }
    }
}
