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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 区域菜系字典同步处理器
 *
 * <p>
 * 将省份→菜系映射数据同步到 Milvus 集合 {@code regional_cuisine_dictionary}，
 * 为配餐推荐提供区域菜系偏好维度。数据来源为内置静态映射，无需外部 MCP 查询。
 * </p>
 *
 * <p>
 * 通过 {@code nutrition-extension.regional-cuisine-sync.enabled=true} 启用，
 * 默认关闭，不影响现有学校配餐流程。
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "nutrition-extension.regional-cuisine-sync.enabled", havingValue = "true")
public class RegionalCuisineSyncHandler extends BaseSyncService {

    /** Milvus 集合名称 */
    public static final String REGIONAL_CUISINE_DICTIONARY_COLLECTION = "regional_cuisine_dictionary";

    /** Gson 序列化工具 */
    private final Gson gson = new GsonBuilder().create();

    /**
     * 构造函数，注入 Milvus 操作门面和嵌入服务
     *
     * @param milvusOps       Milvus 操作门面
     * @param embeddingService 嵌入服务
     */
    public RegionalCuisineSyncHandler(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            McpQueryService mcpQueryService,
            MilvusCollectionService milvusCollectionService,
            EmbeddingBatchService embeddingBatchService) {
        super(milvusOps, embeddingService, mcpQueryService, milvusCollectionService, embeddingBatchService);
    }

    // ==================== 省份→菜系静态映射 ====================

