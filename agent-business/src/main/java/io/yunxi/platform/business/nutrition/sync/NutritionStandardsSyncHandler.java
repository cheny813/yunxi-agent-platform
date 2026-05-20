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
 * 营养标准同步处理器
 *
 * <p>
 * 负责 nutrition_standards 集合的数据解析、向量化、插入和增量同步。
 * 从 StaticDataSyncService 中提取，实现 {@link SyncHandler} 接口。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class NutritionStandardsSyncHandler implements SyncHandler {

    /** Milvus 集合名称 */
    private static final String COLLECTION_NAME = "nutrition_standards";

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
    public NutritionStandardsSyncHandler(
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
     * 创建营养标准集合的 Schema 定义
     *
     * @return Milvus 集合创建请求对象
     */
    @Override
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("营养标准ID").build();

        CreateCollectionReq.FieldSchema codeField = CreateCollectionReq.FieldSchema.builder()
                .name("code").dataType(DataType.VarChar).maxLength(50)
                .description("标准编码").build();

        CreateCollectionReq.FieldSchema nameField = CreateCollectionReq.FieldSchema.builder()
                .name("name").dataType(DataType.VarChar).maxLength(200)
                .description("标准名称").build();

        CreateCollectionReq.FieldSchema ageGroupField = CreateCollectionReq.FieldSchema.builder()
                .name("age_group").dataType(DataType.VarChar).maxLength(50)
                .description("适用年龄段").build();

        CreateCollectionReq.FieldSchema regionField = CreateCollectionReq.FieldSchema.builder()
                .name("region").dataType(DataType.VarChar).maxLength(100)
                .description("地区标准").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(2000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, codeField, nameField, ageGroupField, regionField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("营养标准数据")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 执行营养标准增量同步
     *
     * <p>从 MCP 数据库查询营养标准数据，解析后向量化并 upsert 到 Milvus。</p>
     */
    @Override
    public void sync() {
        log.info("开始增量同步营养标准...");
        try {
            String sql = "SELECT id, code, name, age_group, region FROM nutrient_standard WHERE deleted = 0";
            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql);
            List<NutritionStandard> standards = parseNutritionStandards(response);

            if (standards.isEmpty()) {
                log.info("营养标准数据为空，跳过");
                return;
            }

            insertNutritionStandards(standards);
            log.info("营养标准增量同步完成，共 {} 条", standards.size());
        } catch (Exception e) {
            log.error("营养标准增量同步失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析 MCP 查询返回的营养标准数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 营养标准列表
     */
    private List<NutritionStandard> parseNutritionStandards(String response) {
        List<NutritionStandard> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    NutritionStandard standard = new NutritionStandard();
                    standard.setId(TextParserUtil.parseLong(row, "id"));
                    standard.setCode(TextParserUtil.parseString(row, "code"));
                    standard.setName(TextParserUtil.parseString(row, "name"));
                    standard.setAgeRange(TextParserUtil.parseString(row, "age_group"));
                    standard.setStandardName(TextParserUtil.parseString(row, "region"));

                    if (standard.getId() != null) {
                        result.add(standard);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析营养标准失败", e);
        }
        return result;
    }

    /**
     * 将营养标准数据批量嵌入向量并插入 Milvus
     *
     * @param standards 营养标准列表
     */
    private void insertNutritionStandards(List<NutritionStandard> standards) {
        List<JsonObject> dataList = new ArrayList<>();

        int total = standards.size();
        log.info("开始生成 {} 条营养标准的embedding向量...", total);

        List<String> texts = new ArrayList<>();
        for (NutritionStandard standard : standards) {
            String text = String.format("营养标准 %s 年龄段 %s 地区 %s",
                    standard.getName(), standard.getAgeRange(), standard.getStandardName());
            texts.add(text);
        }
        List<List<Float>> allEmbeddings = embeddingBatchService.embedBatchWithRetry(texts);

        for (int i = 0; i < standards.size(); i++) {
            NutritionStandard standard = standards.get(i);
            String text = texts.get(i);
            List<Float> embedding = allEmbeddings.get(i);

            JsonObject data = new JsonObject();
            data.addProperty("id", standard.getId());
            data.addProperty("code", standard.getCode());
            data.addProperty("name", standard.getName());
            data.addProperty("age_group", standard.getAgeRange());
            data.addProperty("region", standard.getStandardName());
            data.addProperty("embedding_text", text);
            data.add("embedding", gson.toJsonTree(embedding));

            dataList.add(data);
        }

        log.info("embedding完成，开始插入 {} 条数据到 {}", dataList.size(), COLLECTION_NAME);
        milvusCollectionService.upsertBatch(COLLECTION_NAME, dataList, batchSize);
    }

    // ==================== 内部 DTO ====================

    /** 营养标准数据传输对象 */
    @Data
    public static class NutritionStandard {
        /** 营养标准ID */
        private Long id;
        /** 标准编码 */
        private String code;
        /** 标准名称 */
        private String name;
        /** 适用年龄段 */
        private String ageRange;
        /** 地区标准名称 */
        private String standardName;
    }
}
