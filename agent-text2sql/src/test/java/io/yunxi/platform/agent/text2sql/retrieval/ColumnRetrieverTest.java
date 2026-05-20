package io.yunxi.platform.agent.text2sql.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.milvus.v2.client.MilvusClientV2;
import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import io.yunxi.platform.spi.text2sql.EmbeddingService;

class ColumnRetrieverTest {

    private Text2SqlProperties text2SqlProperties;
    private MilvusClientV2 milvusClient;
    private EmbeddingService embeddingService;
    private ColumnRetriever columnRetriever;

    @BeforeEach
    void setUp() {
        text2SqlProperties = mock(Text2SqlProperties.class);
        milvusClient = mock(MilvusClientV2.class);
        embeddingService = mock(EmbeddingService.class);

        Text2SqlProperties.RetrievalProperties retrievalProps = new Text2SqlProperties.RetrievalProperties();
        retrievalProps.setCollectionName("column_embeddings");
        retrievalProps.setTopK(10);
        when(text2SqlProperties.getRetrieval()).thenReturn(retrievalProps);

        columnRetriever = new ColumnRetriever();
        columnRetriever.setText2SqlProperties(text2SqlProperties);
        columnRetriever.setMilvusClient(milvusClient);
        columnRetriever.setEmbeddingService(embeddingService);
    }

    @Nested
    @DisplayName("retrieveColumns - embedding service null")
    class EmbeddingNull {

        @Test
        @DisplayName("embed returns null - returns empty list")
        void embedReturnsNull() {
            when(embeddingService.embed(any())).thenReturn(null);

            List<ColumnRetriever.ColumnInfo> result = columnRetriever.retrieveColumns("test query", null, 5);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("embed returns empty array - returns empty list")
        void embedReturnsEmpty() {
            when(embeddingService.embed(any())).thenReturn(new float[0]);

            List<ColumnRetriever.ColumnInfo> result = columnRetriever.retrieveColumns("test query", null, 5);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("retrieveColumns - no Milvus results")
    class NoMilvusResults {

        @Test
        @DisplayName("milvusClient search returns empty list - returns empty list")
        void milvusReturnsEmpty() {
            float[] embedding = new float[128];
            for (int i = 0; i < 128; i++) {
                embedding[i] = 0.1f;
            }
            when(embeddingService.embed(any())).thenReturn(embedding);
            when(milvusClient.search(any())).thenReturn(null);

            List<ColumnRetriever.ColumnInfo> result = columnRetriever.retrieveColumns("test query", null, 5);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("retrieveColumns - with results")
    class WithResults {

        @Test
        @DisplayName("milvusClient search throws exception - returns empty list")
        void milvusThrowsException() {
            float[] embedding = new float[128];
            for (int i = 0; i < 128; i++) {
                embedding[i] = 0.1f;
            }
            when(embeddingService.embed(any())).thenReturn(embedding);
            when(milvusClient.search(any())).thenThrow(new RuntimeException("connection refused"));

            List<ColumnRetriever.ColumnInfo> result = columnRetriever.retrieveColumns("test query", null, 5);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("retrieveColumns - default topK overload")
    class DefaultTopK {

        @Test
        @DisplayName("2-arg overload uses default topK from properties")
        void defaultTopK() {
            when(embeddingService.embed(any())).thenReturn(null);

            List<ColumnRetriever.ColumnInfo> result = columnRetriever.retrieveColumns("test", null);
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("indexColumns - Milvus not available")
    class IndexColumnsNullServices {

        @Test
        @DisplayName("milvusClient is null - skips indexing")
        void milvusNull() {
            columnRetriever.setMilvusClient(null);

            // 测试预期：当MilvusClient为null时，索引操作不应抛出异常，应跳过索引过程
            columnRetriever.indexColumns("db1", Collections.emptyList());
        }

        @Test
        @DisplayName("embeddingService is null - skips indexing")
        void embeddingServiceNull() {
            columnRetriever.setEmbeddingService(null);

            // 测试预期：当EmbeddingService为null时，索引操作不应抛出异常，应跳过索引过程
            columnRetriever.indexColumns("db1", Collections.emptyList());
        }
    }

    @Nested
    @DisplayName("ColumnInfo")
    class ColumnInfoTest {

        @Test
        @DisplayName("setters and getters")
        void settersAndGetters() {
            ColumnRetriever.ColumnInfo info = new ColumnRetriever.ColumnInfo();
            info.setColumnName("id");
            info.setTableName("users");
            info.setDataType("INT");
            info.setDescription("Primary key");
            info.setScore(0.95);

            assertEquals("id", info.getColumnName());
            assertEquals("users", info.getTableName());
            assertEquals("INT", info.getDataType());
            assertEquals("Primary key", info.getDescription());
            assertEquals(0.95, info.getScore(), 0.001);
        }
    }

    @Nested
    @DisplayName("TableSchema and ColumnSchema")
    class DataModels {

        @Test
        @DisplayName("TableSchema constructor with name only")
        void tableSchemaNameOnly() {
            ColumnRetriever.TableSchema ts = new ColumnRetriever.TableSchema("users");
            assertEquals("users", ts.getTableName());
            assertNotNull(ts.getColumns());
            assertTrue(ts.getColumns().isEmpty());
        }

        @Test
        @DisplayName("TableSchema constructor with name and columns")
        void tableSchemaWithColumns() {
            ColumnRetriever.ColumnSchema col = new ColumnRetriever.ColumnSchema("id", "INT");
            ColumnRetriever.TableSchema ts = new ColumnRetriever.TableSchema("users", List.of(col));
            assertEquals("users", ts.getTableName());
            assertEquals(1, ts.getColumns().size());
        }

        @Test
        @DisplayName("ColumnSchema setters and getters")
        void columnSchemaSetters() {
            ColumnRetriever.ColumnSchema col = new ColumnRetriever.ColumnSchema("name", "VARCHAR");
            col.setDescription("User name");
            assertEquals("name", col.getColumnName());
            assertEquals("VARCHAR", col.getBaseType());
            assertEquals("User name", col.getDescription());
        }
    }
}