    /** 省份菜系映射数据 */
    private static final List<RegionalCuisine> REGIONAL_CUISINES = Arrays.asList(
            // 八大菜系
            new RegionalCuisine("510000", "四川省", "川菜", "麻婆豆腐,宫保鸡丁,回锅肉,水煮鱼,夫妻肺片,鱼香肉丝,担担面,龙抄手", "麻辣鲜香，善用花椒辣椒，味型丰富"),
            new RegionalCuisine("440000", "广东省", "粤菜", "白切鸡,烧鹅,清蒸鱼,虾饺,叉烧,煲仔饭,肠粉,老火靓汤", "清鲜嫩滑，讲究原汁原味，善用煲汤"),
            new RegionalCuisine("370000", "山东省", "鲁菜", "糖醋鲤鱼,九转大肠,葱烧海参,油爆双脆,德州扒鸡,煎饼卷大葱", "咸鲜为主，精于火候，善用葱姜"),
            new RegionalCuisine("320000", "江苏省", "苏菜", "松鼠桂鱼,清炖蟹粉狮子头,大煮干丝,盐水鸭,叫花鸡,三套鸭", "甜咸适中，注重刀工火候，炖焖见长"),
            new RegionalCuisine("430000", "湖南省", "湘菜", "剁椒鱼头,小炒肉,毛氏红烧肉,腊味合蒸,臭豆腐,口味虾", "香辣酸鲜，擅长炒煨腊蒸"),
            new RegionalCuisine("350000", "福建省", "闽菜", "佛跳墙,荔枝肉,醉排骨,沙茶面,蚵仔煎,光饼,鱼丸", "清鲜和醇，善用红糟，多汤品"),
            new RegionalCuisine("330000", "浙江省", "浙菜", "西湖醋鱼,东坡肉,龙井虾仁,叫花童鸡,宋嫂鱼羹,干菜焖肉", "清鲜脆嫩，制作精细，注重本味"),
            new RegionalCuisine("340000", "安徽省", "徽菜", "臭鳜鱼,毛豆腐,徽州一品锅,李鸿章大杂烩,黄山炖鸽,火腿炖甲鱼", "重油重色重火功，善用火腿笋干"),

            // 地方特色菜系
            new RegionalCuisine("210000", "辽宁省", "东北菜", "锅包肉,地三鲜,小鸡炖蘑菇,猪肉炖粉条,酸菜白肉,溜肉段", "量大实惠，炖菜为主，咸鲜浓香"),
            new RegionalCuisine("220000", "吉林省", "东北菜", "锅包肉,地三鲜,小鸡炖蘑菇,猪肉炖粉条,酸菜白肉,朝鲜冷面", "量大实惠，炖菜为主，兼有朝鲜族风味"),
            new RegionalCuisine("230000", "黑龙江省", "东北菜", "锅包肉,地三鲜,小鸡炖蘑菇,猪肉炖粉条,酸菜白肉,杀猪菜", "量大实惠，炖菜为主，冬季炖菜丰富"),
            new RegionalCuisine("610000", "陕西省", "西北菜", "羊肉泡馍,肉夹馍,凉皮,biangbiang面,葫芦鸡,臊子面", "酸辣鲜香，面食为主，牛羊肉丰富"),
            new RegionalCuisine("620000", "甘肃省", "西北菜", "兰州牛肉面,手抓羊肉,酿皮子,灰豆子,甜醅子,浆水面", "面食为主，牛羊肉丰富，口味浓厚"),
            new RegionalCuisine("640000", "宁夏回族自治区", "西北菜", "手抓羊肉,羊杂碎,烩羊杂,清蒸羊羔肉,馓子,油香", "清真风味为主，牛羊肉丰富"),
            new RegionalCuisine("650000", "新疆维吾尔自治区", "西北菜", "大盘鸡,烤羊肉串,手抓饭,馕,拉条子,烤包子", "清真风味为主，牛羊肉面食丰富，孜然调味"),
            new RegionalCuisine("150000", "内蒙古自治区", "西北菜", "烤全羊,手把肉,奶茶,奶豆腐,莜面窝窝,羊肉烧麦", "牛羊肉奶食为主，草原风味"),

            // 其他省份
            new RegionalCuisine("110000", "北京市", "京菜", "北京烤鸭,炸酱面,涮羊肉,卤煮火烧,豆汁焦圈,爆肚", "融合宫廷菜与民间小吃，酱香浓郁"),
            new RegionalCuisine("120000", "天津市", "京菜", "狗不理包子,煎饼果子,麻花,锅巴菜,耳朵眼炸糕,八珍豆腐", "小吃丰富，甜咸兼备，海鲜河鲜并用"),
            new RegionalCuisine("130000", "河北省", "京菜", "驴肉火烧,承德满汉全席,保定烙饼,藁城宫面,柴沟堡熏肉", "北方菜系，面食肉类为主"),
            new RegionalCuisine("140000", "山西省", "京菜", "刀削面,剔尖,平遥牛肉,过油肉,太谷饼,莜面栲栳栳", "面食之乡，醋文化浓厚"),
            new RegionalCuisine("410000", "河南省", "中原菜", "胡辣汤,烩面,灌汤包,道口烧鸡,焖饼,扁粉菜", "面食为主，口味适中，汤面丰富"),
            new RegionalCuisine("420000", "湖北省", "鄂菜", "热干面,武昌鱼,排骨藕汤,三鲜豆皮,面窝,沔阳三蒸", "鲜香微辣，善蒸煨，水产丰富"),
            new RegionalCuisine("450000", "广西壮族自治区", "桂菜", "螺蛳粉,桂林米粉,啤酒鱼,柠檬鸭,白切狗肉,荔浦芋扣肉", "酸辣鲜香，米粉文化，少数民族风味丰富"),
            new RegionalCuisine("460000", "海南省", "琼菜", "文昌鸡,加积鸭,东山羊,和乐蟹,椰子饭,清补凉", "清淡鲜美，椰子入菜，海鲜丰富"),
            new RegionalCuisine("500000", "重庆市", "川菜", "火锅,辣子鸡,毛血旺,水煮肉片,酸辣粉,小面", "麻辣热烈，火锅文化，江湖菜丰富"),
            new RegionalCuisine("510000", "四川省", "川菜", "麻婆豆腐,宫保鸡丁,回锅肉,水煮鱼,夫妻肺片,鱼香肉丝", "麻辣鲜香，味型丰富"),
            new RegionalCuisine("520000", "贵州省", "黔菜", "酸汤鱼,丝娃娃,肠旺面,花溪牛肉粉,折耳根炒腊肉,辣子鸡", "酸辣为主，酸汤文化，辣椒品种丰富"),
            new RegionalCuisine("530000", "云南省", "滇菜", "过桥米线,汽锅鸡,鲜花饼,野生菌火锅,宣威火腿,破酥包子", "鲜香酸辣，食材多样，菌类花卉入菜"),
            new RegionalCuisine("540000", "西藏自治区", "藏菜", "酥油茶,糌粑,藏面,牦牛肉干,青稞酒,甜茶", "高原能量饮食，牛羊肉奶食为主"),
            new RegionalCuisine("710000", "台湾省", "台菜", "卤肉饭,牛肉面,珍珠奶茶,蚵仔煎,三杯鸡,凤梨酥", "闽粤根基，融合日式，小吃文化丰富"),
            new RegionalCuisine("810000", "香港特别行政区", "粤菜", "港式茶餐厅,蛋挞,菠萝油,烧腊,云吞面,煲仔饭", "粤菜根基，中西融合，茶餐厅文化"),
            new RegionalCuisine("820000", "澳门特别行政区", "粤菜", "葡式蛋挞,猪扒包,水蟹粥,非洲鸡,马介休球,杏仁饼", "粤葡融合，中西并蓄")
    );

