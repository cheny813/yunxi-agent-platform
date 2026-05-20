package io.yunxi.platform.agent.text2sql.service;

import io.yunxi.platform.agent.text2sql.alignment.SqlAligner;
import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import io.yunxi.platform.agent.text2sql.fewshot.FewShotManager;
import io.yunxi.platform.agent.text2sql.generation.SqlGenerator;
import io.yunxi.platform.agent.text2sql.retrieval.ColumnRetriever;
import io.yunxi.platform.agent.text2sql.schema.SchemaGenerator;
import io.yunxi.platform.agent.text2sql.voting.SqlVoter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Text-to-SQL 服务
 * <p>
 * 统一的 Text-to-SQL 处理入口，整合检索、生成、对齐、投票等模块
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
@ConditionalOnBean(SqlGenerator.class)
public class Text2SqlService {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlService.class);

    @Autowired
    private SchemaGenerator schemaGenerator;

    @Autowired
    private ColumnRetriever columnRetriever;

    @Autowired
    private SqlGenerator sqlGenerator;

    @Autowired
    private SqlAligner sqlAligner;

    @Autowired
    private SqlVoter sqlVoter;

    @Autowired
    private FewShotManager fewShotManager;

    @Autowired
    private Text2SqlProperties text2SqlProperties;

    /**
     * Text-to-SQL（单流程）
     *
     * @param databaseId 数据库 ID
     * @param question   用户问题
     * @return 生成的 SQL
     */
    public String text2Sql(String databaseId, String question) {
        try {
            log.info("开始 Text-to-SQL 处理: databaseId={}, question={}", databaseId, question);

            // 1. 生成数据库 Schema
            SchemaGenerator.DatabaseSchema schemaObj = schemaGenerator.generateSchema(databaseId);
            String schema = schemaObj != null ? schemaObj.toLLMPrompt() : "";
            log.debug("生成的 Schema: {}", schema);

            // 2. 检索相关列
            List<ColumnRetriever.ColumnInfo> columnInfos = columnRetriever.retrieveColumns(
                    question, null, 10);
            List<String> relevantColumns = new ArrayList<>();
            for (ColumnRetriever.ColumnInfo info : columnInfos) {
                relevantColumns.add(info.getColumnName());
            }
            log.debug("检索到相关列: {}", relevantColumns);

            // 3. 检索 Few-shot 示例
            List<FewShotManager.FewShotExample> fewShotExamples =
                    fewShotManager.retrieveExamples(databaseId, question, schema, 5);
            List<String> fewShots = fewShotManager.formatExamplesToPrompt(
                    fewShotExamples).lines().toList();
            log.debug("检索到 Few-shot 示例: {}", fewShots.size());

            // 4. 生成 SQL
            String sql = sqlGenerator.generateSql(question, schema, relevantColumns, fewShots);

            if (sql == null || sql.isEmpty()) {
                log.warn("SQL 生成失败: question={}", question);
                return null;
            }

            // 5. 对齐 SQL（可选）
            if (text2SqlProperties.isUseAlignment()) {
                sql = sqlAligner.alignSql(sql, schema, relevantColumns);
                log.debug("对齐后的 SQL: {}", sql);
            }

            log.info("Text-to-SQL 处理完成: question={}, sql={}", question, sql);
            return sql;

        } catch (Exception e) {
            log.error("Text-to-SQL 处理失败: databaseId={}, question={}", databaseId, question, e);
            return null;
        }
    }

    /**
     * Text-to-SQL（带投票）
     *
     * @param databaseId 数据库 ID
     * @param question   用户问题
     * @return 生成的 SQL
     */
    public String text2SqlWithVoting(String databaseId, String question) {
        try {
            log.info("开始 Text-to-SQL 处理（带投票）: databaseId={}, question={}", databaseId, question);

            // 1. 生成数据库 Schema
            SchemaGenerator.DatabaseSchema schemaObj = schemaGenerator.generateSchema(databaseId);
            String schema = schemaObj != null ? schemaObj.toLLMPrompt() : "";
            log.debug("生成的 Schema: {}", schema);

            // 2. 检索相关列
            List<ColumnRetriever.ColumnInfo> columnInfos = columnRetriever.retrieveColumns(
                    question, null, 10);
            List<String> relevantColumns = new ArrayList<>();
            for (ColumnRetriever.ColumnInfo info : columnInfos) {
                relevantColumns.add(info.getColumnName());
            }
            log.debug("检索到相关列: {}", relevantColumns);

            // 3. 检索 Few-shot 示例
            List<FewShotManager.FewShotExample> fewShotExamples =
                    fewShotManager.retrieveExamples(databaseId, question, schema, 5);
            List<String> fewShots = fewShotManager.formatExamplesToPrompt(
                    fewShotExamples).lines().toList();
            log.debug("检索到 Few-shot 示例: {}", fewShots.size());

            // 4. 生成多个候选 SQL
            List<String> candidateSqls = sqlGenerator.generateCandidateSqls(
                    question, schema, relevantColumns, fewShots, text2SqlProperties.getCandidateCount());
            log.debug("生成 {} 个候选 SQL", candidateSqls.size());

            if (candidateSqls.isEmpty()) {
                log.warn("候选 SQL 生成失败: question={}", question);
                return null;
            }

            // 5. 对齐候选 SQL（可选）
            if (text2SqlProperties.isUseAlignment()) {
                List<String> alignedSqls = new ArrayList<>();
                for (String sql : candidateSqls) {
                    String aligned = sqlAligner.alignSql(sql, schema, relevantColumns);
                    alignedSqls.add(aligned);
                }
                candidateSqls = alignedSqls;
                log.debug("对齐后的候选 SQL: {}", candidateSqls);
            }

            // 6. 投票选择最优 SQL
            if (text2SqlProperties.isUseVoting()) {
                SqlVoter.SqlExecutor executor = new SqlVoter.McpSqlExecutor(databaseId, sqlVoter.getDatabaseClient(), 30);
                String bestSql = sqlVoter.vote(candidateSqls, executor);
                log.info("投票选择的最优 SQL: question={}, sql={}", question, bestSql);
                return bestSql;
            } else {
                // 不使用投票，返回第一个
                String sql = candidateSqls.get(0);
                log.info("选择第一个候选 SQL: question={}, sql={}", question, sql);
                return sql;
            }

        } catch (Exception e) {
            log.error("Text-to-SQL 处理失败（带投票）: databaseId={}, question={}", databaseId, question, e);
            return null;
        }
    }

    /**
     * 添加 Few-shot 示例
     *
     * @param databaseId 数据库 ID
     * @param question   问题
     * @param sql        SQL
     * @param score      评分（可选，默认 1.0）
     */
    public void addFewShotExample(String databaseId, String question, String sql, double score) {
        SchemaGenerator.DatabaseSchema schemaObj = schemaGenerator.generateSchema(databaseId);
        String schema = schemaObj != null ? schemaObj.toLLMPrompt() : "";
        fewShotManager.addExample(databaseId, question, sql, schema, score);
        log.info("添加 Few-shot 示例: databaseId={}, question={}, score={}", databaseId, question, score);
    }

    /**
     * 列出数据库的表
     *
     * @param databaseId 数据库 ID
     * @return 表列表
     */
    public List<String> listTables(String databaseId) {
        return schemaGenerator.listTablesPublic(databaseId);
    }

    /**
     * 描述表结构
     *
     * @param databaseId 数据库 ID
     * @param tableName  表名
     * @return 表结构描述
     */
    public String describeTable(String databaseId, String tableName) {
        SchemaGenerator.TableSchema tableSchema = schemaGenerator.describeTablePublic(databaseId, tableName);
        if (tableSchema == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(tableSchema.getTableName()).append("\n");
        for (SchemaGenerator.ColumnSchema column : tableSchema.getColumns()) {
            sb.append("  - ").append(column.getColumnName())
              .append(" (").append(column.getDataType()).append(")");
            if (column.isPrimaryKey()) {
                sb.append(" [PK]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 索引列信息（用于列检索）
     *
     * @param databaseId 数据库 ID
     * @param forceReindex 是否强制重新索引
     */
    public void indexColumns(String databaseId, boolean forceReindex) {
        SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema(databaseId);
        if (schema != null && schema.getTables() != null) {
            // 转换 SchemaGenerator.TableSchema 到 ColumnRetriever.TableSchema
            List<ColumnRetriever.TableSchema> columnRetrieverSchemas = new ArrayList<>();
            for (SchemaGenerator.TableSchema tableSchema : schema.getTables()) {
                List<ColumnRetriever.ColumnSchema> crColumnSchemas = new ArrayList<>();
                for (SchemaGenerator.ColumnSchema columnSchema : tableSchema.getColumns()) {
                    ColumnRetriever.ColumnSchema crColumnSchema = new ColumnRetriever.ColumnSchema(
                            columnSchema.getColumnName(), columnSchema.getDataType());
                    crColumnSchemas.add(crColumnSchema);
                }
                ColumnRetriever.TableSchema crTableSchema = new ColumnRetriever.TableSchema(
                        tableSchema.getTableName(), crColumnSchemas);
                columnRetrieverSchemas.add(crTableSchema);
            }
            columnRetriever.indexColumns(databaseId, columnRetrieverSchemas);
        }
        log.info("索引列信息: databaseId={}, forceReindex={}", databaseId, forceReindex);
    }
}
