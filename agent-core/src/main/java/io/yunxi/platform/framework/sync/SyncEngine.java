package io.yunxi.platform.framework.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.yunxi.platform.framework.sync.config.SyncPipelineConfig;
import io.yunxi.platform.framework.sync.config.SyncPipelineConfig.*;
import io.yunxi.platform.infra.milvus.MilvusOperations;
import io.yunxi.platform.shared.entity.SyncCursorEntity;
import io.yunxi.platform.shared.mapper.SyncCursorMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通用数据同步引擎
 *
 * <p>
 * 读取 YAML 配置的 SyncPipeline，执行 MySQL → Milvus 的 ETL 流程。
 * 替代 agent-business 中多个手工编写的 SyncHandler 实现。
 * </p>
 *
 * <p>
 * 执行流程：
 * </p>
 * <ol>
 * <li>读取管道配置（YAML）</li>
 * <li>执行 SQL 查询（通过 MCP 或 JDBC）</li>
 * <li>执行转换（文本拼接 / 值映射 / 过滤）</li>
 * <li>文本向量化（批量 embedding）</li>
 * <li>创建/校验 Milvus 集合 Schema</li>
 * <li>批量 upsert 到 Milvus</li>
 * <li>记录同步游标（增量场景）</li>
 * </ol>
 *
 * @author yunxi-agent-platform
 */
