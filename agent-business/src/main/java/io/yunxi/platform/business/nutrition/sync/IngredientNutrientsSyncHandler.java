package io.yunxi.platform.business.nutrition.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.SyncHandler;
import io.yunxi.platform.framework.sync.McpQueryService;
import io.yunxi.platform.framework.sync.MilvusCollectionService;
import io.yunxi.platform.framework.sync.EmbeddingBatchService;
import io.yunxi.platform.shared.util.TextParserUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 食材营养成分同步处理器
 *
 * <p>
 * 负责 ingredient_nutrients 集合的数据解析、向量化、插入和增量同步。
 * 从 StaticDataSyncService 中提取，实现 {@link SyncHandler} 接口。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class IngredientNutrientsSyncHandler implements SyncHandler {

    /** Milvus 集合名称 */
    private static final String COLLECTION_NAME = "ingredient_nutrients";

    /** MCP 数据库查询服务 */
    private final McpQueryService mcpQueryService;
    /** Milvus 集合操作服务 */
    private final MilvusCollectionService milvusCollectionService;
    /** 批量嵌入服务 */
    private final EmbeddingBatchService embeddingBatchService;
    /** 嵌入向量服务 */
    private final EmbeddingService embeddingService;
    /** JSON 对象映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Gson 序列化工具 */
    private final Gson gson = new GsonBuilder().create();

    /** MCP 数据库主机地址 */
    @Value("${static-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${static-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 批量同步的批次大小 */
    @Value("${static-sync.batch-size:100}")
    private int batchSize;

    /**
     * 构造函数，注入依赖服务
     *
     * @param mcpQueryService          MCP 数据库查询服务
     * @param milvusCollectionService  Milvus 集合操作服务
     * @param embeddingBatchService    批量嵌入服务
     * @param embeddingService         嵌入向量服务（可选注入）
     */
    public IngredientNutrientsSyncHandler(
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService,
            @Autowired(required = false) EmbeddingService embeddingService) {
        this.mcpQueryService = mcpQueryService;
        this.milvusCollectionService = milvusCollectionService;
        this.embeddingBatchService = embeddingBatchService;
        this.embeddingService = embeddingService;
    }

    /**
     * 获取 Milvus 集合名称
     *
     * @return 集合名称字符串
     */
    @Override
    public String getCollectionName() {
        return COLLECTION_NAME;
    }

    /**
     * 创建食材营养成分集合的 Schema 定义
     *
     * @return Milvus 集合创建请求对象
     */
    @Override
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("食材ID").build();

        CreateCollectionReq.FieldSchema nameField = CreateCollectionReq.FieldSchema.builder()
                .name("name").dataType(DataType.VarChar).maxLength(200)
                .description("食材名称").build();

        CreateCollectionReq.FieldSchema mainClassIdField = CreateCollectionReq.FieldSchema.builder()
                .name("main_class_id").dataType(DataType.Int64)
                .description("主分类ID").build();

        CreateCollectionReq.FieldSchema subClassIdField = CreateCollectionReq.FieldSchema.builder()
                .name("sub_class_id").dataType(DataType.Int64)
                .description("子分类ID").build();

        CreateCollectionReq.FieldSchema nutrientsField = CreateCollectionReq.FieldSchema.builder()
                .name("nutrients").dataType(DataType.VarChar).maxLength(5000)
                .description("营养成分详情").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(5000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, nameField, mainClassIdField, subClassIdField,
                        nutrientsField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("食材营养成分数据")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 执行食材营养成分增量同步
     *
     * <p>分两步查询：先获取食材基本信息，再获取营养成分数据，合并后向量化并 upsert 到 Milvus。</p>
     */
    @Override
    public void sync() {
        log.info("开始增量同步食材营养成分...");
        try {
            // 1. 获取食材基本信息
            String ingredientSql = "SELECT id, name, main_class_id, sub_class_id FROM food_ingredient WHERE deleted = 0";
            String ingredientResponse = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, ingredientSql);
            Map<Long, IngredientNutrient> ingredients = parseAllIngredientInfo(ingredientResponse);

            if (ingredients.isEmpty()) {
                log.info("食材数据为空，跳过");
                return;
            }

            // 2. 获取营养成分数据
            String nutrientSql = "SELECT fin.fi_id, n.name, fin.nutrient_content, n.unit " +
                    "FROM food_ingredient_nutrient fin " +
                    "LEFT JOIN nutrient n ON fin.nutrient_id = n.id " +
                    "WHERE fin.fi_id IS NOT NULL";
            String nutrientResponse = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, nutrientSql);
            Map<Long, Map<String, String>> allNutrients = parseAllNutrientData(nutrientResponse);

            // 3. 合并数据
            for (Map.Entry<Long, IngredientNutrient> entry : ingredients.entrySet()) {
                Long id = entry.getKey();
                if (allNutrients.containsKey(id)) {
                    entry.getValue().setNutrients(allNutrients.get(id));
                }
            }

            // 4. 批量 upsert
            List<IngredientNutrient> list = new ArrayList<>(ingredients.values());
            insertIngredientNutrients(list);
            log.info("食材营养成分增量同步完成，共 {} 条", list.size());

        } catch (Exception e) {
            log.error("食材营养成分增量同步失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析食材基本信息
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 食材ID到食材信息对象的映射
     */
    private Map<Long, IngredientNutrient> parseAllIngredientInfo(String response) {
        Map<Long, IngredientNutrient> result = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    IngredientNutrient nutrient = new IngredientNutrient();
                    nutrient.setId(TextParserUtil.parseLong(row, "id"));
                    nutrient.setName(TextParserUtil.parseString(row, "name"));
                    nutrient.setMainClassId(TextParserUtil.parseLong(row, "main_class_id"));
                    nutrient.setSubClassId(TextParserUtil.parseLong(row, "sub_class_id"));

                    if (nutrient.getId() != null) {
                        result.put(nutrient.getId(), nutrient);
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量解析食材信息失败", e);
        }
        return result;
    }

    /**
     * 解析食材营养成分数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 食材ID到营养成分映射的映射（营养素名称 -> 含量+单位）
     */
    private Map<Long, Map<String, String>> parseAllNutrientData(String response) {
        Map<Long, Map<String, String>> result = new HashMap<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Long fiId = TextParserUtil.parseLong(row, "fi_id");
                    String name = TextParserUtil.parseString(row, "name");
                    String contentVal = TextParserUtil.parseString(row, "nutrient_content");
                    String unit = TextParserUtil.parseString(row, "unit");

                    if (fiId != null && name != null && contentVal != null) {
                        result.computeIfAbsent(fiId, k -> new HashMap<>())
                                .put(name, contentVal + (unit != null ? unit : ""));
                    }
                }
            }
        } catch (Exception e) {
            log.error("批量解析营养成分失败", e);
        }
        return result;
    }

    /**
     * 将食材营养数据批量嵌入向量并插入 Milvus
     *
     * @param nutrients 食材营养信息列表
     */
    private void insertIngredientNutrients(List<IngredientNutrient> nutrients) {
        List<JsonObject> dataList = new ArrayList<>();

        int total = nutrients.size();
        log.info("开始生成 {} 条食材营养的embedding向量 (批量)...", total);

        List<String> texts = new ArrayList<>();
        for (IngredientNutrient nutrient : nutrients) {
            Map<String, String> nutrientsMap = nutrient.getNutrients();
            String nutrientStr = (nutrientsMap != null ? nutrientsMap.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("") : "");
            String text = String.format("食材 %s 营养成分 %s", nutrient.getName(), nutrientStr);
            texts.add(text);
        }

        List<List<Float>> allEmbeddings = embeddingBatchService.embedBatchWithRetry(texts);

        for (int i = 0; i < nutrients.size(); i++) {
            IngredientNutrient nutrient = nutrients.get(i);
            Map<String, String> nutrientsMap = nutrient.getNutrients();
            String nutrientStr = (nutrientsMap != null ? nutrientsMap.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("") : "");
            String text = texts.get(i);
            List<Float> embedding = allEmbeddings.get(i);

            JsonObject data = new JsonObject();
            data.addProperty("id", nutrient.getId());
            data.addProperty("name", nutrient.getName());
            data.addProperty("main_class_id", nutrient.getMainClassId());
            data.addProperty("sub_class_id", nutrient.getSubClassId());
            data.addProperty("nutrients", nutrientStr);
            data.addProperty("embedding_text", text);
            data.add("embedding", gson.toJsonTree(embedding));

            dataList.add(data);
        }

        log.info("embedding完成，开始插入 {} 条数据到 {}", dataList.size(), COLLECTION_NAME);
        milvusCollectionService.upsertBatch(COLLECTION_NAME, dataList, batchSize);
    }

    // ==================== 内部 DTO ====================

    /** 食材营养成分数据传输对象 */
    @Data
    public static class IngredientNutrient {
        /** 食材ID */
        private Long id;
        /** 食材名称 */
        private String name;
        /** 主分类ID */
        private Long mainClassId;
        /** 子分类ID */
        private Long subClassId;
        /** 营养成分映射（营养素名称 -> 含量+单位） */
        private Map<String, String> nutrients;
    }
}