    // ==================== Schema 创建 ====================

    /**
     * 创建区域菜系字典集合的 Schema
     *
     * @return Milvus 集合创建请求
     */
    public CreateCollectionReq createSchema() {
        int dimension = embeddingService.getDimension();

        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false)
                .description("主键ID").build();

        CreateCollectionReq.FieldSchema provinceCodeField = CreateCollectionReq.FieldSchema.builder()
                .name("province_code").dataType(DataType.VarChar).maxLength(20)
                .description("省份编码").build();

        CreateCollectionReq.FieldSchema provinceNameField = CreateCollectionReq.FieldSchema.builder()
                .name("province_name").dataType(DataType.VarChar).maxLength(50)
                .description("省份名称").build();

        CreateCollectionReq.FieldSchema cuisineTypeField = CreateCollectionReq.FieldSchema.builder()
                .name("cuisine_type").dataType(DataType.VarChar).maxLength(30)
                .description("菜系类型: 川菜/粤菜/鲁菜/苏菜/湘菜/闽菜/浙菜/徽菜/东北菜/西北菜/京菜/中原菜/鄂菜/桂菜/琼菜/黔菜/滇菜/藏菜/台菜").build();

        CreateCollectionReq.FieldSchema representativeDishesField = CreateCollectionReq.FieldSchema.builder()
                .name("representative_dishes").dataType(DataType.VarChar).maxLength(2000)
                .description("代表菜品(逗号分隔)").build();

        CreateCollectionReq.FieldSchema descriptionField = CreateCollectionReq.FieldSchema.builder()
                .name("description").dataType(DataType.VarChar).maxLength(1000)
                .description("菜系特点描述").build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding_text").dataType(DataType.VarChar).maxLength(3000)
                .description("用于生成embedding的文本").build();