@Component
public class SyncEngine implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    /** MCP 数据库查询服务 */
    @Autowired(required = false)
    private McpQueryService mcpQueryService;

    /** Milvus 集合管理服务 */
    @Autowired(required = false)
    private MilvusCollectionService milvusCollectionService;

    /** 批量嵌入服务 */
    @Autowired(required = false)
    private EmbeddingBatchService embeddingBatchService;

    /** Milvus 操作门面 */
    @Autowired(required = false)
    private MilvusOperations milvusOps;

    /** 同步游标持久化 Mapper */
    @Autowired(required = false)
    private SyncCursorMapper syncCursorMapper;

    /**
     * 管道配置列表（由 SyncPipelineConfigLoader 注入）
     */
    private List<SyncPipelineConfig> pipelineConfigs = new ArrayList<>();

    /** MCP 数据库主机地址 */
    @Value("${static-sync.mcp-database.host:localhost}")
    private String mcpDbHost;

    /** MCP 数据库端口号 */
    @Value("${static-sync.mcp-database.port:40101}")
    private int mcpDbPort;

    /** 批量同步的批次大小 */
    @Value("${static-sync.batch-size:100}")
    private int batchSize;

    /** JSON 映射器 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 注入管道配置列表
     * <p>
     * 由 {@link io.yunxi.platform.framework.sync.config.SyncPipelineConfigLoader}
     * 在初始化时调用。
     * </p>
     */
    public void setPipelineConfigs(List<SyncPipelineConfig> configs) {
        if (configs != null) {
            this.pipelineConfigs = configs;
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (pipelineConfigs.isEmpty()) {
            log.info("SyncEngine: 未配置同步管道，跳过");
            return;
        }
        log.info("SyncEngine: 已加载 {} 条同步管道配置", pipelineConfigs.size());
        for (SyncPipelineConfig cfg : pipelineConfigs) {
            log.info("  pipeline: {} → {}.{}", cfg.getName(), cfg.getTarget().getCollection(),
                    cfg.getSource().getDatasource());
        }
    }

    /**
     * 执行所有管道同步
     */
    public void syncAll() {
        for (SyncPipelineConfig cfg : pipelineConfigs) {
            try {
                executePipeline(cfg);
            } catch (Exception e) {
                log.error("管道同步失败: {}", cfg.getName(), e);
            }
        }
    }

    /**
     * 执行单个管道同步
     */
    public void executePipeline(SyncPipelineConfig cfg) {
        String pipelineName = cfg.getName();
        String collectionName = cfg.getTarget().getCollection();
        IncrementalConfig incremental = cfg.getSource().getIncremental();

        // 增量管道：始终执行，游标控制同步范围
        // 非增量管道：集合有数据则跳过（只全量同步一次）
        if (incremental == null || incremental.getColumn() == null) {
            if (milvusCollectionService != null) {
                long count = milvusCollectionService.getCollectionCount(collectionName);
                if (count < 0) {
                    log.warn("集合 {} 计数失败（返回值={}），跳过同步管道: {}（保守策略）",
                            collectionName, count, pipelineName);
                    return;
                }
                if (count > 0) {
                    log.info("集合 {} 已有 {} 条数据，跳过同步管道: {}（非增量模式）",
                            collectionName, count, pipelineName);
                    return;
                }
            }
        } else {
            log.info("增量同步管道: {}（游标列: {}）", pipelineName, incremental.getColumn());
        }

        log.info("开始执行同步管道: {}", pipelineName);

        // 1. 构建 SQL（支持增量）
        String sql = buildSql(cfg);
        log.debug("  SQL: {}", sql);

        // 2. 执行查询（通过 MCP，传入 datasource 作为 db_id）
        String jsonResult = mcpQueryService != null
                ? mcpQueryService.callMcpDatabase(mcpDbHost, mcpDbPort,
                        cfg.getSource().getDatasource(), sql, 10000)
                : "[]";
        List<Map<String, Object>> rows = parseJsonResult(jsonResult);
        log.info("  查询到 {} 条记录", rows.size());

        if (rows.isEmpty()) {
            return;
        }

        // 3. 执行转换
        List<Map<String, Object>> transformed = applyTransforms(rows, cfg.getTransform());

        // 4. 构建 embedding 文本并向量化
        List<String> embedTexts = extractEmbedTexts(transformed);
        List<List<Float>> vectors = embeddingBatchService != null && !embedTexts.isEmpty()
                ? embeddingBatchService.embedBatchWithRetry(embedTexts)
                : Collections.emptyList();

        // 5. 准备 Milvus 数据
        List<JsonObject> milvusData = buildMilvusData(transformed, vectors, cfg);

        // 6. 批量 upsert（集合已由 MilvusSchemaInitializer 统一创建）
        if (milvusCollectionService != null && !milvusData.isEmpty()) {
            milvusCollectionService.upsertBatch(cfg.getTarget().getCollection(), milvusData, batchSize);
            log.info("  同步完成: pipeline={}, inserted={}", pipelineName, milvusData.size());
        }

        // 8. 更新同步游标
        updateCursor(cfg);
    }

    /**
     * 构建 SQL（支持增量）
     * <p>
     * 从数据库读取上次同步游标，追加增量过滤条件。
     * SQL 中已有 WHERE 时自动使用 AND，否则使用 WHERE。
     * </p>
     */
    private String buildSql(SyncPipelineConfig cfg) {
        String baseSql = cfg.getSource().getSql();
        IncrementalConfig incremental = cfg.getSource().getIncremental();
        if (incremental != null && incremental.getColumn() != null) {
            String lastCursor = readCursor(cfg.getName(), incremental.getInitialValue());
            if (lastCursor != null) {
                String condition = incremental.getColumn() + " > '" + lastCursor + "'";
                // 自动判断使用 WHERE 还是 AND
                if (baseSql.matches("(?si).*\\bWHERE\\b.*")) {
                    baseSql += " AND " + condition;
                } else {
                    baseSql += " WHERE " + condition;
                }
                baseSql += " ORDER BY " + incremental.getColumn() + " ASC";
            }
        }
        return baseSql;
    }

    /**
     * 解析 MCP 查询返回的 JSON 结果
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonResult(String json) {
        try {
            if (json == null || json.isBlank() || "[]".equals(json.trim())) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("解析查询结果失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 执行数据转换
     */
    private List<Map<String, Object>> applyTransforms(List<Map<String, Object>> rows,
            List<TransformConfig> transforms) {
        if (transforms == null || transforms.isEmpty()) {
            return rows;
        }
        return rows.stream().map(row -> {
            Map<String, Object> result = new LinkedHashMap<>(row);
            for (TransformConfig tc : transforms) {
                switch (tc.getType()) {
                    case "concat":
                        // 模板拼接: "菜品：{name}，分类：{category}"
                        if (tc.getTemplate() != null) {
                            String text = tc.getTemplate();
                            for (Map.Entry<String, Object> e : row.entrySet()) {
                                text = text.replace("{" + e.getKey() + "}",
                                        e.getValue() != null ? e.getValue().toString() : "");
                            }
                            result.put(tc.getField(), text);
                        }
                        break;
                    case "enrich":
                        // 值映射: { "1": "荤菜", "2": "素菜" }
                        if (tc.getMap() != null && tc.getField() != null) {
                            Object val = row.get(tc.getField());
                            if (val != null && tc.getMap().containsKey(val.toString())) {
                                result.put(tc.getField(), tc.getMap().get(val.toString()));
                            }
                        }
                        break;
                    case "filter":
                        // 过滤字段（保留指定字段）
                        if (tc.getField() != null) {
                            result.keySet().retainAll(Set.of(tc.getField().split(",")));
                        }
                        break;
                    default:
                        log.warn("未知转换类型: {}", tc.getType());
                }
            }
            return result;
        }).collect(Collectors.toList());
    }

    /**
     * 提取用于 embedding 的文本列表
     */
    private List<String> extractEmbedTexts(List<Map<String, Object>> rows) {
        return rows.stream()
                .map(row -> row.containsKey("embedding_text") ? row.get("embedding_text").toString() : "")
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * 构建 Milvus 插入数据
     * <p>
     * 将转换后的行数据 + 向量转换为 Milvus SDK 所需的 JsonObject 列表。
     * </p>
     */
    private List<JsonObject> buildMilvusData(List<Map<String, Object>> rows, List<List<Float>> vectors,
            SyncPipelineConfig cfg) {
        List<JsonObject> dataList = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            JsonObject item = new JsonObject();

            // 添加向量（如果有）
            if (i < vectors.size() && !vectors.get(i).isEmpty()) {
                item.add("embedding", gsonVector(vectors.get(i)));
            }

            // 添加其他字段
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (val instanceof Number) {
                    item.addProperty(key, (Number) val);
                } else if (val instanceof Boolean) {
                    item.addProperty(key, (Boolean) val);
                } else {
                    item.addProperty(key, val != null ? val.toString() : "");
                }
            }
            dataList.add(item);
        }
        return dataList;
    }

    /**
     * 确保 Milvus 集合存在，不存在时自动创建（含中文描述）
     */
    private void ensureCollection(SyncPipelineConfig cfg) {
        if (milvusCollectionService == null && milvusOps == null)
            return;

        String collection = cfg.getTarget().getCollection();
        if (milvusCollectionService.isCollectionExists(collection)) {
            log.debug("  集合已存在: {}", collection);
            return;
        }

        log.info("  集合不存在，自动创建: {} ({})", collection, cfg.getTarget().getCollectionDescription());

        // 从配置构建 Schema
        List<CreateCollectionReq.FieldSchema> fieldSchemas = cfg.getTarget().getFields().stream()
                .map(SyncEngine::buildFieldSchema)
                .collect(Collectors.toList());

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(fieldSchemas)
                .build();

        // 索引配置
        List<IndexParam> indexParams = new ArrayList<>();
        IndexConfig idxCfg = cfg.getTarget().getIndex();
        if (idxCfg != null) {
            indexParams.add(IndexParam.builder()
                    .fieldName(idxCfg.getField())
                    .metricType(IndexParam.MetricType.valueOf(idxCfg.getMetricType()))
                    .build());
        }

        // 创建集合
        if (milvusOps != null) {
            milvusOps.createCollection(collection, schema, indexParams);
            log.info("  自动创建集合完成: {}", collection);
        }
    }

    /**
     * 从配置构建 Milvus FieldSchema（支持中文 description）
     */
    public static CreateCollectionReq.FieldSchema buildFieldSchema(FieldConfig fc) {
        DataType dt = mapDataType(fc.getType());
        var builder = CreateCollectionReq.FieldSchema.builder()
                .name(fc.getName())
                .dataType(dt)
                .autoID(false);
        if (fc.isPrimaryKey()) {
            builder.isPrimaryKey(true);
        }
        if (fc.getDescription() != null) {
            builder.description(fc.getDescription());
        }
        if (dt == DataType.FloatVector && fc.getDimension() != null) {
            builder.dimension(fc.getDimension());
        }
        if (dt == DataType.VarChar && fc.getMaxLength() != null) {
            builder.maxLength(fc.getMaxLength());
        }
        return builder.build();
    }

    /**
     * 将配置中的类型字符串映射为 DataType 枚举（公开静态方法，供 MilvusSchemaInitializer 复用）
     */
    public static DataType mapDataType(String type) {
        if (type == null)
            return DataType.VarChar;
        return switch (type.toUpperCase()) {
            case "INT64" -> DataType.Int64;
            case "INT32" -> DataType.Int32;
            case "FLOAT" -> DataType.Float;
            case "DOUBLE" -> DataType.Double;
            case "BOOLEAN" -> DataType.Bool;
            case "FLOATVECTOR" -> DataType.FloatVector;
            case "BINARYVECTOR" -> DataType.BinaryVector;
            default -> DataType.VarChar;
        };
    }

    /**
     * 读取持久化游标值
     * <p>
     * 从数据库读取该管道上次同步的游标；不存在时返回 initialValue（首次全量同步）。
     * </p>
     */
    private String readCursor(String pipelineName, String initialValue) {
        if (syncCursorMapper == null) {
            return initialValue;
        }
        try {
            SyncCursorEntity entity = syncCursorMapper.findByPipelineName(pipelineName);
            return entity != null ? entity.getCursorValue() : initialValue;
        } catch (Exception e) {
            log.warn("读取同步游标失败: pipeline={}, 使用初始值", pipelineName);
            return initialValue;
        }
    }

    /**
     * 更新同步游标到数据库（增量场景）
     */
    @Transactional
    protected void updateCursor(SyncPipelineConfig cfg) {
        IncrementalConfig incremental = cfg.getSource().getIncremental();
        if (incremental != null && incremental.getColumn() != null && syncCursorMapper != null) {
            String cursor = java.time.LocalDateTime.now().toString();
            try {
                syncCursorMapper.upsert(cfg.getName(), cursor, 0);
                log.debug("  持久化游标: pipeline={}, cursor={}", cfg.getName(), cursor);
            } catch (Exception e) {
                log.warn("持久化游标失败: pipeline={}", cfg.getName(), e);
            }
        }
    }

    /**
     * 将 List<Float> 转换为 Gson JsonArray（Milvus SDK 格式）
     */
    private com.google.gson.JsonArray gsonVector(List<Float> vector) {
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        for (Float v : vector) {
            arr.add(v);
        }
        return arr;
    }
}
