package io.yunxi.platform.agent.text2sql.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.yunxi.platform.agent.text2sql.alignment.SqlAligner;
import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import io.yunxi.platform.agent.text2sql.fewshot.FewShotManager;
import io.yunxi.platform.agent.text2sql.generation.SqlGenerator;
import io.yunxi.platform.agent.text2sql.retrieval.ColumnRetriever;
import io.yunxi.platform.agent.text2sql.schema.SchemaGenerator;
import io.yunxi.platform.agent.text2sql.voting.SqlVoter;

class Text2SqlWorkflowTest {

    @Mock
    private SchemaGenerator schemaGenerator;

    @Mock
    private ColumnRetriever columnRetriever;

    @Mock
    private FewShotManager fewShotManager;

    @Mock
    private SqlGenerator sqlGenerator;

    @Mock
    private SqlAligner sqlAligner;

    @Mock
    private SqlVoter sqlVoter;

    @Mock
    private Text2SqlProperties text2SqlProperties;

    @InjectMocks
    private Text2SqlWorkflow workflow;

    private SchemaGenerator.DatabaseSchema testSchema;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // 配置测试的默认参数
        when(text2SqlProperties.getCandidateCount()).thenReturn(3);
        when(text2SqlProperties.isUseAlignment()).thenReturn(true);
        when(text2SqlProperties.isUseVoting()).thenReturn(false);

        // 配置基础数据架构
        testSchema = new SchemaGenerator.DatabaseSchema();
        testSchema.setDatabaseId("db1");
        testSchema.setTables(new ArrayList<>());
        when(schemaGenerator.generateSchema("db1")).thenReturn(testSchema);

        // 模拟SqlVoter.getDatabaseClient()返回null（单元测试中没有数据库客户端）
        when(sqlVoter.getDatabaseClient()).thenReturn(null);
    }

    @Nested
    @DisplayName("execute - full pipeline")
    class FullPipeline {

        @Test
        @DisplayName("successful pipeline returns result with finalSql")
        void successfulPipeline() {
            // 架构生成步骤
            SchemaGenerator.TableSchema tableSchema = new SchemaGenerator.TableSchema();
            tableSchema.setTableName("users");
            tableSchema.setColumns(new ArrayList<>());
            testSchema.setTables(List.of(tableSchema));

            // 列信息检索步骤
            ColumnRetriever.ColumnInfo colInfo = new ColumnRetriever.ColumnInfo();
            colInfo.setColumnName("id");
            colInfo.setTableName("users");
            when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                    .thenReturn(List.of(colInfo));

            // Few-shot step
            when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                    .thenReturn(Collections.emptyList());

            // SQL generation step
            when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                    .thenReturn(List.of("SELECT id FROM users"));

            // SQL对齐步骤
            when(sqlAligner.alignSql(anyString(), any(), any()))
                    .thenReturn("SELECT id FROM users LIMIT 1000");

            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("how many users", "db1");

            assertTrue(result.isSuccess());
            assertNotNull(result.getFinalSql());
            assertEquals("how many users", result.getQuestion());
            assertEquals("db1", result.getDatabaseId());
            assertFalse(result.isVotingEnabled());
        }
    }

    @Nested
    @DisplayName("execute - schema step fails")
    class SchemaStepFails {

        @Test
        @DisplayName("null schema returns error result")
        void nullSchema() {
            when(schemaGenerator.generateSchema("db1")).thenReturn(null);

            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("test question", "db1");

            assertFalse(result.isSuccess());
            assertEquals("无法生成数据库 Schema", result.getErrorMessage());
        }

        @Test
        @DisplayName("empty schema prompt returns error result")
        void emptySchemaPrompt() {
            SchemaGenerator.DatabaseSchema emptySchema = new SchemaGenerator.DatabaseSchema();
            emptySchema.setDatabaseId("db1");
            emptySchema.setTables(null); // toLLMPrompt may throw or return empty
            when(schemaGenerator.generateSchema("db1")).thenReturn(emptySchema);

            // The schema object's toLLMPrompt() will NPE on null tables
            // workflow catches this and returns error
            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("test", "db1");

            assertFalse(result.isSuccess());
        }
    }

    @Nested
    @DisplayName("execute - voting disabled")
    class VotingDisabled {

        @Test
        @DisplayName("voting disabled returns first candidate")
        void votingDisabled() {
            when(text2SqlProperties.isUseVoting()).thenReturn(false);

            // 列信息检索
            when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                    .thenReturn(Collections.emptyList());

            // Few-shot
            when(fewShotManager.retrieveExamples(anyString(), anyString(), anyString(), eq(5)))
                    .thenReturn(Collections.emptyList());

            // SQL generation
            when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                    .thenReturn(List.of("SELECT 1", "SELECT 2", "SELECT 3"));

            // SQL对齐
            when(sqlAligner.alignSql(anyString(), any(), any()))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("test", "db1");

            assertTrue(result.isSuccess());
            assertFalse(result.isVotingEnabled());
            assertEquals("SELECT 1", result.getFinalSql());
        }
    }

    @Nested
    @DisplayName("execute - alignment disabled")
    class AlignmentDisabled {

        @Test
        @DisplayName("alignment disabled skips SqlAligner")
        void alignmentDisabled() {
            when(text2SqlProperties.isUseAlignment()).thenReturn(false);
            when(text2SqlProperties.isUseVoting()).thenReturn(false);

            when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                    .thenReturn(Collections.emptyList());
            when(fewShotManager.retrieveExamples(anyString(), anyString(), anyString(), eq(5)))
                    .thenReturn(Collections.emptyList());
            when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                    .thenReturn(List.of("SELECT * FROM users"));

            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("test", "db1");

            assertTrue(result.isSuccess());
            // SqlAligner不应该被调用
            verify(sqlAligner, never()).alignSql(anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("execute - no candidates generated")
    class NoCandidates {

        @Test
        @DisplayName("empty candidate list returns error")
        void emptyCandidates() {
            when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                    .thenReturn(Collections.emptyList());
            when(fewShotManager.retrieveExamples(anyString(), anyString(), anyString(), eq(5)))
                    .thenReturn(Collections.emptyList());
            when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                    .thenReturn(Collections.emptyList());

            Text2SqlWorkflow.Text2SqlResult result = workflow.execute("test", "db1");

            assertFalse(result.isSuccess());
            assertEquals("无法生成候选 SQL", result.getErrorMessage());
        }
    }

    @Nested
    @DisplayName("Text2SqlResult")
    class Text2SqlResultTest {

        @Test
        @DisplayName("toString contains key fields")
        void toStringFormat() {
            Text2SqlWorkflow.Text2SqlResult result = new Text2SqlWorkflow.Text2SqlResult();
            result.setQuestion("test");
            result.setSuccess(true);
            result.setFinalSql("SELECT 1");
            result.setExecutionTime(50L);

            String str = result.toString();
            assertTrue(str.contains("test"));
            assertTrue(str.contains("true"));
            assertTrue(str.contains("SELECT 1"));
        }
    }
}
