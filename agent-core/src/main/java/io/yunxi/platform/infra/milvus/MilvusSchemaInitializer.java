package io.yunxi.platform.infra.milvus;

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
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.framework.sync.MilvusCollectionService;
import io.yunxi.platform.framework.sync.SyncEngine;
import io.yunxi.platform.framework.sync.config.SyncPipelineConfig;
import io.yunxi.platform.infra.config.MilvusConfig;
import jakarta.annotation.PostConstruct;

/**
 * Milvus 统一集合初始化器
 *
 * <p>
 * 启动时创建所有 Milvus 集合，包括：
 * </p>
 * <ul>
 * <li>文件内容向量集合 {@code file_content}</li>
 * <li>图像特征向量集合 {@code file_image_feature}</li>
 * <li>会话记忆集合 {@code conversation_memory}</li>
 * <li>用户记忆集合 {@code user_memory}</li>
 * <li>10 个数据同步集合（从 sync-pipelines.yml 读取）</li>
 * </ul>
 *
 * <p>
 * 替代原先分散在 {@code FileVectorService}、{@code MilvusVectorPersistenceStrategy}、
 * {@code SyncEngine} 三处的集合创建逻辑。
 * </p>
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

    // 文件集合字段名
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

    // 记忆集合字段名
    private static final String FIELD_CONVERSATION_ID = "conversationId";
    private static final String FIELD_CONTENT_TYPE = "contentType";

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

    @Override
    @PostConstruct
    public void afterPropertiesSet() {
        if (!milvusOps.isAvailable()) {
            log.warn("MilvusSchemaInitializer: Milvus 不可用，跳过集合初始化");
            return;
        }

        log.info("========== MilvusSchemaInitializer: 开始统一初始化集合 ==========");

        // 1. 基础设施集合
        ensureCollection("file_content", "文件内容向量存储", buildFileContentSchema(), buildAutoIndexParams());
        ensureCollection("file_image_feature", "图像特征向量存储", buildFileImageFeatureSchema(), buildAutoIndexParams());
        ensureCollection("conversation_memory", "会话记忆数据", buildMemorySchema("conversation"),
                buildIvfFlatIndexParams());
        ensureCollection("user_memory", "用户记忆数据", buildMemorySchema("user"),
                buildIvfFlatIndexParams());

        // 2. 数据同步集合（从 sync-pipelines.yml 读取）
        int pipelineCount = 0;
        for (SyncPipelineConfig cfg : pipelineConfigs) {
            String collectionName = cfg.getTarget().getCollection();
            String desc = cfg.getTarget().getCollectionDescription();

            List<CreateCollectionReq.FieldSchema> fieldSchemas = cfg.getTarget().getFields().stream()
                    .map(SyncEngine::buildFieldSchema)
                    .collect(Collectors.toList());

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                    .fieldSchemaList(fieldSchemas)
                    .build();

            List<IndexParam> indexParams = new ArrayList<>();
            if (cfg.getTarget().getIndex() != null) {
                indexParams.add(IndexParam.builder()
                        .fieldName(cfg.getTarget().getIndex().getField())
                        .metricType(IndexParam.MetricType.valueOf(
                                cfg.getTarget().getIndex().getMetricType()))
                        .build());
            }

            ensureCollection(collectionName, desc, schema, indexParams);
            pipelineCount++;
        }

        log.info("========== MilvusSchemaInitializer: 集合初始化完成，共 {} 个集合（4 基础设施 + {} 数据同步） ==========",
                4 + pipelineCount, pipelineCount);
    }

    // ==================== 基础设施集合 Schema 构建 ====================

    /**
     * 构建 file_content 集合 Schema
     */
    private CreateCollectionReq.CollectionSchema buildFileContentSchema() {
        int dimension = embeddingService.getDimension();

        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field(FIELD_ID, DataType.VarChar, 36, true, "主键ID，UUID格式"),
                        field(FIELD_USER_ID, DataType.VarChar, 100, "用户ID"),
                        field(FIELD_FILE_ID, DataType.VarChar, 36, "文件ID"),
                        field(FIELD_FILE_TYPE, DataType.VarChar, 20, "文件类型"),
                        field(FIELD_FILE_NAME, DataType.VarChar, 500, "文件名称"),
                        field(FIELD_CONTENT, DataType.VarChar, 65535, "文件内容，如OCR提取的文字"),
                        field(FIELD_METADATA, DataType.VarChar, 65535, "文件元数据JSON"),
                        vecField(FIELD_EMBEDDING, dimension, "内容向量"),
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间戳")))
                .build();
    }

    /**
     * 构建 file_image_feature 集合 Schema
     */
    private CreateCollectionReq.CollectionSchema buildFileImageFeatureSchema() {
        int dimension = 512; // 默认图像特征维度，对应 file-upload.image-feature.dimension

        return CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(
                        field(FIELD_ID, DataType.VarChar, 36, true, "主键ID，UUID格式"),
                        field(FIELD_USER_ID, DataType.VarChar, 100, "用户ID"),
                        field(FIELD_FILE_ID, DataType.VarChar, 36, "文件ID"),
                        field(FIELD_FILE_NAME, DataType.VarChar, 500, "文件名称"),
                        field(FIELD_IMAGE_TYPE, DataType.VarChar, 50, "图像类型(如face/general)"),
                        field(FIELD_FEATURE_DIMENSION, DataType.Int64, "特征向量维度"),
                        field(FIELD_FEATURE_MODEL, DataType.VarChar, 100, "特征提取模型名称"),
                        vecField(FIELD_EMBEDDING, dimension, "图像特征向量"),
                        field(FIELD_METADATA, DataType.VarChar, 65535, "图像元数据JSON"),
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间戳")))
                .build();
    }

    /**
     * 构建 memory 集合 Schema（conversation_memory / user_memory 共用）
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
                        field(FIELD_CREATED_AT, DataType.Int64, "创建时间戳")))
                .build();
    }

    // ==================== 索引参数 ====================

    private List<IndexParam> buildAutoIndexParams() {
        return Collections.singletonList(IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build());
    }

    private List<IndexParam> buildIvfFlatIndexParams() {
        return Collections.singletonList(IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.IP)
                .extraParams(Map.of("nlist", String.valueOf(milvusConfig.getEmbedding().getNlist())))
                .build());
    }

    // ==================== 字段构建助手 ====================

    /**
     * 普通字段（非主键）
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, int maxLength, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).maxLength(maxLength).description(desc)
                .build();
    }

    /**
     * 普通字段（Int64 等无需 maxLength）
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).description(desc)
                .build();
    }

    /**
     * 主键字段
     */
    private static CreateCollectionReq.FieldSchema field(String name, DataType type, int maxLength,
            boolean primaryKey, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(type).maxLength(maxLength)
                .isPrimaryKey(primaryKey).autoID(false).description(desc)
                .build();
    }

    /**
     * 向量字段
     */
    private static CreateCollectionReq.FieldSchema vecField(String name, int dimension, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(DataType.FloatVector).dimension(dimension).description(desc)
                .build();
    }

    /**
     * JSON 字段
     */
    private static CreateCollectionReq.FieldSchema jsonField(String name, String desc) {
        return CreateCollectionReq.FieldSchema.builder()
                .name(name).dataType(DataType.JSON).description(desc)
                .build();
    }

    // ==================== 集合创建 ====================

    private void ensureCollection(String name, String description,
            CreateCollectionReq.CollectionSchema schema,
            List<IndexParam> indexParams) {
        // 集合已存在且有数据则跳过（保留已有数据）
        if (milvusOps.hasCollection(name) && milvusCollectionService != null
                && milvusCollectionService.getCollectionCount(name) > 0) {
            log.debug("  集合已存在且有数据: {}", name);
            return;
        }
        // 集合已存在但无数据：删除重建（确保最新的字段描述生效）
        if (milvusOps.hasCollection(name)) {
            log.info("  集合 {} 已存在但无数据，重建以应用字段描述", name);
            milvusOps.dropCollection(name);
        }
        milvusOps.createCollection(name, description, schema, indexParams);
        log.info("  创建集合: {} - {}", name, description);
    }
}
