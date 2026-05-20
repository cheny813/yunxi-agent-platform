package io.yunxi.platform.agent.text2sql.workflow;

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
 * Text-to-SQL 工作流
 * <p>
 * 协调各个组件，完成完整的 Text-to-SQL 流程：
 * 1. Schema 生成
 * 2. 列检索
 * 3. Few-shot 检索
 * 4. SQL 生成
 * 5. SQL 对齐
 * 6. SQL 投票
 * </p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
@Service
@ConditionalOnBean(SqlGenerator.class)
public class Text2SqlWorkflow {

    private static final Logger log = LoggerFactory.getLogger(Text2SqlWorkflow.class);

    @Autowired
    private SchemaGenerator schemaGenerator;

    @Autowired
    private ColumnRetriever columnRetriever;

    @Autowired
    private FewShotManager fewShotManager;

    @Autowired
    private SqlGenerator sqlGenerator;

    @Autowired
    private SqlAligner sqlAligner;

    @Autowired
    private SqlVoter sqlVoter;

    @Autowired
    private Text2SqlProperties text2SqlProperties;

    /**
     * 执行 Text-to-SQL 工作流
     *
     * @param question   用户问题
     * @param databaseId 数据库 ID
     * @return 最终 SQL
     */
    public Text2SqlResult execute(String question, String databaseId) {
        long startTime = System.currentTimeMillis();

        Text2SqlResult result = new Text2SqlResult();
        result.setQuestion(question);
        result.setDatabaseId(databaseId);

        try {
            log.info("开始 Text-to-SQL 工作流: question={}, databaseId={}", question, databaseId);

            // 1. 生成 Schema
            log.debug("步骤 1: 生成 Schema");
            SchemaGenerator.DatabaseSchema schemaObj = schemaGenerator.generateSchema(databaseId);
            String schema = schemaObj != null ? schemaObj.toLLMPrompt() : "";
            result.setSchema(schema);
            if (schema == null || schema.isEmpty()) {
                result.setSuccess(false);
                result.setErrorMessage("无法生成数据库 Schema");
                return result;
            }

            // 2. 检索相关列
            log.debug("步骤 2: 检索相关列");
            List<ColumnRetriever.ColumnInfo> columnInfos = columnRetriever.retrieveColumns(question, null, 10);
            List<String> relevantColumns = new ArrayList<>();
            for (ColumnRetriever.ColumnInfo info : columnInfos) {
                relevantColumns.add(info.getColumnName());
            }
            result.setRelevantColumns(relevantColumns);

            // 3. 检索 Few-shot 示例
            log.debug("步骤 3: 检索 Few-shot 示例");
            List<FewShotManager.FewShotExample> fewShots = fewShotManager.retrieveExamples(
                    databaseId, question, schema, 5);
            result.setFewShotCount(fewShots.size());

            // 4. 生成候选 SQL
            log.debug("步骤 4: 生成 {} 个候选 SQL", text2SqlProperties.getCandidateCount());
            List<String> fewShotPrompts = formatFewShots(fewShots);
            List<String> candidates = sqlGenerator.generateCandidateSqls(
                    question, schema, relevantColumns, fewShotPrompts, text2SqlProperties.getCandidateCount());
            result.setCandidateCount(candidates.size());

            if (candidates.isEmpty()) {
                result.setSuccess(false);
                result.setErrorMessage("无法生成候选 SQL");
                return result;
            }

            // 5. SQL 对齐
            List<String> alignedCandidates = candidates;
            if (text2SqlProperties.isUseAlignment()) {
                log.debug("步骤 5: SQL 对齐");
                alignedCandidates = new ArrayList<>();
                for (String candidate : candidates) {
                    String aligned = sqlAligner.alignSql(candidate, schema, relevantColumns);
                    alignedCandidates.add(aligned);
                }
            }
            result.setAlignedCount(alignedCandidates.size());

            // 6. SQL 投票
            String finalSql;
            if (text2SqlProperties.isUseVoting() && alignedCandidates.size() > 1) {
                log.debug("步骤 6: SQL 投票");
                SqlVoter.SqlExecutor executor = new SqlVoter.McpSqlExecutor(databaseId, sqlVoter.getDatabaseClient(), 30);
                finalSql = sqlVoter.vote(alignedCandidates, executor);
                result.setVotingEnabled(true);
            } else {
                finalSql = alignedCandidates.get(0);
                result.setVotingEnabled(false);
            }

            result.setFinalSql(finalSql);
            result.setSuccess(true);

            log.info("Text-to-SQL 工作流完成: question={}, sql={}, 耗时={}ms",
                    question, finalSql, System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            log.error("Text-to-SQL 工作流失败: question={}, databaseId={}", question, databaseId, e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }

        result.setExecutionTime(System.currentTimeMillis() - startTime);
        return result;
    }

    /**
     * 批量执行 Text-to-SQL 工作流
     *
     * @param questions  问题列表
     * @param databaseId 数据库 ID
     * @return 结果列表
     */
    public List<Text2SqlResult> executeBatch(List<String> questions, String databaseId) {
        List<Text2SqlResult> results = new ArrayList<>();

        for (String question : questions) {
            Text2SqlResult result = execute(question, databaseId);
            results.add(result);
        }

        return results;
    }

    /**
     * 格式化 Few-shot 示例为 Prompt
     */
    private List<String> formatFewShots(List<FewShotManager.FewShotExample> fewShots) {
        if (fewShots == null || fewShots.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> prompts = new ArrayList<>();
        for (FewShotManager.FewShotExample example : fewShots) {
            String prompt = String.format("Q: %s\nA: %s", example.getQuestion(), example.getSql());
            prompts.add(prompt);
        }

        return prompts;
    }

    /**
     * Text-to-SQL 结果
     */
    public static class Text2SqlResult {
        private String question;
        private String databaseId;
        private String schema;
        private List<String> relevantColumns;
        private int fewShotCount;
        private int candidateCount;
        private int alignedCount;
        private boolean votingEnabled;
        private String finalSql;
        private boolean success;
        private String errorMessage;
        private long executionTime;

        // Getters and Setters
        public String getQuestion() {
            return question;
        }

        public void setQuestion(String question) {
            this.question = question;
        }

        public String getDatabaseId() {
            return databaseId;
        }

        public void setDatabaseId(String databaseId) {
            this.databaseId = databaseId;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public List<String> getRelevantColumns() {
            return relevantColumns;
        }

        public void setRelevantColumns(List<String> relevantColumns) {
            this.relevantColumns = relevantColumns;
        }

        public int getFewShotCount() {
            return fewShotCount;
        }

        public void setFewShotCount(int fewShotCount) {
            this.fewShotCount = fewShotCount;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public void setCandidateCount(int candidateCount) {
            this.candidateCount = candidateCount;
        }

        public int getAlignedCount() {
            return alignedCount;
        }

        public void setAlignedCount(int alignedCount) {
            this.alignedCount = alignedCount;
        }

        public boolean isVotingEnabled() {
            return votingEnabled;
        }

        public void setVotingEnabled(boolean votingEnabled) {
            this.votingEnabled = votingEnabled;
        }

        public String getFinalSql() {
            return finalSql;
        }

        public void setFinalSql(String finalSql) {
            this.finalSql = finalSql;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public long getExecutionTime() {
            return executionTime;
        }

        public void setExecutionTime(long executionTime) {
            this.executionTime = executionTime;
        }

        @Override
        public String toString() {
            return String.format("Text2SqlResult{question='%s', success=%s, sql='%s', time=%dms}",
                    question, success, finalSql, executionTime);
        }
    }
}
