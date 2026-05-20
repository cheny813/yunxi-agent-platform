package io.yunxi.platform.agent.text2sql.generation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqlGenerator 单元测试
 * <p>
 * 由于 SqlGenerator 使用 DashScope API，测试主要验证：
 * 1. SQL 清理逻辑
 * 2. Prompt 构建逻辑
 * </p>
 */
class SqlGeneratorTest {

        @Nested
        @DisplayName("SQL 清理测试")
        class SqlCleaning {

                @Test
                @DisplayName("移除 Markdown 代码块标记")
                void removesMarkdownCodeBlocks() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method cleanSqlMethod = SqlGenerator.class.getDeclaredMethod("cleanSql", String.class);
                        cleanSqlMethod.setAccessible(true);

                        String input = "```sql\nSELECT * FROM users\n```";
                        String result = (String) cleanSqlMethod.invoke(generator, input);

                        assertEquals("SELECT * FROM users", result);
                }

                @Test
                @DisplayName("移除多余的 ~ 标记")
                void removesTildeMarkers() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method cleanSqlMethod = SqlGenerator.class.getDeclaredMethod("cleanSql", String.class);
                        cleanSqlMethod.setAccessible(true);

                        String input = "~~SELECT * FROM users~~";
                        String result = (String) cleanSqlMethod.invoke(generator, input);

                        assertEquals("SELECT * FROM users", result);
                }

                @Test
                @DisplayName("处理 null 输入")
                void handlesNullInput() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method cleanSqlMethod = SqlGenerator.class.getDeclaredMethod("cleanSql", String.class);
                        cleanSqlMethod.setAccessible(true);

                        String result = (String) cleanSqlMethod.invoke(generator, (String) null);

                        assertNull(result);
                }

                @Test
                @DisplayName("移除多余空格")
                void normalizesWhitespace() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method cleanSqlMethod = SqlGenerator.class.getDeclaredMethod("cleanSql", String.class);
                        cleanSqlMethod.setAccessible(true);

                        String input = "  SELECT   *    FROM   users  ";
                        String result = (String) cleanSqlMethod.invoke(generator, input);

                        assertEquals("SELECT * FROM users", result);
                }
        }

        @Nested
        @DisplayName("Prompt 构建测试")
        class PromptConstruction {

                @Test
                @DisplayName("包含数据库 Schema")
                void includesDatabaseSchema() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method buildPromptMethod = SqlGenerator.class.getDeclaredMethod("buildPrompt", String.class,
                                        String.class, java.util.List.class, java.util.List.class);
                        buildPromptMethod.setAccessible(true);

                        String prompt = (String) buildPromptMethod.invoke(
                                        generator,
                                        "how many users",
                                        "CREATE TABLE users (id INT, name VARCHAR(100))",
                                        java.util.List.of(),
                                        java.util.List.of());

                        assertTrue(prompt.contains("Database Schema"));
                        assertTrue(prompt.contains("CREATE TABLE users"));
                }

                @Test
                @DisplayName("包含相关列")
                void includesRelevantColumns() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method buildPromptMethod = SqlGenerator.class.getDeclaredMethod("buildPrompt", String.class,
                                        String.class, java.util.List.class, java.util.List.class);
                        buildPromptMethod.setAccessible(true);

                        String prompt = (String) buildPromptMethod.invoke(
                                        generator,
                                        "how many users",
                                        "schema",
                                        java.util.List.of("id", "name"),
                                        java.util.List.of());

                        assertTrue(prompt.contains("Relevant Columns"));
                        assertTrue(prompt.contains("id"));
                        assertTrue(prompt.contains("name"));
                }

                @Test
                @DisplayName("包含 Few-shot 示例")
                void includesFewShotExamples() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method buildPromptMethod = SqlGenerator.class.getDeclaredMethod("buildPrompt", String.class,
                                        String.class, java.util.List.class, java.util.List.class);
                        buildPromptMethod.setAccessible(true);

                        String prompt = (String) buildPromptMethod.invoke(
                                        generator,
                                        "how many users",
                                        "schema",
                                        java.util.List.of(),
                                        java.util.List.of("Q: how many\nA: SELECT COUNT(*)"));

                        assertTrue(prompt.contains("Examples"));
                        assertTrue(prompt.contains("Q: how many"));
                }

                @Test
                @DisplayName("包含用户问题")
                void includesUserQuestion() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method buildPromptMethod = SqlGenerator.class.getDeclaredMethod("buildPrompt", String.class,
                                        String.class, java.util.List.class, java.util.List.class);
                        buildPromptMethod.setAccessible(true);

                        String prompt = (String) buildPromptMethod.invoke(
                                        generator,
                                        "how many users are there",
                                        "schema",
                                        java.util.List.of(),
                                        java.util.List.of());

                        assertTrue(prompt.contains("Question"));
                        assertTrue(prompt.contains("how many users are there"));
                }

                @Test
                @DisplayName("处理 null 列和 Few-shot")
                void handlesNullInputs() throws Exception {
                        SqlGenerator generator = createGenerator();
                        Method buildPromptMethod = SqlGenerator.class.getDeclaredMethod("buildPrompt", String.class,
                                        String.class, java.util.List.class, java.util.List.class);
                        buildPromptMethod.setAccessible(true);

                        String prompt = (String) buildPromptMethod.invoke(
                                        generator,
                                        "test",
                                        "schema",
                                        null,
                                        null);

                        assertNotNull(prompt);
                        assertTrue(prompt.contains("test"));
                }
        }

        /**
         * 创建 SqlGenerator 实例用于测试
         * 由于没有 API Key，测试只能验证内部逻辑
         */
        private SqlGenerator createGenerator() {
                return new SqlGenerator();
        }
}
