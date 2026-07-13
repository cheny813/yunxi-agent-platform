package io.yunxi.platform.persistence.milvus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.embedding.EmbeddingService;
import io.yunxi.platform.sync.MilvusCollectionService;
import io.yunxi.platform.sync.SyncEngine;
import io.yunxi.platform.sync.config.SyncPipelineConfig;
import io.yunxi.platform.config.MilvusConfig;
import jakarta.annotation.PostConstruct;

/**
 * Milvus 统一集合初始化器
 * <p>启动时创建所有 Milvus 集合</p>
 *
 * @author yunxi-agent-platform
 */
@Component
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class MilvusSchemaInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(MilvusSchemaInitializer.class);

    private final MilvusOperations milvusOps;
    private final EmbeddingService embeddingService;
    private final List<SyncPipelineConfig> pipelineConfigs;
    private final MilvusConfig milvusConfig;
    private final MilvusCollectionService milvusCollectionService;

    private static final String FIELD_ID = "id";
    private static final String FIELD_USER_ID = "userId";
    private static final String FIELD_FILE_ID = "fileId";
    private static final String FIELD_FILE_TYPE = "fileType";
    private static final String FIELD_FILE_NAME = "fileName";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_METADATA = "metadata";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_IMAGE_TYPE = "imageType";
    private static final String FIELD_FEATURE_DIMENSION = "featureDimension";
    private static final String FIELD_FEATURE_MODEL = "featureModel";
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    private static final String FIELD_CONTENT_TYPE = "contentType";

    /**
     * 构造 Milvus 集合初始化器。
     *
     * @param milvusOps               Milvus 操作门面
     * @param embeddingService        文本向量化服务
     * @param pipelineConfigs         同步流水线配置列表（按 Bean 名称 {@code syncPipelineConfigs} 注入）
     * @param milvusConfig            Milvus 配置
     * @param milvusCollectionService 集合服务（用于判断集合是否已存在数据）
     */
    public MilvusSchemaInitializer(
            MilvusOperations milvusOps,
            EmbeddingService embeddingService,
            @Qualifier("syncPipelineConfigs") List<SyncPipelineConfig> pipelineConfigs,
            MilvusConfig milvusConfig,
            MilvusCollectionService milvusCollectionService) {
        this.milvusOps = milvusOps;
        this.embeddingService = embeddingService;
        this.pipelineConfigs = pipelineConfigs;
        this.milvusConfig = milvusConfig;
        this.milvusCollectionService = milvusCollectionService;
    }

    /**
     * Bean 初始化完成后统一创建全部 Milvus 集合。
     *
     * <p>依次确保内置集合（file_content / file_image_feature / conversation_memory / user_memory）\n     * 与所有同步流水线的目标集合存在；Milvus 不可用时跳过。</p>
     */
    @Override
    @PostConstruct
    public void afterPropertiesSet() {
        if (!milvusOps.isAvailable()) {
            log.warn("MilvusSchemaInitializer: Milvus 不可用，跳过集合初始化");
            return;
        }
        log.info("========== MilvusSchemaInitializer: 开始统一初始化集合 ==========");
        ensureCollection("file_content", "文件内容向量存储", buildFileContentSchema(), buildAutoIndexParams());
        ensureCollection("file_image_feature", "图像特征向量存储", buildFileImageFeatureSchema(), buildAutoIndexParams());
        ensureCollection("conversation_memory", "会话记忆数据", buildMemorySchema("conversation"), buildIvfFlatIndexParams());
        ensureCollection("user_memory", "用户记忆数据", buildMemorySchema("user"), buildIvfFlatIndexParams());
        int pipelineCount = 0;
        for (SyncPipelineConfig cfg : pipelineConfigs) {
            String collectionName = cfg.getTarget().getCollection();
            String desc = cfg.getTarget().getCollectionDescription();
            List<CreateCollectionReq.FieldSchema> fieldSchemas = cfg.getTarget().getFields().stream()
                    .map(SyncEngine::buildFieldSchema).collect(Collectors.toList());
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(fieldSchemas).build();
            List<IndexParam> indexParams = new ArrayList<>();
            if (cfg.getTarget().getIndex() != null) {
                indexParams.add(IndexParam.builder().fieldName(cfg.getTarget().getIndex().getField())
                        .metricType(IndexParam.MetricType.valueOf(cfg.getTarget().getIndex().getMetricType())).build());
            }
            ensureCollection(collectionName, desc, schema, indexParams);
            pipelineCount++;
        }
        log.info("========== MilvusSchemaInitializer: 集合初始化完成，共 {} 个集合 ==========", 4 + pipelineCount);
    }

    /**
     * 构建文件内容向量集合 schema。
     *
     * @return 文件内容集合 schema
     */
    private CreateCollectionReq.CollectionSchema buildFileContentSchema() {
        int dimension = embeddingService.getDimension();
        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field(FIELD_ID, DataType.VarChar, 36, true, "主键ID"),
                        field(FIELD_USER_ID, DataType.VarChar, 100, "用户ID"),
                        field(FIELD_FILE_ID, DataType.VarChar, 36, "文件ID"),
                        field(FIELD_FILE_TYPE, DataType.VarChar, 20, "文件类型"),
                        field(FIELD_FILE_NAME, DataType.VarChar, 500, "文件名称"),
                        field(FIELD_CONTENT, DataType.VarChar, 65535, "文件内容"),
                        field(FIELD_METADATA, DataType.VarChar, 65535, "元数据JSON"),
                        vecField(FIELD_EMBEDDING, dimension, "内容向量"),
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间")))
                .build();
    }

    /**
     * 构建图像特征向量集合 schema（固定 512 维特征向量）。
     *
     * @return 图像特征集合 schema
     */
    private CreateCollectionReq.CollectionSchema buildFileImageFeatureSchema() {
        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field(FIELD_ID, DataType.VarChar, 36, true, "主键ID"),
                        field(FIELD_USER_ID, DataType.VarChar, 100, "用户ID"),
                        field(FIELD_FILE_ID, DataType.VarChar, 36, "文件ID"),
                        field(FIELD_FILE_NAME, DataType.VarChar, 500, "文件名称"),
                        field(FIELD_IMAGE_TYPE, DataType.VarChar, 50, "图像类型"),
                        field(FIELD_FEATURE_DIMENSION, DataType.Int64, "特征向量维度"),
                        field(FIELD_FEATURE_MODEL, DataType.VarChar, 100, "特征提取模型"),
                        vecField(FIELD_EMBEDDING, 512, "图像特征向量"),
                        field(FIELD_METADATA, DataType.VarChar, 65535, "元数据JSON"),
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间")))
                .build();
    }

    /**
     * 构建会话/用户记忆向量集合 schema（维度来自配置）。
     *
     * @param type 记忆类型标识（"conversation" 或 "user"），仅用于语义区分
     * @return 记忆集合 schema
     */
    private CreateCollectionReq.CollectionSchema buildMemorySchema(String type) {
        int dimension = milvusConfig.getEmbedding().getDimension();
        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field(FIELD_ID, DataType.VarChar, 256, true, "记忆唯一标识"),
                        field(FIELD_USER_ID, DataType.VarChar, 256, "用户ID"),
                        field(FIELD_CONVERSATION_ID, DataType.VarChar, 256, "会话ID"),
                        field(FIELD_CONTENT_TYPE, DataType.VarChar, 64, "内容类型"),
                        field(FIELD_CONTENT, DataType.VarChar, 8192, "记忆内容"),
                        jsonField(FIELD_METADATA, "元数据"),
                        vecField(FIELD_EMBEDDING, dimension, "内容向量"),
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间")))
                .build();
    }

    /**
     * 构建自动索引（AUTOINDEX + COSINE）参数。
     *
     * @return 索引参数列表
     */
    private List<IndexParam> buildAutoIndexParams() {
        return Collections.singletonList(IndexParam.builder().fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.AUTOINDEX).metricType(IndexParam.MetricType.COSINE).build());
    }

    /**
     * 构建 IVF_FLAT 索引（IP 距离 + nlist 参数）参数。
     *
     * @return 索引参数列表
     */
    private List<IndexParam> buildIvfFlatIndexParams() {
        return Collections.singletonList(IndexParam.builder().fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.IVF_FLAT).metricType(IndexParam.MetricType.IP)
                .extraParams(Map.of("nlist", String.valueOf(milvusConfig.getEmbedding().getNlist()))).build());
    }

    /**
     * 构造普通字段 schema。
     *
     * @param name 字段名
     * @param type 数据类型
     * @param maxLength VarChar 最大长度
     * @param desc 字段描述
     * @return 字段 schema
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, int maxLength, String desc) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(type).maxLength(maxLength).description(desc).build();
    }

    /**
     * 构造普通字段 schema（无长度限制，如 Int64）。
     *
     * @param name 字段名
     * @param type 数据类型
     * @param desc 字段描述
     * @return 字段 schema
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, String desc) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(type).description(desc).build();
    }

    /**
     * 构造主键字段 schema（自定主键，autoID=false）。
     *
     * @param name 字段名
     * @param type 数据类型
     * @param maxLength VarChar 最大长度
     * @param primaryKey 是否主键
     * @param desc 字段描述
     * @return 主键字段 schema
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, int maxLength, boolean primaryKey, String desc) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(type).maxLength(maxLength)
                .isPrimaryKey(primaryKey).autoID(false).description(desc).build();
    }

    /**
     * 构造向量字段 schema（FloatVector）。
     *
     * @param name 字段名
     * @param dimension 向量维度
     * @param desc 字段描述
     * @return 向量字段 schema
     */
    private static CreateCollectionReq.FieldSchema vecField(String name, int dimension, String desc) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.FloatVector).dimension(dimension).description(desc).build();
    }

    /**
     * 构造 JSON 类型字段 schema。
     *
     * @param name 字段名
     * @param desc 字段描述
     * @return JSON 字段 schema
     */
    private static CreateCollectionReq.FieldSchema jsonField(String name, String desc) {
        return CreateCollectionReq.FieldSchema.builder().name(name).dataType(DataType.JSON).description(desc).build();
    }

    /**
     * 确保目标集合存在：已有非空集合则跳过，已有空集合则先重建，均未创建则新建。
     *
     * @param name 集合名称
     * @param description 集合描述
     * @param schema 集合 schema
     * @param indexParams 索引参数列表
     */
    private void ensureCollection(String name, String description, CreateCollectionReq.CollectionSchema schema, List<IndexParam> indexParams) {
        if (milvusOps.hasCollection(name) && milvusCollectionService != null && milvusCollectionService.getCollectionCount(name) > 0) return;
        if (milvusOps.hasCollection(name)) { milvusOps.dropCollection(name); }
        milvusOps.createCollection(name, description, schema, indexParams);
    }
}