        CreateCollectionReq.FieldSchema embeddingField = CreateCollectionReq.FieldSchema.builder()
                .name("embedding").dataType(DataType.FloatVector).dimension(dimension)
                .description("文本向量").build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, provinceCodeField, provinceNameField,
                        cuisineTypeField, representativeDishesField, descriptionField, textField, embeddingField))
                .build();

        IndexParam indexParam = IndexParam.builder()
                .fieldName("embedding")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();

        return CreateCollectionReq.builder()
                .collectionName(REGIONAL_CUISINE_DICTIONARY_COLLECTION)
                .description("区域菜系字典 - 省份菜系映射与代表菜品")
                .collectionSchema(schema)
                .indexParams(Collections.singletonList(indexParam))
                .build();
    }

    // ==================== 同步逻辑 ====================

    /**
     * 执行区域菜系字典同步
     *
     * <p>
     * 从内置静态映射构建数据，批量嵌入并 upsert 到 Milvus。
     * 使用 upsert 保证可重复执行不产生重复数据。
     * </p>
     *
     * @return 同步记录数
     */
    public int sync() {
        log.info("开始同步区域菜系字典到 Milvus 集合: {}", REGIONAL_CUISINE_DICTIONARY_COLLECTION);

        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，跳过区域菜系字典同步");
            return 0;
        }

        try {
            // 确保集合存在
            if (!isCollectionExists(REGIONAL_CUISINE_DICTIONARY_COLLECTION)) {
                CreateCollectionReq req = createSchema();
                milvusOps.createCollection(REGIONAL_CUISINE_DICTIONARY_COLLECTION, req.getCollectionSchema(), req.getIndexParams());
                log.info("创建 Milvus 集合: {}", REGIONAL_CUISINE_DICTIONARY_COLLECTION);
            }

            // 构建数据
            List<JsonObject> dataList = new ArrayList<>();
            List<String> embeddingTexts = new ArrayList<>();
            long idCounter = 1;
            for (RegionalCuisine rc : REGIONAL_CUISINES) {
                JsonObject data = new JsonObject();
                data.addProperty("id", idCounter++);
                data.addProperty("province_code", rc.getProvinceCode());
                data.addProperty("province_name", rc.getProvinceName());
                data.addProperty("cuisine_type", rc.getCuisineType());
                data.addProperty("representative_dishes", rc.getRepresentativeDishes());
                data.addProperty("description", rc.getDescription());

                // 构建嵌入文本：省份名 + 菜系 + 代表菜品 + 描述
                String embeddingText = String.format("%s %s 代表菜品: %s 特点: %s",
                        rc.getProvinceName(), rc.getCuisineType(),
                        rc.getRepresentativeDishes(), rc.getDescription());
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
            upsertBatch(REGIONAL_CUISINE_DICTIONARY_COLLECTION, dataList, 50);

            log.info("区域菜系字典同步完成，共 {} 条记录", dataList.size());
            return dataList.size();
        } catch (Exception e) {
            log.error("区域菜系字典同步失败", e);
            return 0;
        }
    }

    // ==================== 搜索方法 ====================

    /**
     * 根据省份编码搜索菜系信息
     *
     * <p>
     * 优先从内置数据查找省份对应的菜系，再通过向量搜索获取相似菜系。
     * </p>
     *
     * @param provinceCode 省份编码（如 "510000"）
     * @param topK         返回结果数
     * @return 搜索结果文本
     */
    public String searchCuisinesByProvince(String provinceCode, int topK) {
        if (!milvusOps.isAvailable()) {
            log.warn("Milvus 不可用，无法搜索区域菜系");
            return "Milvus 不可用";
        }
        try {
            // 从内置数据中查找搜索文本
            Optional<RegionalCuisine> found = REGIONAL_CUISINES.stream()
                    .filter(rc -> rc.getProvinceCode().equals(provinceCode))
                    .findFirst();

            String searchText = found
                    .map(rc -> rc.getProvinceName() + " " + rc.getCuisineType() + " " + rc.getRepresentativeDishes())
                    .orElse(provinceCode + " 菜系");

            // 向量搜索
            List<Float> queryEmbedding = embeddingService.embedBatch(Collections.singletonList(searchText)).get(0);
            List<SearchResp.SearchResult> searchResults = milvusOps.search(
                    REGIONAL_CUISINE_DICTIONARY_COLLECTION,
                    queryEmbedding,
                    topK,
                    Arrays.asList("province_code", "province_name", "cuisine_type", "representative_dishes", "description"),
                    null);

            StringBuilder sb = new StringBuilder();
            sb.append("【区域菜系推荐】\n");
            for (SearchResp.SearchResult result : searchResults) {
                sb.append(String.format("- %s (%s): %s — %s\n",
                        result.getEntity().get("province_name"),
                        result.getEntity().get("cuisine_type"),
                        result.getEntity().get("representative_dishes"),
                        result.getEntity().get("description")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("搜索区域菜系失败: provinceCode={}", provinceCode, e);
            return "搜索区域菜系失败: " + e.getMessage();
        }
    }

    /**
     * 根据省份编码获取菜系类型（本地查找，无需向量搜索）
     *
     * @param provinceCode 省份编码
     * @return 菜系类型，未找到返回 null
     */
    public String getCuisineTypeByProvince(String provinceCode) {
        return REGIONAL_CUISINES.stream()
                .filter(rc -> rc.getProvinceCode().equals(provinceCode))
                .map(RegionalCuisine::getCuisineType)
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有菜系类型的去重列表
     *
     * @return 菜系类型列表
     */
    public List<String> getAllCuisineTypes() {
        return REGIONAL_CUISINES.stream()
                .map(RegionalCuisine::getCuisineType)
                .distinct()
                .collect(Collectors.toList());
    }

    // ==================== 内部数据模型 ====================

    /**
     * 区域菜系数据模型
     */
    @Data
    private static class RegionalCuisine {
        /** 省份编码 */
        private final String provinceCode;
        /** 省份名称 */
        private final String provinceName;
        /** 菜系类型 */
        private final String cuisineType;
        /** 代表菜品（逗号分隔） */
        private final String representativeDishes;
        /** 菜系特点描述 */
        private final String description;

        RegionalCuisine(String provinceCode, String provinceName, String cuisineType,
                        String representativeDishes, String description) {
            this.provinceCode = provinceCode;
            this.provinceName = provinceName;
            this.cuisineType = cuisineType;
            this.representativeDishes = representativeDishes;
            this.description = description;
        }
    }
}
