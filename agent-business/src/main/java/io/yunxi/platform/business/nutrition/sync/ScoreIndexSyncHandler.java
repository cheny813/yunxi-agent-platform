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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 评分指标同步处理器
 *
 * <p>
 * 负责 cook_book_score_index 集合的数据解析、向量化、插入和增量同步。
 * 从 StaticDataSyncService 中提取，实现 {@link SyncHandler} 接口。
 * 已清理3个废弃 parse 方法（parseScoreIndexData/parseScoreIndexClassData/parseScoreIndexDetailData）。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class ScoreIndexSyncHandler implements SyncHandler {

    /** Milvus 集合名称 */
    private static final String COLLECTION_NAME = "cook_book_score_index";

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
    public ScoreIndexSyncHandler(
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
     * 创建评分指标集合的 Schema 定义
     *
     * @return Milvus 集合创建请求对象
     */
    @Override
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("评分指标明细ID").build();

        CreateCollectionReq.FieldSchema indexClassIdField = CreateCollectionReq.FieldSchema.builder()
                .name("index_class_id").dataType(DataType.Int64)
                .description("所属指标类别ID").build();

        CreateCollectionReq.FieldSchema nsIdField = CreateCollectionReq.FieldSchema.builder()
                .name("ns_id").dataType(DataType.Int64)
                .description("营养标准ID").build();

        CreateCollectionReq.FieldSchema indexClassField = CreateCollectionReq.FieldSchema.builder()
                .name("index_class").dataType(DataType.VarChar).maxLength(64)
                .description("指标类别名称").build();

        CreateCollectionReq.FieldSchema dimensionField = CreateCollectionReq.FieldSchema.builder()
                .name("dimension").dataType(DataType.VarChar).maxLength(10)
                .description("维度(1周维度2日维度3餐维度)").build();

        CreateCollectionReq.FieldSchema indexField = CreateCollectionReq.FieldSchema.builder()
                .name("index_code").dataType(DataType.VarChar).maxLength(64).isNullable(true)
                .description("指标编码").build();

        CreateCollectionReq.FieldSchema indexNameField = CreateCollectionReq.FieldSchema.builder()
                .name("index_name").dataType(DataType.VarChar).maxLength(64).isNullable(true)
                .description("指标名称").build();

        CreateCollectionReq.FieldSchema classRatioField = CreateCollectionReq.FieldSchema.builder()
                .name("class_index_ratio").dataType(DataType.Double)
                .description("类别指标占比").build();

        CreateCollectionReq.FieldSchema detailRatioField = CreateCollectionReq.FieldSchema.builder()
                .name("detail_index_ratio").dataType(DataType.Double).isNullable(true)
                .description("明细指标占比").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(2000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, indexClassIdField, nsIdField, indexClassField,
                        dimensionField, indexField, indexNameField, classRatioField, detailRatioField,
                        textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("评分指标数据")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 执行评分指标增量同步
     *
     * <p>从 MCP 数据库查询评分指标明细及类别数据，解析后向量化并 upsert 到 Milvus。</p>
     */
    @Override
    public void sync() {
        log.info("开始增量同步评分索引...");
        try {
            String sql = "SELECT d.id, d.index_class_id, c.ns_id, c.index_class, c.dimension, " +
                    "d.`index` AS index_code, d.index_name, " +
                    "c.index_ratio AS class_index_ratio, d.index_ratio AS detail_index_ratio " +
                    "FROM cook_book_score_index_detail d " +
                    "LEFT JOIN cook_book_score_index_class c ON d.index_class_id = c.id";
            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql);
            List<Map<String, Object>> items = parseScoreIndexDetailWithClass(response);

            if (items.isEmpty()) {
                log.info("评分指标数据为空，跳过");
                return;
            }

            insertScoreIndexData(items);
            log.info("评分索引增量同步完成，共 {} 条", items.size());

        } catch (Exception e) {
            log.error("评分索引增量同步失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析 MCP 查询返回的评分指标明细（含类别）数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 解析后的评分指标数据列表
     */
    private List<Map<String, Object>> parseScoreIndexDetailWithClass(String response) {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    Map<String, Object> item = new HashMap<>();

                    Long id = TextParserUtil.parseLong(row, "id");
                    Long indexClassId = TextParserUtil.parseLong(row, "index_class_id");
                    Long nsId = TextParserUtil.parseLong(row, "ns_id");
                    String indexClass = TextParserUtil.parseString(row, "index_class");
                    String dimension = TextParserUtil.parseString(row, "dimension");
                    String indexCode = TextParserUtil.parseString(row, "index_code");
                    String indexName = TextParserUtil.parseString(row, "index_name");
                    String classRatio = TextParserUtil.parseString(row, "class_index_ratio");
                    String detailRatio = TextParserUtil.parseString(row, "detail_index_ratio");

                    if (id != null) {
                        item.put("id", id);
                        item.put("index_class_id", indexClassId != null ? indexClassId : 0L);
                        item.put("ns_id", nsId != null ? nsId : 0L);
                        item.put("index_class", indexClass != null ? indexClass : "");
                        item.put("dimension", dimension != null ? dimension : "");
                        item.put("index_code", indexCode);
                        item.put("index_name", indexName);
                        item.put("class_index_ratio", classRatio != null ? Double.parseDouble(classRatio) : 0.0);
                        item.put("detail_index_ratio", detailRatio != null ? Double.parseDouble(detailRatio) : null);
                        result.add(item);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析评分指标明细（含类别）失败", e);
        }
        return result;
    }

    /**
     * 将评分指标数据批量嵌入向量并插入 Milvus
     *
     * @param items 评分指标数据列表
     */
    private void insertScoreIndexData(List<Map<String, Object>> items) {
        if (items.isEmpty())
            return;

        List<JsonObject> dataList = new ArrayList<>();

        List<String> texts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String indexClass = item.get("index_class") != null ? item.get("index_class").toString() : "";
            String indexName = item.get("index_name") != null ? item.get("index_name").toString() : "";
            String indexCode = item.get("index_code") != null ? item.get("index_code").toString() : "";
            String dim = item.get("dimension") != null ? item.get("dimension").toString() : "";
            String text = String.format("评分指标类别 %s 维度 %s 指标 %s %s", indexClass, dim, indexCode, indexName);
            texts.add(text);
        }
        List<List<Float>> allEmbeddings = embeddingBatchService.embedBatchWithRetry(texts);

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            List<Float> embedding = allEmbeddings.get(i);

            JsonObject data = new JsonObject();
            data.addProperty("id", ((Number) item.get("id")).longValue());
            data.addProperty("index_class_id",
                    item.get("index_class_id") != null ? ((Number) item.get("index_class_id")).longValue() : 0L);
            data.addProperty("ns_id", item.get("ns_id") != null ? ((Number) item.get("ns_id")).longValue() : 0L);
            data.addProperty("index_class", item.get("index_class") != null ? item.get("index_class").toString() : "");
            data.addProperty("dimension", item.get("dimension") != null ? item.get("dimension").toString() : "");
            if (item.get("index_code") != null) {
                data.addProperty("index_code", item.get("index_code").toString());
            }
            if (item.get("index_name") != null) {
                data.addProperty("index_name", item.get("index_name").toString());
            }
            data.addProperty("class_index_ratio",
                    item.get("class_index_ratio") != null ? ((Number) item.get("class_index_ratio")).doubleValue()
                            : 0.0);
            if (item.get("detail_index_ratio") != null) {
                data.addProperty("detail_index_ratio", ((Number) item.get("detail_index_ratio")).doubleValue());
            }
            data.addProperty("embedding_text", texts.get(i));
            data.add("embedding", gson.toJsonTree(embedding));

            dataList.add(data);
        }

        milvusCollectionService.upsertBatch(COLLECTION_NAME, dataList, batchSize);
    }
}
