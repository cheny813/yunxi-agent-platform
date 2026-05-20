package io.yunxi.platform.infra.file;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import io.yunxi.platform.framework.embedding.EmbeddingService;
import io.yunxi.platform.infra.config.MilvusConfig;
import io.yunxi.platform.infra.file.dto.FileSearchRequest;
import io.yunxi.platform.infra.file.dto.FileSearchResult;
import io.yunxi.platform.infra.file.dto.ImageFeatureResult;
import io.yunxi.platform.shared.entity.UserFileEntity;
import io.yunxi.platform.shared.mapper.UserFileMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 文件向量化和检索服务
 *
 * <p>
 * 支持两种向量类型：
 * 1. 文本内容向量 - 用于OCR文字内容的语义搜索
 * 2. 图像特征向量 - 用于人脸识别、工装识别等视觉相似度搜索
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true")
public class FileVectorService {

        /** Milvus 客户端 */
        @Autowired
        private ObjectProvider<io.milvus.v2.client.MilvusClientV2> milvusClientProvider;

        /** Milvus 配置 */
        @Autowired
        private ObjectProvider<MilvusConfig> milvusConfigProvider;

        /** 嵌入服务 */
        @Autowired
        private ObjectProvider<EmbeddingService> embeddingServiceProvider;

        /** 用户文件 Mapper */
        @Autowired
        private UserFileMapper userFileMapper;

        /** JSON 序列化工具 */
        private final Gson gson = new Gson();

        // 集合名称常量
        /** 文件内容向量集合名称 */
        private static final String COLLECTION_FILE_CONTENT = "file_content";
        /** 图像特征向量集合名称 */
        private static final String COLLECTION_IMAGE_FEATURE = "file_image_feature";

        // 字段名称常量
        private static final String FIELD_ID = "id";
        private static final String FIELD_USER_ID = "userId";
        private static final String FIELD_FILE_ID = "fileId";
        private static final String FIELD_FILE_TYPE = "fileType";
        private static final String FIELD_FILE_NAME = "fileName";
        private static final String FIELD_CONTENT = "content";
        private static final String FIELD_METADATA = "metadata";
        private static final String FIELD_EMBEDDING = "embedding";
        private static final String FIELD_CREATED_AT = "createdAt";

        // 图像特征集合字段
        private static final String FIELD_IMAGE_TYPE = "imageType";
        private static final String FIELD_FEATURE_DIMENSION = "featureDimension";
        private static final String FIELD_FEATURE_MODEL = "featureModel";

        /** 默认图像特征维度 */
        @Value("${file-upload.image-feature.dimension:512}")
        private int defaultFeatureDimension;

        /**
         * 保存文件内容向量
         *
         * @param file    文件信息
         * @param content 文本内容
         */
        public void saveFileVector(UserFileEntity file, String content) {
                if (milvusClientProvider.getIfAvailable() == null) {
                        log.warn("Milvus 客户端未初始化，跳过文件向量化");
                        return;
                }

                try {
                        List<Float> embedding = embeddingServiceProvider.getIfAvailable().embed(content);
                        if (embedding == null || embedding.isEmpty()) {
                                log.error("向量化失败: fileId={}", file.getId());
                                return;
                        }

                        JsonObject data = new JsonObject();
                        data.addProperty(FIELD_ID, UUID.randomUUID().toString());
                        data.addProperty(FIELD_USER_ID, file.getUserId());
                        data.addProperty(FIELD_FILE_ID, file.getId());
                        data.addProperty(FIELD_FILE_TYPE, file.getFileType().getCode());
                        data.addProperty(FIELD_FILE_NAME, file.getFileName());
                        data.addProperty(FIELD_CONTENT, content);

                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("fileSize", file.getFileSize());
                        metadata.put("uploadTime", System.currentTimeMillis());
                        if (file.getMetadata() != null) {
                                try {
                                        Map<String, Object> originalMetadata = gson.fromJson(
                                                        file.getMetadata(),
                                                        new TypeToken<Map<String, Object>>() {
                                                        }.getType());
                                        metadata.putAll(originalMetadata);
                                } catch (Exception e) {
                                        log.warn("解析文件元数据失败: fileId={}", file.getId(), e);
                                }
                        }
                        data.add(FIELD_METADATA, gson.toJsonTree(metadata));
                        data.add(FIELD_EMBEDDING, gson.toJsonTree(embedding));
                        data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                        io.milvus.v2.service.vector.request.InsertReq insertReq = io.milvus.v2.service.vector.request.InsertReq
                                        .builder()
                                        .collectionName(COLLECTION_FILE_CONTENT)
                                        .data(Collections.singletonList(data))
                                        .build();

                        milvusClientProvider.getIfAvailable().insert(insertReq);
                        log.info("文件内容向量化成功: fileId={}, vectorDimension={}", file.getId(), embedding.size());

                } catch (Exception e) {
                        log.error("文件向量化失败: fileId={}", file.getId(), e);
                }
        }

