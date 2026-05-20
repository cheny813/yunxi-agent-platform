package io.yunxi.platform.agent.text2sql.service;

import io.yunxi.platform.agent.text2sql.alignment.SqlAligner;
import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import io.yunxi.platform.agent.text2sql.fewshot.FewShotManager;
import io.yunxi.platform.agent.text2sql.generation.SqlGenerator;
import io.yunxi.platform.agent.text2sql.retrieval.ColumnRetriever;
import io.yunxi.platform.agent.text2sql.schema.SchemaGenerator;
import io.yunxi.platform.agent.text2sql.voting.SqlVoter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Text2SqlServiceTest {

        @Mock
        private SchemaGenerator schemaGenerator;

        @Mock
        private ColumnRetriever columnRetriever;

        @Mock
        private SqlGenerator sqlGenerator;

        @Mock
        private SqlAligner sqlAligner;

        @Mock
        private SqlVoter sqlVoter;

        @Mock
        private FewShotManager fewShotManager;

        @Mock
        private Text2SqlProperties text2SqlProperties;

        @InjectMocks
        private Text2SqlService text2SqlService;

        private SchemaGenerator.DatabaseSchema testSchema;

        @BeforeEach
        void setUp() {
                MockitoAnnotations.openMocks(this);

                when(text2SqlProperties.isUseAlignment()).thenReturn(true);
                when(text2SqlProperties.isUseVoting()).thenReturn(false);
                when(text2SqlProperties.getCandidateCount()).thenReturn(3);
                when(sqlVoter.getDatabaseClient()).thenReturn(null);

                // 配置基础数据架构
                testSchema = new SchemaGenerator.DatabaseSchema();
                testSchema.setDatabaseId("db1");
                SchemaGenerator.TableSchema tableSchema = new SchemaGenerator.TableSchema();
                tableSchema.setTableName("users");
                tableSchema.setColumns(new ArrayList<>());
                testSchema.setTables(List.of(tableSchema));
                when(schemaGenerator.generateSchema("db1")).thenReturn(testSchema);
        }

        @Nested
        @DisplayName("text2Sql - single SQL path")
        class SingleSqlPath {

                @Test
                @DisplayName("returns generated SQL for simple question")
                void returnsSql() {
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateSql(anyString(), anyString(), anyList(), anyList()))
                                        .thenReturn("SELECT * FROM users");
                        when(sqlAligner.alignSql(anyString(), any(), any()))
                                        .thenReturn("SELECT * FROM users LIMIT 1000");

                        String result = text2SqlService.text2Sql("db1", "show all users");

                        assertNotNull(result);
                        assertTrue(result.contains("SELECT"));
                }

                @Test
                @DisplayName("null SQL generation returns null")
                void nullSqlGeneration() {
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateSql(anyString(), anyString(), anyList(), anyList()))
                                        .thenReturn(null);

                        String result = text2SqlService.text2Sql("db1", "test");

                        assertNull(result);
                }

                @Test
                @DisplayName("alignment disabled skips SqlAligner")
                void alignmentDisabled() {
                        when(text2SqlProperties.isUseAlignment()).thenReturn(false);
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateSql(anyString(), anyString(), anyList(), anyList()))
                                        .thenReturn("SELECT 1");

                        String result = text2SqlService.text2Sql("db1", "test");

                        assertNotNull(result);
                        verify(sqlAligner, never()).alignSql(anyString(), any(), any());
                }
        }

        @Nested
        @DisplayName("text2SqlWithVoting")
        class VotingPath {

                @Test
                @DisplayName("voting enabled returns best SQL from vote")
                void votingEnabled() {
                        when(text2SqlProperties.isUseVoting()).thenReturn(true);
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                                        .thenReturn(List.of("SELECT 1", "SELECT 2"));
                        when(sqlAligner.alignSql(anyString(), any(), any()))
                                        .thenAnswer(invocation -> invocation.getArgument(0));
                        when(sqlVoter.vote(anyList(), any()))
                                        .thenReturn("SELECT 1");

                        String result = text2SqlService.text2SqlWithVoting("db1", "test");

                        assertNotNull(result);
                        verify(sqlVoter).vote(anyList(), any());
                }

                @Test
                @DisplayName("voting disabled returns first candidate")
                void votingDisabled() {
                        when(text2SqlProperties.isUseVoting()).thenReturn(false);
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                                        .thenReturn(List.of("SELECT 1", "SELECT 2"));
                        when(sqlAligner.alignSql(anyString(), any(), any()))
                                        .thenAnswer(invocation -> invocation.getArgument(0));

                        String result = text2SqlService.text2SqlWithVoting("db1", "test");

                        assertEquals("SELECT 1", result);
                        verify(sqlVoter, never()).vote(anyList(), any());
                }

                @Test
                @DisplayName("empty candidates returns null")
                void emptyCandidates() {
                        when(text2SqlProperties.isUseVoting()).thenReturn(true);
                        when(columnRetriever.retrieveColumns(anyString(), any(), eq(10)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.retrieveExamples(eq("db1"), anyString(), anyString(), eq(5)))
                                        .thenReturn(Collections.emptyList());
                        when(fewShotManager.formatExamplesToPrompt(any()))
                                        .thenReturn("");
                        when(sqlGenerator.generateCandidateSqls(anyString(), anyString(), anyList(), anyList(), eq(3)))
                                        .thenReturn(Collections.emptyList());

                        String result = text2SqlService.text2SqlWithVoting("db1", "test");

                        assertNull(result);
                }
        }

        @Nested
        @DisplayName("listTables")
        class ListTables {

                @Test
                @DisplayName("delegates to schemaGenerator")
                void delegates() {
                        when(schemaGenerator.listTablesPublic("db1")).thenReturn(List.of("users", "orders"));

                        List<String> tables = text2SqlService.listTables("db1");

                        assertEquals(2, tables.size());
                        assertTrue(tables.contains("users"));
                        assertTrue(tables.contains("orders"));
                }
        }

        @Nested
        @DisplayName("describeTable")
        class DescribeTable {

                @Test
                @DisplayName("returns formatted table description")
                void returnsDescription() {
                        SchemaGenerator.TableSchema ts = new SchemaGenerator.TableSchema();
                        ts.setTableName("users");
                        SchemaGenerator.ColumnSchema col = new SchemaGenerator.ColumnSchema();
                        col.setColumnName("id");
                        col.setDataType("INT");
                        col.setPrimaryKey(true);
                        ts.setColumns(List.of(col));
                        when(schemaGenerator.describeTablePublic("db1", "users")).thenReturn(ts);

                        String desc = text2SqlService.describeTable("db1", "users");

                        assertTrue(desc.contains("Table: users"));
                        assertTrue(desc.contains("id (INT) [PK]"));
                }

                @Test
                @DisplayName("null schema returns empty string")
                void nullSchema() {
                        when(schemaGenerator.describeTablePublic("db1", "unknown")).thenReturn(null);

                        String desc = text2SqlService.describeTable("db1", "unknown");

                        assertEquals("", desc);
                }
        }

        @Nested
        @DisplayName("addFewShotExample")
        class AddFewShot {

                @Test
                @DisplayName("delegates to fewShotManager")
                void delegates() {
                        text2SqlService.addFewShotExample("db1", "how many users", "SELECT COUNT(*) FROM users", 1.0);

                        verify(fewShotManager).addExample(eq("db1"), eq("how many users"),
                                        eq("SELECT COUNT(*) FROM users"), anyString(), eq(1.0));
                }
        }
}
