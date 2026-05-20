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
 * 营养素详情同步处理器
 *
 * <p>
 * 负责 nutrient_standard_details 集合的数据解析、向量化、插入和增量同步。
 * 从 StaticDataSyncService 中提取，实现 {@link SyncHandler} 接口。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Component
public class NutrientStandardDetailsSyncHandler implements SyncHandler {

    /** Milvus 集合名称 */
    private static final String COLLECTION_NAME = "nutrient_standard_details";

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
    public NutrientStandardDetailsSyncHandler(
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
     * 创建营养素详情集合的 Schema 定义
     *
     * @return Milvus 集合创建请求对象
     */
    @Override
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("营养素详情ID").build();

        CreateCollectionReq.FieldSchema nsIdField = CreateCollectionReq.FieldSchema.builder()
                .name("ns_id").dataType(DataType.Int64)
                .description("营养标准ID").build();

        CreateCollectionReq.FieldSchema nsCodeField = CreateCollectionReq.FieldSchema.builder()
                .name("ns_code").dataType(DataType.VarChar).maxLength(50)
                .description("标准编码").build();

        CreateCollectionReq.FieldSchema nsNameField = CreateCollectionReq.FieldSchema.builder()
                .name("ns_name").dataType(DataType.VarChar).maxLength(200)
                .description("标准名称").build();

        CreateCollectionReq.FieldSchema ageGroupField = CreateCollectionReq.FieldSchema.builder()
                .name("age_group").dataType(DataType.VarChar).maxLength(50)
                .description("适用年龄段").build();

        CreateCollectionReq.FieldSchema regionField = CreateCollectionReq.FieldSchema.builder()
                .name("region").dataType(DataType.VarChar).maxLength(100)
                .description("地区标准").build();

        CreateCollectionReq.FieldSchema nscIdField = CreateCollectionReq.FieldSchema.builder()
                .name("nsc_id").dataType(DataType.Int64).isNullable(true)
                .description("就餐人群ID").build();

        CreateCollectionReq.FieldSchema nscNameField = CreateCollectionReq.FieldSchema.builder()
                .name("nsc_name").dataType(DataType.VarChar).maxLength(100).isNullable(true)
                .description("就餐人群名称").build();

        CreateCollectionReq.FieldSchema nutrientIdField = CreateCollectionReq.FieldSchema.builder()
                .name("nutrient_id").dataType(DataType.Int64)
                .description("营养素ID").build();

        CreateCollectionReq.FieldSchema nutrientNameField = CreateCollectionReq.FieldSchema.builder()
                .name("nutrient_name").dataType(DataType.VarChar).maxLength(100)
                .description("营养素名称").build();

        CreateCollectionReq.FieldSchema allRecommendField = CreateCollectionReq.FieldSchema.builder()
                .name("all_recommend_qty").dataType(DataType.Double)
                .description("推荐摄入量").build();

        CreateCollectionReq.FieldSchema suggestionsField = CreateCollectionReq.FieldSchema.builder()
                .name("suggestions_json").dataType(DataType.VarChar).maxLength(65535)
                .description("营养建议JSON").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(2000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        idField, nsIdField, nsCodeField, nsNameField, ageGroupField, regionField,
                        nscIdField, nscNameField, nutrientIdField, nutrientNameField,
                        allRecommendField, suggestionsField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("营养素详情数据")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    /**
     * 执行营养素详情增量同步
     *
     * <p>从 MCP 数据库查询营养素详情数据（关联营养标准、就餐人群、营养素），解析后向量化并 upsert 到 Milvus。</p>
     */
    @Override
    public void sync() {
        try {
            String sql = "SELECT " +
                    "dc.id as id, ns.id as ns_id, ns.code as ns_code, ns.name as ns_name, ns.age_group, ns.region, " +
                    "nsc.id as nsc_id, nsc.name as nsc_name, nsc.min_age, nsc.max_age, " +
                    "dc.n_id, n.name as nutrient_name, " +
                    "dc.all_recommend_qty, dc.boy_recommend_qty, dc.girl_recommend_qty, " +
                    "dc.boy_lower_limit, dc.boy_upper_limit, dc.girl_lower_limit, dc.girl_upper_limit, " +
                    "dc.intake_suitable_suggestion, dc.intake_low_suggestion, dc.intake_high_suggestion " +
                    "FROM nutrient_standard_dcn dc " +
                    "LEFT JOIN nutrient_standard ns ON dc.ns_id = ns.id " +
                    "LEFT JOIN nutritional_standard_dining_crowd nsc ON dc.nsc_id = nsc.id " +
                    "LEFT JOIN nutrient n ON dc.n_id = n.id " +
                    "WHERE ns.deleted = 0";
            String response = mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort, sql);
            List<NutrientStandardDetail> details = parseNutrientStandardDetails(response);

            if (details.isEmpty()) {
                log.info("营养素详情数据为空，跳过");
                return;
            }

            insertNutrientStandardDetails(details);
            log.info("营养素详情增量同步完成，共 {} 条", details.size());
        } catch (Exception e) {
            log.error("营养素详情增量同步失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 解析 MCP 查询返回的营养素详情数据
     *
     * @param response MCP 查询返回的 JSON 字符串
     * @return 营养素详情列表
     */
    private List<NutrientStandardDetail> parseNutrientStandardDetails(String response) {
        List<NutrientStandardDetail> result = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");

            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                Pattern rowPattern = Pattern.compile("Row \\d+: \\{(.+?)\\}");
                Matcher matcher = rowPattern.matcher(text);

                while (matcher.find()) {
                    String row = matcher.group(1);
                    NutrientStandardDetail detail = new NutrientStandardDetail();

                    detail.setId(TextParserUtil.parseLong(row, "id"));
                    detail.setNsId(TextParserUtil.parseLong(row, "ns_id"));
                    detail.setNsCode(TextParserUtil.parseString(row, "ns_code"));
                    detail.setNsName(TextParserUtil.parseString(row, "ns_name"));
                    detail.setAgeGroup(TextParserUtil.parseString(row, "age_group"));
                    detail.setRegion(TextParserUtil.parseString(row, "region"));
                    detail.setNscId(TextParserUtil.parseLong(row, "nsc_id"));
                    detail.setNscName(TextParserUtil.parseString(row, "nsc_name"));
                    detail.setMinAge(TextParserUtil.parseInt(row, "min_age"));
                    detail.setMaxAge(TextParserUtil.parseInt(row, "max_age"));
                    detail.setNutrientId(TextParserUtil.parseLong(row, "n_id"));
                    detail.setNutrientName(TextParserUtil.parseString(row, "nutrient_name"));
                    detail.setAllRecommendQty(TextParserUtil.parseDouble(row, "all_recommend_qty"));
                    detail.setBoyRecommendQty(TextParserUtil.parseDouble(row, "boy_recommend_qty"));
                    detail.setGirlRecommendQty(TextParserUtil.parseDouble(row, "girl_recommend_qty"));
                    detail.setBoyLowerLimit(TextParserUtil.parseDouble(row, "boy_lower_limit"));
                    detail.setBoyUpperLimit(TextParserUtil.parseDouble(row, "boy_upper_limit"));
                    detail.setGirlLowerLimit(TextParserUtil.parseDouble(row, "girl_lower_limit"));
                    detail.setGirlUpperLimit(TextParserUtil.parseDouble(row, "girl_upper_limit"));
                    detail.setIntakeSuitableSuggestion(TextParserUtil.parseString(row, "intake_suitable_suggestion"));
                    detail.setIntakeLowSuggestion(TextParserUtil.parseString(row, "intake_low_suggestion"));
                    detail.setIntakeHighSuggestion(TextParserUtil.parseString(row, "intake_high_suggestion"));

                    if (detail.getNsId() != null && detail.getNutrientId() != null) {
                        String embeddingText = String.format("营养标准 %s %s 营养素 %s",
                                detail.getNsName(), detail.getNscName(), detail.getNutrientName());
                        detail.setEmbeddingText(embeddingText);
                        result.add(detail);
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析营养素详情失败", e);
        }
        return result;
    }

    /**
     * 将营养素详情数据批量嵌入向量并插入 Milvus
     *
     * @param details 营养素详情列表
     */
    private void insertNutrientStandardDetails(List<NutrientStandardDetail> details) {
        List<JsonObject> dataList = new ArrayList<>();

        List<String> texts = new ArrayList<>();
        for (NutrientStandardDetail detail : details) {
            String text = String.format("营养标准 %s %s 营养素 %s",
                    detail.getNsName(), detail.getNscName(), detail.getNutrientName());
            texts.add(text);
        }

        List<List<Float>> allEmbeddings = embeddingBatchService.embedBatchWithRetry(texts);

        for (int i = 0; i < details.size(); i++) {
            NutrientStandardDetail detail = details.get(i);
            List<Float> embedding = allEmbeddings.get(i);
            String text = texts.get(i);

            JsonObject suggestions = new JsonObject();
            if (detail.getIntakeSuitableSuggestion() != null) {
                suggestions.addProperty("suitable", detail.getIntakeSuitableSuggestion());
            }
            if (detail.getIntakeLowSuggestion() != null) {
                suggestions.addProperty("low", detail.getIntakeLowSuggestion());
            }
            if (detail.getIntakeHighSuggestion() != null) {
                suggestions.addProperty("high", detail.getIntakeHighSuggestion());
            }

            JsonObject data = new JsonObject();
            data.addProperty("id", detail.getId());
            data.addProperty("ns_id", detail.getNsId());
            data.addProperty("ns_code", detail.getNsCode());
            data.addProperty("ns_name", detail.getNsName());
            data.addProperty("age_group", detail.getAgeGroup());
            data.addProperty("region", detail.getRegion());
            if (detail.getNscId() != null) {
                data.addProperty("nsc_id", detail.getNscId());
            }
            if (detail.getNscName() != null) {
                data.addProperty("nsc_name", detail.getNscName());
            }
            data.addProperty("nutrient_id", detail.getNutrientId());
            data.addProperty("nutrient_name", detail.getNutrientName());
            data.addProperty("all_recommend_qty", detail.getAllRecommendQty());
            data.addProperty("suggestions_json", suggestions.toString());
            data.addProperty("embedding_text", text);
            data.add("embedding", gson.toJsonTree(embedding));
            dataList.add(data);
        }

        log.info("embedding完成，开始插入 {} 条数据到 {}", dataList.size(), COLLECTION_NAME);
        milvusCollectionService.upsertBatch(COLLECTION_NAME, dataList, batchSize);
    }

    // ==================== 内部 DTO ====================

    /** 营养素详情数据传输对象 */
    @Data
    static class NutrientStandardDetail {
        /** 详情ID */
        private Long id;
        /** 营养标准ID */
        private Long nsId;
        /** 标准编码 */
        private String nsCode;
        /** 标准名称 */
        private String nsName;
        /** 适用年龄段 */
        private String ageGroup;
        /** 地区标准 */
        private String region;
        /** 就餐人群ID */
        private Long nscId;
        /** 就餐人群名称 */
        private String nscName;
        /** 最小年龄 */
        private Integer minAge;
        /** 最大年龄 */
        private Integer maxAge;
        /** 营养素ID */
        private Long nutrientId;
        /** 营养素名称 */
        private String nutrientName;
        /** 推荐摄入量 */
        private Double allRecommendQty;
        /** 男孩推荐摄入量 */
        private Double boyRecommendQty;
        /** 女孩推荐摄入量 */
        private Double girlRecommendQty;
        /** 男孩下限 */
        private Double boyLowerLimit;
        /** 男孩上限 */
        private Double boyUpperLimit;
        /** 女孩下限 */
        private Double girlLowerLimit;
        /** 女孩上限 */
        private Double girlUpperLimit;
        /** 适宜摄入量下限 */
        private Double intakeSuitableLowerLimit;
        /** 适宜摄入量上限 */
        private Double intakeSuitableUpperLimit;
        /** 适宜摄入建议 */
        private String intakeSuitableSuggestion;
        /** 摄入偏低建议 */
        private String intakeLowSuggestion;
        /** 摄入偏高建议 */
        private String intakeHighSuggestion;
        /** 用于生成向量的文本 */
        private String embeddingText;
    }
}