        /**
         * 检索相关文件内容
         *
         * @param request 检索请求
         * @return 检索结果列表
         */
        public List<FileSearchResult> searchRelevantFiles(FileSearchRequest request) {
                if (milvusClientProvider.getIfAvailable() == null) {
                        log.warn("Milvus 客户端未初始化，返回空结果");
                        return Collections.emptyList();
                }

                try {
                        List<Float> queryEmbedding = embeddingServiceProvider.getIfAvailable()
                                        .embed(request.getQuery());
                        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                                log.error("查询向量化失败");
                                return Collections.emptyList();
                        }

                        List<String> outputFields = Arrays.asList(
                                        FIELD_FILE_ID, FIELD_FILE_NAME, FIELD_FILE_TYPE,
                                        FIELD_CONTENT, FIELD_METADATA, FIELD_CREATED_AT);

                        SearchReq searchReq = SearchReq.builder()
                                        .collectionName(COLLECTION_FILE_CONTENT)
                                        .data(Collections.singletonList(
                                                        new io.milvus.v2.service.vector.request.data.FloatVec(
                                                                        queryEmbedding)))
                                        .topK(request.getTopK())
                                        .outputFields(outputFields)
                                        .build();

                        SearchResp searchResp = milvusClientProvider.getIfAvailable().search(searchReq);
                        List<FileSearchResult> results = parseSearchResults(searchResp, request);

                        log.info("文件检索完成: userId={}, query={}, resultCount={}",
                                        request.getUserId(), request.getQuery(), results.size());

                        return results;

                } catch (Exception e) {
                        log.error("文件检索失败: userId={}, query={}", request.getUserId(), request.getQuery(), e);
                        return Collections.emptyList();
                }
        }

        /**
         * 解析检索结果
         *
         * @param searchResp Milvus 搜索响应
         * @param request    检索请求
         * @return 检索结果列表
         */
        private List<FileSearchResult> parseSearchResults(SearchResp searchResp, FileSearchRequest request) {
                List<FileSearchResult> results = new ArrayList<>();
                log.debug("解析Milvus检索结果，当前实现待完善");
                return results;
        }

        /**
         * 删除文件向量
         *
         * @param fileId 文件ID
         */
        public void deleteFileVector(String fileId) {
                if (milvusClientProvider.getIfAvailable() == null) {
                        return;
                }
                log.warn("删除文件向量功能待完善: fileId={}", fileId);
        }

        /**
         * 保存图像特征向量
         *
         * @param file          文件信息
         * @param featureResult 特征提取结果
         */
        public void saveImageFeatureVector(UserFileEntity file, ImageFeatureResult featureResult) {
                if (milvusClientProvider.getIfAvailable() == null) {
                        log.warn("Milvus 客户端未初始化，跳过图像特征向量化");
                        return;
                }

                try {
                        List<Float> featureVector = featureResult.getFeatureVector();
                        if (featureVector == null || featureVector.isEmpty()) {
                                log.error("图像特征向量为空: fileId={}", file.getId());
                                return;
                        }

                        JsonObject data = new JsonObject();
                        data.addProperty(FIELD_ID, UUID.randomUUID().toString());
                        data.addProperty(FIELD_USER_ID, file.getUserId());
                        data.addProperty(FIELD_FILE_ID, file.getId());
                        data.addProperty(FIELD_FILE_NAME, file.getFileName());
                        data.addProperty(FIELD_IMAGE_TYPE,
                                        file.getImageType() != null ? file.getImageType() : "general");
                        data.addProperty(FIELD_FEATURE_DIMENSION, featureResult.getDimension());
                        data.addProperty(FIELD_FEATURE_MODEL, featureResult.getModelName());
                        data.add(FIELD_EMBEDDING, gson.toJsonTree(featureVector));
                        data.addProperty(FIELD_CREATED_AT, System.currentTimeMillis());

                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("fileSize", file.getFileSize());
                        metadata.put("uploadTime", System.currentTimeMillis());
                        if (featureResult.getMetadata() != null) {
                                metadata.putAll(featureResult.getMetadata());
                        }
                        data.add(FIELD_METADATA, gson.toJsonTree(metadata));

                        io.milvus.v2.service.vector.request.InsertReq insertReq = io.milvus.v2.service.vector.request.InsertReq
                                        .builder()
                                        .collectionName(COLLECTION_IMAGE_FEATURE)
                                        .data(Collections.singletonList(data))
                                        .build();

                        milvusClientProvider.getIfAvailable().insert(insertReq);
                        log.info("图像特征向量保存成功: fileId={}, imageType={}, dimension={}",
                                        file.getId(), file.getImageType(), featureVector.size());

                } catch (Exception e) {
                        log.error("图像特征向量保存失败: fileId={}", file.getId(), e);
                }
        }

        /**
         * 搜索相似图像特征
         *
         * @param userId        用户ID
         * @param imageType     图像类型
         * @param featureVector 特征向量
         * @param topK          返回数量
         * @return 相似图像特征列表
         */
        public List<Map<String, Object>> searchSimilarImageFeatures(String userId, String imageType,
                        List<Float> featureVector, int topK) {
                if (milvusClientProvider.getIfAvailable() == null) {
                        log.warn("Milvus 客户端未初始化，返回空结果");
                        return Collections.emptyList();
                }

                try {
                        List<String> outputFields = Arrays.asList(
                                        FIELD_FILE_ID, FIELD_FILE_NAME, FIELD_IMAGE_TYPE,
                                        FIELD_FEATURE_MODEL, FIELD_METADATA, FIELD_CREATED_AT);

                        SearchReq searchReq = SearchReq.builder()
                                        .collectionName(COLLECTION_IMAGE_FEATURE)
                                        .data(Collections.singletonList(
                                                        new io.milvus.v2.service.vector.request.data.FloatVec(
                                                                        featureVector)))
                                        .topK(topK)
                                        .outputFields(outputFields)
                                        .build();

                        SearchResp searchResp = milvusClientProvider.getIfAvailable().search(searchReq);
                        List<Map<String, Object>> results = parseImageFeatureSearchResults(searchResp);

                        log.info("图像特征搜索完成: userId={}, imageType={}, resultCount={}",
                                        userId, imageType, results.size());

                        return results;

                } catch (Exception e) {
                        log.error("图像特征搜索失败: userId={}, imageType={}", userId, imageType, e);
                        return Collections.emptyList();
                }
        }

        private List<Map<String, Object>> parseImageFeatureSearchResults(SearchResp searchResp) {
                List<Map<String, Object>> results = new ArrayList<>();
                log.debug("解析图像特征搜索结果，实现待完善");
                return results;
        }

        /**
         * 初始化集合
         */
        @jakarta.annotation.PostConstruct
        public void initCollection() {
                if (milvusClientProvider.getIfAvailable() == null || milvusConfigProvider.getIfAvailable() == null) {
                        return;
                }
                initFileContentCollection();
                initImageFeatureCollection();
        }

        /**
         * 初始化文件内容集合
         */
        private void initFileContentCollection() {
                try {
                        io.milvus.v2.service.collection.request.HasCollectionReq hasCollectionReq = io.milvus.v2.service.collection.request.HasCollectionReq
                                        .builder()
                                        .collectionName(COLLECTION_FILE_CONTENT)
                                        .build();

                        if (milvusClientProvider.getIfAvailable().hasCollection(hasCollectionReq)) {
                                log.info("文件内容集合已存在: {}", COLLECTION_FILE_CONTENT);
                                return;
                        }

                        log.info("开始创建文件内容集合: {}", COLLECTION_FILE_CONTENT);

                        int dimension = embeddingServiceProvider.getIfAvailable().getDimension();
                        log.info("文件内容集合向量维度: {} (来自 embeddingServiceProvider.getIfAvailable())", dimension);

                        // 定义字段结构
                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema idField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(36)
                                        .isPrimaryKey(true)
                                        .autoID(false)
                                        .description("主键ID，UUID格式")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema userIdField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_USER_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(100)
                                        .description("用户ID")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema fileIdField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FILE_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(36)
                                        .description("文件ID")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema fileTypeField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FILE_TYPE)
                                        .dataType(DataType.VarChar)
                                        .maxLength(20)
                                        .description("文件类型")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema fileNameField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FILE_NAME)
                                        .dataType(DataType.VarChar)
                                        .maxLength(500)
                                        .description("文件名称")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema contentField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_CONTENT)
                                        .dataType(DataType.VarChar)
                                        .maxLength(65535)
                                        .description("文件内容，如OCR提取的文字")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema metadataField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_METADATA)
                                        .dataType(DataType.VarChar)
                                        .maxLength(65535)
                                        .description("文件元数据JSON")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema embeddingField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_EMBEDDING)
                                        .dataType(DataType.FloatVector)
                                        .dimension(dimension)
                                        .description("内容向量")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema createdAtField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_CREATED_AT)
                                        .dataType(DataType.Int64)
                                        .description("创建时间戳")
                                        .build();

                        // 创建集合 Schema
                        io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema schema = io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema
                                        .builder()
                                        .fieldSchemaList(Arrays.asList(
                                                        idField, userIdField, fileIdField, fileTypeField,
                                                        fileNameField, contentField, metadataField,
                                                        embeddingField, createdAtField))
                                        .build();

                        // 创建索引参数
                        IndexParam indexParam = IndexParam.builder()
                                        .fieldName(FIELD_EMBEDDING)
                                        .indexType(IndexParam.IndexType.AUTOINDEX)
                                        .metricType(IndexParam.MetricType.COSINE)
                                        .build();

                        // 创建集合请求
                        io.milvus.v2.service.collection.request.CreateCollectionReq createReq = io.milvus.v2.service.collection.request.CreateCollectionReq
                                        .builder()
                                        .collectionName(COLLECTION_FILE_CONTENT)
                                        .description("文件内容向量存储")
                                        .collectionSchema(schema)
                                        .indexParams(Collections.singletonList(indexParam))
                                        .build();

                        milvusClientProvider.getIfAvailable().createCollection(createReq);
                        log.info("文件内容集合创建成功: {}, 维度: {}", COLLECTION_FILE_CONTENT, dimension);

                } catch (Exception e) {
                        log.error("创建文件内容集合失败: {}", COLLECTION_FILE_CONTENT, e);
                        log.warn("如果自动创建失败，请手动创建Milvus集合 'file_content'");
                }
        }

        /**
         * 初始化图像特征集合
         */
        private void initImageFeatureCollection() {
                try {
                        io.milvus.v2.service.collection.request.HasCollectionReq hasCollectionReq = io.milvus.v2.service.collection.request.HasCollectionReq
                                        .builder()
                                        .collectionName(COLLECTION_IMAGE_FEATURE)
                                        .build();

                        if (milvusClientProvider.getIfAvailable().hasCollection(hasCollectionReq)) {
                                log.info("图像特征集合已存在: {}", COLLECTION_IMAGE_FEATURE);
                                return;
                        }

                        log.info("开始创建图像特征集合: {}", COLLECTION_IMAGE_FEATURE);

                        int dimension = defaultFeatureDimension;
                        log.info("图像特征集合向量维度: {}", dimension);

                        // 定义字段结构
                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema idField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(36)
                                        .isPrimaryKey(true)
                                        .autoID(false)
                                        .description("主键ID，UUID格式")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema userIdField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_USER_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(100)
                                        .description("用户ID")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema fileIdField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FILE_ID)
                                        .dataType(DataType.VarChar)
                                        .maxLength(36)
                                        .description("文件ID")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema fileNameField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FILE_NAME)
                                        .dataType(DataType.VarChar)
                                        .maxLength(500)
                                        .description("文件名称")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema imageTypeField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_IMAGE_TYPE)
                                        .dataType(DataType.VarChar)
                                        .maxLength(50)
                                        .description("图像类型(如face/general)")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema featureDimensionField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FEATURE_DIMENSION)
                                        .dataType(DataType.Int64)
                                        .description("特征向量维度")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema featureModelField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_FEATURE_MODEL)
                                        .dataType(DataType.VarChar)
                                        .maxLength(100)
                                        .description("特征提取模型名称")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema embeddingField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_EMBEDDING)
                                        .dataType(DataType.FloatVector)
                                        .dimension(dimension)
                                        .description("图像特征向量")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema metadataField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_METADATA)
                                        .dataType(DataType.VarChar)
                                        .maxLength(65535)
                                        .description("图像元数据JSON")
                                        .build();

                        io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema createdAtField = io.milvus.v2.service.collection.request.CreateCollectionReq.FieldSchema
                                        .builder()
                                        .name(FIELD_CREATED_AT)
                                        .dataType(DataType.Int64)
                                        .description("创建时间戳")
                                        .build();

                        // 创建集合 Schema
                        io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema schema = io.milvus.v2.service.collection.request.CreateCollectionReq.CollectionSchema
                                        .builder()
                                        .fieldSchemaList(Arrays.asList(
                                                        idField, userIdField, fileIdField, fileNameField,
                                                        imageTypeField, featureDimensionField, featureModelField,
                                                        embeddingField, metadataField, createdAtField))
                                        .build();

                        // 创建索引参数
                        IndexParam indexParam = IndexParam.builder()
                                        .fieldName(FIELD_EMBEDDING)
                                        .indexType(IndexParam.IndexType.AUTOINDEX)
                                        .metricType(IndexParam.MetricType.COSINE)
                                        .build();

                        // 创建集合请求
                        io.milvus.v2.service.collection.request.CreateCollectionReq createReq = io.milvus.v2.service.collection.request.CreateCollectionReq
                                        .builder()
                                        .collectionName(COLLECTION_IMAGE_FEATURE)
                                        .description("图像特征向量存储 - 用于人脸识别、工装识别等")
                                        .collectionSchema(schema)
                                        .indexParams(Collections.singletonList(indexParam))
                                        .build();

                        milvusClientProvider.getIfAvailable().createCollection(createReq);
                        log.info("图像特征集合创建成功: {}, 维度: {}", COLLECTION_IMAGE_FEATURE, dimension);

                } catch (Exception e) {
                        log.error("创建图像特征集合失败: {}", COLLECTION_IMAGE_FEATURE, e);
                        log.warn("如果自动创建失败，请手动创建Milvus集合 'file_image_feature'");
                }
        }
}
