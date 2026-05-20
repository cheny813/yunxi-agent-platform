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

/**
 * 食材季节性属性同步处理器
 *
 * <p>
 * 将食材季节性数据同步到 Milvus 集合 {@code ingredient_seasonality}，
 * 为配餐推荐提供当季食材偏好维度。数据来源为内置常见食材季节性映射，
 * 并与营养数据库的 food_ingredient 表交叉关联。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.ingredient-seasonality-sync.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.ingredient-seasonality-sync.enabled", havingValue = "true")
public class IngredientSeasonalitySyncHandler extends BaseSyncService {

    /** Milvus 集合名称 */
    public static final String INGREDIENT_SEASONALITY_COLLECTION = "ingredient_seasonality";

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
    public IngredientSeasonalitySyncHandler(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        super(milvusOps, embeddingService, mcpQueryService, milvusCollectionService, embeddingBatchService);
    }

    // ==================== 常见食材季节性映射 ====================

    /** 季节性食材数据: 食材名称 → 季节性信息 */
    private static final Map<String, SeasonalityInfo> SEASONALITY_MAP = new LinkedHashMap<>();

    static {
        // 蔬菜类 — 春季
        addSeasonality("春笋", "春", "3,4", "江南地区春季特色，鲜嫩爽口");
        addSeasonality("香椿", "春", "3,4", "春季时令蔬菜，香气浓郁");
        addSeasonality("豌豆苗", "春", "3,4,5", "春季嫩苗，口感鲜嫩");
        addSeasonality("荠菜", "春", "2,3,4", "春季野菜，清香可口");
        addSeasonality("菠菜", "春,秋,冬", "3,4,10,11", "耐寒蔬菜，春秋冬三季可食");
        // 蔬菜类 — 夏季
        addSeasonality("黄瓜", "夏", "6,7,8", "夏季消暑蔬菜，水分充足");
        addSeasonality("西红柿", "夏", "6,7,8,9", "夏季果蔬，营养丰富");
        addSeasonality("苦瓜", "夏", "6,7,8", "夏季清热蔬菜，苦味回甘");
        addSeasonality("丝瓜", "夏", "6,7,8", "夏季消暑蔬菜，口感滑嫩");
        addSeasonality("冬瓜", "夏", "7,8,9", "夏季消暑蔬菜，清淡利水");
        addSeasonality("空心菜", "夏", "6,7,8,9", "夏季绿叶蔬菜，生长快速");
        addSeasonality("苋菜", "夏", "6,7,8", "夏季时令蔬菜，红苋菜含铁丰富");
        // 蔬菜类 — 秋季
        addSeasonality("莲藕", "秋", "9,10,11", "秋季水生蔬菜，粉糯鲜甜");
        addSeasonality("南瓜", "秋", "9,10,11", "秋季瓜果蔬菜，甜糯可口");
        addSeasonality("山药", "秋,冬", "10,11,12", "秋冬滋补蔬菜，健脾胃");
        addSeasonality("芋头", "秋", "9,10,11", "秋季根茎蔬菜，粉糯细腻");
        // 蔬菜类 — 冬季
        addSeasonality("白萝卜", "冬", "11,12,1,2", "冬季根茎蔬菜，消食化痰");
        addSeasonality("大白菜", "冬", "11,12,1,2", "北方冬季主要蔬菜，耐储存");
        addSeasonality("冬笋", "冬", "12,1", "冬季时令蔬菜，口感脆嫩");
        addSeasonality("芹菜", "冬", "11,12,1,2", "冬季绿叶蔬菜，降压利尿");
        // 全年蔬菜
        addSeasonality("土豆", "全年", "", "四季可供应，主粮蔬菜");
        addSeasonality("胡萝卜", "全年", "", "四季可供应，富含胡萝卜素");
        addSeasonality("洋葱", "全年", "", "四季可供应，耐储存");
        addSeasonality("大葱", "全年", "", "四季可供应，调味蔬菜");
        addSeasonality("生姜", "全年", "", "四季可供应，调味蔬菜");
        addSeasonality("大蒜", "全年", "", "四季可供应，调味蔬菜");
        addSeasonality("青椒", "全年", "", "四季可供应，大棚种植");
        addSeasonality("茄子", "全年", "7,8,9", "夏秋最佳，大棚全年供应");
        addSeasonality("花菜", "全年", "10,11,12", "秋冬最佳，大棚全年供应");
        addSeasonality("西兰花", "全年", "10,11,12", "秋冬最佳，大棚全年供应");
        addSeasonality("生菜", "全年", "", "四季可供应，生长周期短");
        addSeasonality("卷心菜", "全年", "", "四季可供应，耐储存");
        // 水果类 — 春季
        addSeasonality("草莓", "春", "3,4,5", "春季水果，酸甜多汁");
        addSeasonality("枇杷", "春", "4,5", "春季水果，润肺止咳");
        addSeasonality("樱桃", "春,夏", "5,6", "春夏之交水果，铁含量高");
        // 水果类 — 夏季
        addSeasonality("西瓜", "夏", "6,7,8", "夏季消暑水果，水分充足");
        addSeasonality("桃子", "夏", "6,7,8", "夏季水果，香甜多汁");
        addSeasonality("荔枝", "夏", "6,7", "夏季水果，甜美多汁");
        addSeasonality("龙眼", "夏", "7,8", "夏季水果，补益心脾");
        addSeasonality("芒果", "夏", "6,7,8", "热带夏季水果，香甜浓郁");
        addSeasonality("葡萄", "夏,秋", "7,8,9", "夏秋水果，品种丰富");
        addSeasonality("哈密瓜", "夏", "7,8", "新疆夏季瓜果，甜度高");
        // 水果类 — 秋季
        addSeasonality("苹果", "秋", "9,10,11", "秋季水果，耐储存，全年可供应");
        addSeasonality("梨", "秋", "9,10,11", "秋季水果，润肺生津");
        addSeasonality("柿子", "秋", "10,11", "秋季水果，甜软可口");
        addSeasonality("石榴", "秋", "9,10", "秋季水果，酸甜多汁");
        addSeasonality("柚子", "秋,冬", "10,11,12", "秋冬水果，维C丰富");
        addSeasonality("橘子", "秋,冬", "10,11,12,1", "秋冬水果，品种丰富");
        addSeasonality("橙子", "秋,冬", "11,12,1,2", "秋冬水果，维C丰富");
        addSeasonality("猕猴桃", "秋", "9,10,11", "秋季水果，维C之王");
        // 水果类 — 冬季
        addSeasonality("甘蔗", "冬", "11,12,1,2", "冬季水果，甜度高，生津解渴");
        // 水产类
        addSeasonality("带鱼", "冬", "11,12,1", "冬季带鱼肥美，肉质细嫩");
        addSeasonality("鲈鱼", "秋,冬", "10,11,12", "秋冬鲈鱼肥美，肉质鲜嫩");
        addSeasonality("对虾", "春,秋", "4,5,9,10", "春秋两季虾肉饱满");
        addSeasonality("螃蟹", "秋", "9,10,11", "秋季螃蟹膏肥黄满");
        addSeasonality("黄鱼", "春,夏", "4,5,6", "春夏大黄鱼汛期，肉质鲜嫩");
        // 禽畜类
        addSeasonality("羊肉", "冬", "11,12,1", "冬季温补，驱寒暖身");
        addSeasonality("鸭肉", "夏,秋", "7,8,9", "夏秋滋阴，清热降火");
    }

    /**
     * 添加季节性食材到静态映射
     *
     * @param name        食材名称
     * @param seasons     适宜季节（春/夏/秋/冬/全年，逗号分隔）
     * @param peakMonths  旺季月份（逗号分隔）
     * @param description 描述信息
     */
    private static void addSeasonality(String name, String seasons, String peakMonths, String description) {
        SEASONALITY_MAP.put(name, new SeasonalityInfo(name, seasons, peakMonths, description));
    }

    // ==================== Schema 创建 ====================

    /**
     * 创建食材季节性集合的 Schema
     *
     * @return Milvus 集合创建请求
     */
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("主键ID").build();

        CreateCollectionReq.FieldSchema ingredientIdField = CreateCollectionReq.FieldSchema.builder()
                .name("ingredient_id").dataType(DataType.Int64)
                .description("食材ID(对应food_ingredient表)").build();

        CreateCollectionReq.FieldSchema ingredientNameField = CreateCollectionReq.FieldSchema.builder()
                .name("ingredient_name").dataType(DataType.VarChar).maxLength(100)
                .description("食材名称").build();

        CreateCollectionReq.FieldSchema seasonsField = CreateCollectionReq.FieldSchema.builder()
                .name("seasons").dataType(DataType.VarChar).maxLength(100)
                .description("适宜季节: 春/夏/秋/冬/全年").build();

        CreateCollectionReq.FieldSchema peakMonthsField = CreateCollectionReq.FieldSchema.builder()
                .name("peak_months").dataType(DataType.VarChar).maxLength(50)
                .description("旺季月份: 3,4,5").build();

        CreateCollectionReq.FieldSchema regionSuitabilityField = CreateCollectionReq.FieldSchema.builder()
                .name("region_suitability").dataType(DataType.VarChar).maxLength(200)
                .description("地域适宜性描述").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(2000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, ingredientIdField, ingredientNameField,
                        seasonsField, peakMonthsField, regionSuitabilityField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(INGREDIENT_SEASONALITY_COLLECTION)
                .description("食材季节性属性 - 食材适宜季节与旺季月份")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    // ==================== 同步逻辑 ====================

    /**
     * 执行食材季节性属性同步
     *
     * <p>
     * 从营养数据库查询所有食材，与内置季节性映射交叉关联，
     * 批量嵌入并 upsert 到 Milvus。
     * </p>
     *
     * @return 同步记录数
     */
    public int sync() {
        log.info("开始同步食材季节性属性到 Milvus 集合: {}", INGREDIENT_SEASONALITY_COLLECTION);

        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过食材季节性属性同步");
            return 0;
        }

        try {
            // 确保集合存在
            if (!isCollectionExists(INGREDIENT_SEASONALITY_COLLECTION)) {
                CreateCollectionReq req = createSchema();
                milvusOps.createCollection(INGREDIENT_SEASONALITY_COLLECTION, req.getCollectionSchema(), req.getIndexParams());
                log.info("创建 Milvus 集合: {}", INGREDIENT_SEASONALITY_COLLECTION);
            }

            // 从营养数据库查询食材列表
            String sql = "SELECT id, name FROM food_ingredient WHERE deleted = 0";
            String result = callMcpDatabase(mcpDbHost, mcpDbPort, sql);

            // 解析食材数据
            List<IngredientRecord> ingredients = parseIngredients(result);
            log.info("从营养数据库获取 {} 条食材记录", ingredients.size());

            // 构建数据：与季节性映射交叉
            List<JsonObject> dataList = new ArrayList<>();
            List<String> embeddingTexts = new ArrayList<>();
            long idCounter = 1;

            for (IngredientRecord ingredient : ingredients) {
                SeasonalityInfo info = SEASONALITY_MAP.get(ingredient.getName());

                JsonObject data = new JsonObject();
                data.addProperty("id", idCounter++);
                data.addProperty("ingredient_id", ingredient.getId());
                data.addProperty("ingredient_name", ingredient.getName());
                data.addProperty("seasons", info != null ? info.getSeasons() : "全年");
                data.addProperty("peak_months", info != null ? info.getPeakMonths() : "");
                data.addProperty("region_suitability", info != null ? info.getDescription() : "四季可供应");

                String embeddingText = String.format("食材: %s 适宜季节: %s 旺季: %s %s",
                        ingredient.getName(),
                        info != null ? info.getSeasons() : "全年",
                        info != null && !info.getPeakMonths().isEmpty() ? info.getPeakMonths() + "月" : "全年供应",
                        info != null ? info.getDescription() : "");
                data.addProperty("embedding_text", embeddingText);
                embeddingTexts.add(embeddingText);

                dataList.add(data);
            }

            // 批量嵌入
            List<List<Float>> embeddings = embedBatchWithRetry(embeddingTexts);
            for (int i = 0; i < dataList.size(); i++) {
                dataList.get(i).add("embedding", gson.toJsonTree(embeddings.get(i)));
            }

            // 批量 upsert
            upsertBatch(INGREDIENT_SEASONALITY_COLLECTION, dataList, 100);

            log.info("食材季节性属性同步完成，共 {} 条记录", dataList.size());
            return dataList.size();
        } catch (Exception e) {
            log.error("食材季节性属性同步失败", e);
            return 0;
        }
    }

    // ==================== 搜索方法 ====================

    /**
     * 搜索当季食材
     *
     * @param month 当前月份 (1-12)
     * @param topK  返回结果数
     * @return 当季食材列表文本
     */
    public String searchSeasonalIngredients(int month, int topK) {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，无法搜索当季食材");
            return "Milvus 不可用";
        }
        try {
            String searchText = String.format("适宜%d月的当季食材 旺季%d月", month, month);
            List<Float> queryEmbedding = embeddingService.embedBatch(Collections.singletonList(searchText)).get(0);

            List<SearchResp.SearchResult> searchResults = milvusOps.search(
                    INGREDIENT_SEASONALITY_COLLECTION,
                    queryEmbedding,
                    topK,
                    Arrays.asList("ingredient_id", "ingredient_name", "seasons", "peak_months", "region_suitability"),
                    null);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("【%d月当季食材推荐】\n", month));
            for (SearchResp.SearchResult result : searchResults) {
                sb.append(String.format("- %s (适宜季节: %s, 旺季: %s) %s\n",
                        result.getEntity().get("ingredient_name"),
                        result.getEntity().get("seasons"),
                        result.getEntity().get("peak_months"),
                        result.getEntity().get("region_suitability")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("搜索当季食材失败: month={}", month, e);
            return "搜索当季食材失败: " + e.getMessage();
        }
    }

    /**
     * 获取食材的季节性信息（本地查找）
     *
     * @param ingredientName 食材名称
     * @return 季节性信息，未找到返回"全年"
     */
    public String getSeasonsForIngredient(String ingredientName) {
        SeasonalityInfo info = SEASONALITY_MAP.get(ingredientName);
        return info != null ? info.getSeasons() : "全年";
    }

    // ==================== 解析方法 ====================

    /**
     * 解析 MCP 查询返回的食材数据
     *
     * <p>
     * 解析格式: "Row N: {key=value, key=value, ...}"
     * </p>
     *
     * @param result MCP 查询结果
     * @return 食材记录列表
     */
    private List<IngredientRecord> parseIngredients(String result) {
        List<IngredientRecord> ingredients = new ArrayList<>();
        if (result == null || result.isEmpty()) {
            return ingredients;
        }

        Pattern rowPattern = Pattern.compile("Row \\d+: \\{([^}]+)\\}");
        Matcher matcher = rowPattern.matcher(result);

        while (matcher.find()) {
            String rowContent = matcher.group(1);
            Long id = TextParserUtil.parseLong(rowContent, "id");
            String name = TextParserUtil.parseString(rowContent, "name");

            if (id != null && name != null) {
                ingredients.add(new IngredientRecord(id, name));
            }
        }

        return ingredients;
    }

    // ==================== 内部数据模型 ====================

    /**
     * 食材记录（从 MCP 查询解析）
     */
    @Data
    private static class IngredientRecord {
        /** 食材ID */
        private final Long id;
        /** 食材名称 */
        private final String name;

        IngredientRecord(Long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * 季节性信息
     */
    @Data
    private static class SeasonalityInfo {
        /** 食材名称 */
        private final String name;
        /** 适宜季节 */
        private final String seasons;
        /** 旺季月份 */
        private final String peakMonths;
        /** 描述信息 */
        private final String description;

        SeasonalityInfo(String name, String seasons, String peakMonths, String description) {
            this.name = name;
            this.seasons = seasons;
            this.peakMonths = peakMonths;
            this.description = description;
        }
    }
}
