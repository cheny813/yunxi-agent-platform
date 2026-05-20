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
 * 菜谱分类同步处理器
 *
 * <p>
 * 负责 dish_classes 集合的数据解析、向量化、插入和增量同步。
 * 从 StaticDataSyncService 中提取，实现 {@link SyncHandler} 接口。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class DishClassesSyncHandler implements SyncHandler {

    /** Milvus 集合名称 */
    private static final String COLLECTION_NAME = "dish_classes";

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
    public DishClassesSyncHandler(
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
     * 创建菜谱分类集合的 Schema 定义
     *
     * @return Milvus 集合创建请求对象
     */
    @Override
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("分类ID").build();

        CreateCollectionReq.FieldSchema nameField = CreateCollectionReq.FieldSchema.builder()
                .name("name").dataType(DataType.VarChar).maxLength(100)
                .description("分类名称").build();

        CreateCollectionReq.FieldSchema parentIdField = CreateCollectionReq.FieldSchema.builder()
                .name("parent_id").dataType(DataType.Int64).isNullable(true)
                .description("父分类ID").build();

        CreateCollectionReq.FieldSchema levelField = CreateCollectionReq.FieldSchema.builder()
                .name("level").dataType(DataType.Int32)
                .description("分类层级").build();

        CreateCollectionReq.FieldSchema sortOrderField = CreateCollectionReq.FieldSchema.builder()
                .name("sort_order").dataType(DataType.Int32)
                .description("排序").build();

        CreateCollectionReq.FieldSchema descriptionField = CreateCollectionReq.FieldSchema.builder()
                .name("description").dataType(DataType.VarChar).maxLength(500)
                .description("分类说明").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(500)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("分类向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, nameField, parentIdField, levelField,
                        sortOrderField, descriptionField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("菜谱分类数据")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 执行菜谱分类增量同步
     *
     * <p>从 MCP 数据库查询菜谱分类数据，解析后向量化并 upsert 到 Milvus。</p>
     */
    @Override
    public void sync() {
        log.info("开始增量同步菜谱分类...");
        try {
            String sql = "SELECT id, name, pid AS parent_id " +
                    "FROM dish_class WHERE deleted = 0";
            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql);

            List<Map<String, Object>> items = parseClassData(response);

            if (items.isEmpty()) {
                log.info("菜谱分类数据为空，跳过");
                return;
            }

            insertClassData(items);
            log.info("菜谱分类增量同步完成，共 {} 条", items.size());
        } catch (Exception e) {
            log.error("菜谱分类增量同步失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析 MCP 查询返回的分类数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 解析后的分类数据列表，每个元素为一个分类的字段映射
     */
    private List<Map<String, Object>> parseClassData(String response) {
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
                    String name = TextParserUtil.parseString(row, "name");
                    Long parentId = TextParserUtil.parseLong(row, "parent_id");
                    Integer level = TextParserUtil.parseInt(row, "level");
                    Integer sortOrder = TextParserUtil.parseInt(row, "sort_order");
                    String desc = TextParserUtil.parseString(row, "description");

                    if (id != null && name != null) {
                        item.put("id", id);
                        item.put("name", name);
                        item.put("parent_id", parentId);
                        item.put("level", level != null ? level : 1);
                        item.put("sort_order", sortOrder != null ? sortOrder : 0);
                        item.put("description", desc != null ? desc : "");
                        result.add(item);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析分类数据失败", e);
        }
        return result;
    }

    /**
     * 将分类数据批量嵌入向量并插入 Milvus
     *
     * @param items 分类数据列表，每个元素包含 id、name、parent_id 等字段
     */
    private void insertClassData(List<Map<String, Object>> items) {
        List<JsonObject> dataList = new ArrayList<>();

        List<String> texts = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String className = (String) item.get("name");
            String text = String.format("菜谱分类 %s", className);
            texts.add(text);
        }

        List<List<Float>> allEmbeddings = embeddingBatchService.embedBatchWithRetry(texts);

        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            List<Float> embedding = allEmbeddings.get(i);
            String className = (String) item.get("name");

            JsonObject data = new JsonObject();
            data.addProperty("id", ((Number) item.get("id")).longValue());
            data.addProperty("name", className);
            data.addProperty("parent_id",
                    item.get("parent_id") != null ? ((Number) item.get("parent_id")).longValue() : null);
            data.addProperty("level", (Integer) item.get("level"));
            data.addProperty("sort_order", (Integer) item.get("sort_order"));
            data.addProperty("description", (String) item.get("description"));
            data.addProperty("embedding_text", texts.get(i));
            data.add("embedding", gson.toJsonTree(embedding));
            dataList.add(data);
        }

        milvusCollectionService.upsertBatch(COLLECTION_NAME, dataList, batchSize);
    }
}
