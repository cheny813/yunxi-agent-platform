package io.yunxi.platform.shared.util.database;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 数据库工具包统一入口
 * <p>
 * 提供一套完整的数据库开发、调试、优化工具：
 * <ul>
 *   <li>表结构查看和验证</li>
 *   <li>索引分析</li>
 *   <li>数据样本查询</li>
 *   <li>表关系映射</li>
 *   <li>SQL性能分析</li>
 *   <li>智能建议</li>
 * </ul>
 * </p>
 *
 * <p>使用示例：</p>
 * <pre>
 * // 初始化工具包
 * DatabaseToolkit toolkit = DatabaseToolkit.builder()
 *     .mcpHost("localhost")
 *     .mcpPort(40101)
 *     .build();
 *
 * // 查看表结构
 * TableSchema schema = toolkit.describeTable("dish_library");
 * log.info("表结构: {}", toolkit.formatSchema(schema));
 *
 * // 验证SQL
 * ValidationResult result = toolkit.validateSql(
 *     "SELECT id, name FROM dish_library WHERE deleted = 0");
 * if (!result.isValid()) {
 *     log.error("SQL错误: {}", result.getErrors());
 * }
 * </pre>
 *
 * @author yunxi-agent-platform
 * @version 2.0.0
 */
@Slf4j
public class DatabaseToolkit {

    private final String mcpHost;
    private final int mcpPort;
    private final RestTemplate restTemplate;
    private final SchemaInspector inspector;
    private final SqlValidator validator;
    private final DataExplorer explorer;
    private final RelationshipMapper relationshipMapper;

    private DatabaseToolkit(Builder builder) {
        this.mcpHost = builder.mcpHost;
        this.mcpPort = builder.mcpPort;
        this.restTemplate = builder.restTemplate;
        this.inspector = new SchemaInspector(mcpHost, mcpPort, restTemplate);
        this.validator = new SqlValidator(inspector);
        this.explorer = new DataExplorer(mcpHost, mcpPort, restTemplate);
        this.relationshipMapper = new RelationshipMapper(mcpHost, mcpPort, restTemplate);
    }

    /**
     * 创建Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder类
     */
    public static class Builder {
        private String mcpHost = "localhost";
        private int mcpPort = 40101;
        private RestTemplate restTemplate = new RestTemplate();

        public Builder mcpHost(String mcpHost) {
            this.mcpHost = mcpHost;
            return this;
        }

        public Builder mcpPort(int mcpPort) {
            this.mcpPort = mcpPort;
            return this;
        }

        public Builder restTemplate(RestTemplate restTemplate) {
            this.restTemplate = restTemplate;
            return this;
        }

        public DatabaseToolkit build() {
            return new DatabaseToolkit(this);
        }
    }

    // ==================== 表结构相关 ====================

    /**
     * 查看表结构
     *
     * @param tableName 表名
     * @return 表结构信息
     */
    public TableSchema describeTable(String tableName) {
        return inspector.describeTable(tableName);
    }

    /**
     * 批量查看表结构
     *
     * @param tableNames 表名列表
     * @return 表名 -> 表结构 映射
     */
    public Map<String, TableSchema> describeTables(List<String> tableNames) {
        return inspector.describeTables(tableNames);
    }

    /**
     * 查看所有表
     *
     * @return 表名列表
     */
    public List<String> listTables() {
        return explorer.listTables();
    }

    /**
     * 格式化表结构输出
     *
     * @param schema 表结构
     * @return 格式化的字符串
     */
    public String formatSchema(TableSchema schema) {
        return inspector.formatSchema(schema);
    }

    /**
     * 验证SQL字段
     *
     * @param sql SQL语句
     * @return 验证结果
     */
    public ValidationResult validateSql(String sql) {
        return validator.validate(sql);
    }

    /**
     * 验证多个SQL
     *
     * @param sqls SQL语句列表（可命名）
     * @return 验证结果映射
     */
    public Map<String, ValidationResult> validateSqls(Map<String, String> sqls) {
        return validator.validateBatch(sqls);
    }

    // ==================== 索引相关 ====================

    /**
     * 查看表的索引
     *
     * @param tableName 表名
     * @return 索引列表
     */
    public List<TableIndex> showIndexes(String tableName) {
        return explorer.showIndexes(tableName);
    }

    /**
     * 分析索引使用情况
     *
     * @param tableName 表名
     * @return 索引分析报告
     */
    public String analyzeIndexes(String tableName) {
        List<TableIndex> indexes = showIndexes(tableName);
        return IndexAnalyzer.analyze(tableName, indexes);
    }

    // ==================== 数据相关 ====================

    /**
     * 查看表数据样本
     *
     * @param tableName 表名
     * @param limit     返回条数（默认10）
     * @return 数据样本
     */
    public List<Map<String, Object>> sampleData(String tableName, int limit) {
        return explorer.sampleData(tableName, limit);
    }

    /**
     * 查看表数据样本（使用默认10条）
     *
     * @param tableName 表名
     * @return 数据样本
     */
    public List<Map<String, Object>> sampleData(String tableName) {
        return sampleData(tableName, 10);
    }

