package io.yunxi.platform.framework.sync.config;

import java.util.List;
import java.util.Map;

/**
 * 同步管道配置模型
 *
 * <p>描述一条 MySQL → Milvus 数据同步管道的完整配置，从 YAML 文件加载。</p>
 *
 * <pre>
 * sync-pipelines:
 *   - name: school-dish-sync
 *     source:
 *       datasource: school_db
 *       sql: "SELECT d.id, d.name, d.category, n.protein, n.fat, n.carb FROM dishes d JOIN nutrients n ON d.id = n.dish_id"
 *       incremental:
 *         column: updated_at
 *         initial-value: "2020-01-01"
 *     transform:
 *       - type: concat
 *         field: embedding_text
 *         template: "菜品：{name}，分类：{category}，蛋白质：{protein}g，脂肪：{fat}g"
 *       - type: enrich
 *         field: category
 *         map: { "1": "荤菜", "2": "素菜", "3": "汤品" }
 *     target:
 *       collection: dish_vectors
 *       primaryKey: id
 *       fields:
 *         - name: id
 *           type: Int64
 *           primaryKey: true
 *         - name: name
 *           type: VarChar
 *         - name: category
 *           type: VarChar
 *         - name: embedding_text
 *           type: VarChar
 *         - name: embedding
 *           type: FloatVector
 *           dimension: 1024
 *       index:
 *         field: embedding
 *         metricType: COSINE
 * </pre>
 *
 * @author yunxi-agent-platform
 */
public class SyncPipelineConfig {

    /** 管道名称 */
    private String name;

    /** 数据源配置 */
    private SourceConfig source;

    /** 转换配置列表 */
    private List<TransformConfig> transform;

    /** 目标 Milvus 配置 */
    private TargetConfig target;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public SourceConfig getSource() { return source; }
    public void setSource(SourceConfig source) { this.source = source; }

    public List<TransformConfig> getTransform() { return transform; }
    public void setTransform(List<TransformConfig> transform) { this.transform = transform; }

    public TargetConfig getTarget() { return target; }
    public void setTarget(TargetConfig target) { this.target = target; }

    /** 数据源配置 */
    public static class SourceConfig {
        /** 数据源名称（对应 Spring 的 datasource 配置） */
        private String datasource;
        /** SQL 查询语句 */
        private String sql;
        /** 增量同步配置 */
        private IncrementalConfig incremental;

        public String getDatasource() { return datasource; }
        public void setDatasource(String datasource) { this.datasource = datasource; }
        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }
        public IncrementalConfig getIncremental() { return incremental; }
        public void setIncremental(IncrementalConfig incremental) { this.incremental = incremental; }
    }

    /** 增量同步配置 */
    public static class IncrementalConfig {
        /** 增量列名（如 updated_at） */
        private String column;
        /** 初始值（如 "2020-01-01"） */
        private String initialValue;

        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }
        public String getInitialValue() { return initialValue; }
        public void setInitialValue(String initialValue) { this.initialValue = initialValue; }
    }

    /** 转换步骤配置 */
    public static class TransformConfig {
        /** 转换类型: concat / enrich / filter */
        private String type;
        /** 目标字段名 */
        private String field;
        /** 拼接模板（concat 类型使用） */
        private String template;
        /** 值映射（enrich 类型使用） */
        private Map<String, String> map;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getTemplate() { return template; }
        public void setTemplate(String template) { this.template = template; }
        public Map<String, String> getMap() { return map; }
        public void setMap(Map<String, String> map) { this.map = map; }
    }

    /** 目标 Milvus 配置 */
    public static class TargetConfig {
        /** 集合名称 */
        private String collection;
        /** 集合中文描述（如"菜品向量库"），创建 Milvus Schema 时使用 */
        private String collectionDescription;
        /** 主键字段名 */
        private String primaryKey;
        /** 字段定义列表 */
        private List<FieldConfig> fields;
        /** 索引配置 */
        private IndexConfig index;

        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public String getCollectionDescription() { return collectionDescription; }
        public void setCollectionDescription(String collectionDescription) { this.collectionDescription = collectionDescription; }
        public String getPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(String primaryKey) { this.primaryKey = primaryKey; }
        public List<FieldConfig> getFields() { return fields; }
        public void setFields(List<FieldConfig> fields) { this.fields = fields; }
        public IndexConfig getIndex() { return index; }
        public void setIndex(IndexConfig index) { this.index = index; }
    }

    /** 字段配置 */
    public static class FieldConfig {
        private String name;
        private String type;
        private boolean primaryKey;
        private Integer dimension;
        /** 字段中文描述（如"菜品名称"、"食材分类"），创建 Milvus Schema 时使用 */
        private String description;
        /** 字符串字段最大长度（默认 256） */
        private Integer maxLength = 256;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public boolean isPrimaryKey() { return primaryKey; }
        public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
        public Integer getDimension() { return dimension; }
        public void setDimension(Integer dimension) { this.dimension = dimension; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getMaxLength() { return maxLength; }
        public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }
    }

    /** 索引配置 */
    public static class IndexConfig {
        private String field;
        private String metricType;

        public String getField() { return field; }
        public void setField(String field) { this.field = field; }
        public String getMetricType() { return metricType; }
        public void setMetricType(String metricType) { this.metricType = metricType; }
    }
}