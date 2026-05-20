package io.yunxi.platform.agent.text2sql.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import io.yunxi.platform.spi.text2sql.DatabaseClient;

class SchemaGeneratorTest {

    @Mock
    private DatabaseClient databaseClient;

    @InjectMocks
    private SchemaGenerator schemaGenerator;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Nested
    @DisplayName("generateSchema - MCP null")
    class McpNull {

        @Test
        @DisplayName("databaseClient is null - returns null schema")
        void databaseClientNull() {
            // 创建实例时不注入Mock
            schemaGenerator = new SchemaGenerator();
            // databaseClient字段为@Autowired(required=false)，因此保持null

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");
            assertNotNull(schema);
            assertTrue(schema.getTables().isEmpty());
        }
    }

    @Nested
    @DisplayName("generateSchema - parse tables and columns")
    class ParseTablesAndColumns {

        @Test
        @DisplayName("parses listTables and describeTable responses")
        void parsesTablesAndColumns() {
            // listTables返回List<String>
            when(databaseClient.listTables()).thenReturn(List.of("users", "orders"));

            // describeTable返回TableSchema
            List<DatabaseClient.ColumnInfo> usersColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false),
                    new DatabaseClient.ColumnInfo("name", "VARCHAR", false));
            when(databaseClient.describeTable("users"))
                    .thenReturn(new DatabaseClient.TableSchema("users", usersColumns));

            List<DatabaseClient.ColumnInfo> ordersColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false),
                    new DatabaseClient.ColumnInfo("user_id", "INT", false));
            when(databaseClient.describeTable("orders"))
                    .thenReturn(new DatabaseClient.TableSchema("orders", ordersColumns));

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");

            assertNotNull(schema);
            assertEquals("db1", schema.getDatabaseId());
            assertEquals(2, schema.getTables().size());

            // 验证users表
            SchemaGenerator.TableSchema usersTable = schema.getTables().get(0);
            assertEquals("users", usersTable.getTableName());
            assertEquals(2, usersTable.getColumns().size());
            assertEquals("id", usersTable.getColumns().get(0).getColumnName());

            // 验证orders表
            SchemaGenerator.TableSchema ordersTable = schema.getTables().get(1);
            assertEquals("orders", ordersTable.getTableName());
            assertEquals("user_id", ordersTable.getColumns().get(1).getColumnName());
        }
    }

    @Nested
    @DisplayName("generateSchema - infer foreign keys")
    class InferForeignKeys {

        @Test
        @DisplayName("column ending _id infers FK relationship")
        void infersForeignKey() {
            when(databaseClient.listTables()).thenReturn(List.of("category", "product"));

            List<DatabaseClient.ColumnInfo> categoryColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false));
            when(databaseClient.describeTable("category"))
                    .thenReturn(new DatabaseClient.TableSchema("category", categoryColumns));

            List<DatabaseClient.ColumnInfo> productColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false),
                    new DatabaseClient.ColumnInfo("category_id", "INT", false));
            when(databaseClient.describeTable("product"))
                    .thenReturn(new DatabaseClient.TableSchema("product", productColumns));

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");

            assertNotNull(schema);
            assertNotNull(schema.getForeignKeys());
            assertFalse(schema.getForeignKeys().isEmpty());

            SchemaGenerator.ForeignKey fk = schema.getForeignKeys().get(0);
            assertEquals("category_id", fk.getColumnName());
            assertEquals("category", fk.getReferencedTable());
            assertEquals("id", fk.getReferencedColumn());
            assertEquals(0.8, fk.getConfidence(), 0.01);
        }

        @Test
        @DisplayName("column ending Id (camelCase) infers FK")
        void infersForeignKeyCamelCase() {
            when(databaseClient.listTables()).thenReturn(List.of("category", "product"));

            List<DatabaseClient.ColumnInfo> categoryColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false));
            when(databaseClient.describeTable("category"))
                    .thenReturn(new DatabaseClient.TableSchema("category", categoryColumns));

            List<DatabaseClient.ColumnInfo> productColumns = List.of(
                    new DatabaseClient.ColumnInfo("id", "INT", false),
                    new DatabaseClient.ColumnInfo("categoryId", "INT", false));
            when(databaseClient.describeTable("product"))
                    .thenReturn(new DatabaseClient.TableSchema("product", productColumns));

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");

            assertNotNull(schema.getForeignKeys());
            // categoryId -> category (after removing "Id" suffix and lowercasing)
            boolean hasCategoryIdFk = schema.getForeignKeys().stream()
                    .anyMatch(fk -> fk.getColumnName().equals("categoryId")
                            && fk.getReferencedTable().equals("category"));
            assertTrue(hasCategoryIdFk);
        }
    }

    @Nested
    @DisplayName("generateSchema - returns null/empty response")
    class ReturnsNull {

        @Test
        @DisplayName("listTables returns empty - schema has no tables")
        void listTablesReturnsEmpty() {
            when(databaseClient.listTables()).thenReturn(List.of());

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");

            assertNotNull(schema);
            assertTrue(schema.getTables().isEmpty());
        }

        @Test
        @DisplayName("describeTable returns null - table is skipped")
        void describeTableReturnsNull() {
            when(databaseClient.listTables()).thenReturn(List.of("users"));
            when(databaseClient.describeTable("users")).thenReturn(null);

            SchemaGenerator.DatabaseSchema schema = schemaGenerator.generateSchema("db1");

            assertNotNull(schema);
            assertTrue(schema.getTables().isEmpty());
        }
    }

    @Nested
    @DisplayName("DatabaseSchema.toLLMPrompt")
    class ToLLMPrompt {

        @Test
        @DisplayName("generates correct text format")
        void generatesCorrectFormat() {
            SchemaGenerator.DatabaseSchema schema = new SchemaGenerator.DatabaseSchema();
            schema.setDatabaseId("testdb");

            SchemaGenerator.TableSchema usersTable = new SchemaGenerator.TableSchema();
            usersTable.setTableName("users");

            SchemaGenerator.ColumnSchema idCol = new SchemaGenerator.ColumnSchema();
            idCol.setColumnName("id");
            idCol.setDataType("INT");
            idCol.setPrimaryKey(true);
            usersTable.setColumns(List.of(idCol));

            schema.setTables(List.of(usersTable));

            SchemaGenerator.ForeignKey fk = new SchemaGenerator.ForeignKey();
            fk.setColumnName("user_id");
            fk.setReferencedTable("users");
            fk.setReferencedColumn("id");
            schema.setForeignKeys(List.of(fk));

            String prompt = schema.toLLMPrompt();

            assertTrue(prompt.contains("Database Schema:"));
            assertTrue(prompt.contains("Table: users"));
            assertTrue(prompt.contains("id (INT) [PK]"));
            assertTrue(prompt.contains("Foreign Keys:"));
            assertTrue(prompt.contains("user_id -> users.id"));
        }
    }
}