    /**
     * 查看表统计信息
     *
     * @param tableName 表名
     * @return 统计信息
     */
    public TableStatistics getStatistics(String tableName) {
        return explorer.getStatistics(tableName);
    }

    // ==================== 关系映射相关 ====================

    /**
     * 查看表的关联关系
     *
     * @param tableName 表名
     * @return 关联关系列表
     */
    public List<TableRelationship> showRelationships(String tableName) {
        return relationshipMapper.getRelationships(tableName);
    }

    /**
     * 可视化表关系图
     *
     * @param tableName 表名
     * @return 关系图的Mermaid格式字符串
     */
    public String visualizeRelationships(String tableName) {
        return relationshipMapper.visualize(tableName);
    }

    /**
     * 批量查看表关系
     *
     * @param tableNames 表名列表
     * @return 关系图（Mermaid格式）
     */
    public String visualizeRelationshipsBatch(List<String> tableNames) {
        return relationshipMapper.visualizeBatch(tableNames);
    }

    // ==================== SQL分析相关 ====================

    /**
     * 分析SQL性能
     *
     * @param sql SQL语句
     * @return 性能分析报告
     */
    public SqlPerformanceReport analyzePerformance(String sql) {
        return SqlAnalyzer.analyze(sql, mcpHost, mcpPort, restTemplate);
    }

    /**
     * 生成SQL优化建议
     *
     * @param sql SQL语句
     * @return 优化建议
     */
    public List<String> suggestOptimizations(String sql) {
        return SqlAnalyzer.suggestOptimizations(sql);
    }

    /**
     * 解释SQL执行计划
     *
     * @param sql SQL语句
     * @return 执行计划
     */
    public String explainSql(String sql) {
        return SqlAnalyzer.explain(sql, mcpHost, mcpPort, restTemplate);
    }

    // ==================== 调试辅助相关 ====================

    /**
     * 生成完整的表分析报告
     *
     * @param tableName 表名
     * @return 完整分析报告
     */
    public String generateFullReport(String tableName) {
        StringBuilder report = new StringBuilder();

        report.append("=").append("=".repeat(70)).append("\n");
        report.append("表分析报告: ").append(tableName).append("\n");
        report.append("=").append("=".repeat(70)).append("\n\n");

        // 1. 表结构
        report.append("【1. 表结构】\n");
        TableSchema schema = describeTable(tableName);
        report.append(formatSchema(schema)).append("\n");

        // 2. 索引分析
        report.append("【2. 索引分析】\n");
        report.append(analyzeIndexes(tableName)).append("\n");

        // 3. 统计信息
        report.append("【3. 统计信息】\n");
        TableStatistics stats = getStatistics(tableName);
        report.append(stats.toString()).append("\n");

        // 4. 数据样本
        report.append("【4. 数据样本】\n");
        List<Map<String, Object>> samples = sampleData(tableName, 5);
        report.append(DataExplorer.formatSamples(samples)).append("\n");

        // 5. 关联关系
        report.append("【5. 关联关系】\n");
        List<TableRelationship> relationships = showRelationships(tableName);
        report.append(relationshipMapper.formatRelationships(relationships)).append("\n");

        // 6. 建议和警告
        report.append("【6. 优化建议】\n");
        report.append(generateOptimizations(schema, stats, relationships)).append("\n");

        return report.toString();
    }

    /**
     * 生成优化建议
     */
    private String generateOptimizations(TableSchema schema, TableStatistics stats, List<TableRelationship> relationships) {
        List<String> suggestions = new ArrayList<>();

        // 基于表结构的建议
        if (schema.getColumns().size() > 20) {
            suggestions.add("⚠️  表字段较多(" + schema.getColumns().size() + ")，考虑是否需要垂直拆分");
        }

        // 基于统计信息的建议
        if (stats.getRowCount() > 1000000) {
            suggestions.add("💡 大表（" + stats.getRowCount() + "行），建议添加索引优化查询");
        }

        // 基于关系的建议
        if (relationships.isEmpty()) {
            suggestions.add("ℹ️  该表没有外键关联，可能是独立的基础表");
        } else if (relationships.size() > 5) {
            suggestions.add("💡 表关系复杂（" + relationships.size() + "个关联），注意查询性能");
        }

        if (suggestions.isEmpty()) {
            return "  ✅ 无明显优化建议";
        }

        StringBuilder sb = new StringBuilder();
        for (String suggestion : suggestions) {
            sb.append("  ").append(suggestion).append("\n");
        }
        return sb.toString();
    }

    // ==================== 工具方法 ====================

    /**
     * 清除缓存
     */
    public void clearCache() {
        inspector.clearCache();
    }

    /**
     * 获取Schema检查器（用于更高级的操作）
     */
    public SchemaInspector getInspector() {
        return inspector;
    }

    /**
     * 获取SQL验证器（用于更高级的操作）
     */
    public SqlValidator getValidator() {
        return validator;
    }

    /**
     * 获取数据探索器（用于更高级的操作）
     */
    public DataExplorer getExplorer() {
        return explorer;
    }

    /**
     * 获取关系映射器（用于更高级的操作）
     */
    public RelationshipMapper getRelationshipMapper() {
        return relationshipMapper;
    }
}
