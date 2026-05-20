package io.yunxi.platform.agent.text2sql.fewshot;

import io.yunxi.platform.agent.text2sql.config.Text2SqlProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * FewShotManager 测试类
 * 
 * <p>
 * 功能：
 * 1. 测试FewShotManager类的各种功能，包括示例的添加、检索、格式化等
 * 2. 使用嵌套测试结构组织不同类型的测试用例
 * 3. 验证少样本学习功能在不同场景下的正确性
 * </p>
 * 
 * @author yunxi-agent-platform
 * @version 1.0.0
 * @since 2024-01-01
 */
class FewShotManagerTest {

    /** Text2SQL配置属性Mock对象 */
    @Mock
    private Text2SqlProperties text2SqlProperties;

    /** 待测试的FewShotManager实例（通过Mock注入依赖） */
    @InjectMocks
    private FewShotManager fewShotManager;

    /**
     * 测试初始化方法
     * 
     * <p>
     * 在每个测试方法执行前调用，用于：
     * 1. 初始化Mock对象
     * 2. 设置FewShot配置属性
     * 3. 准备测试环境
     * </p>
     */
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        Text2SqlProperties.FewShotProperties fewshotProps = new Text2SqlProperties.FewShotProperties();
        fewshotProps.setMaxExamples(50);
        fewshotProps.setSimilarityThreshold(0.3); // low threshold for testing
        when(text2SqlProperties.getFewshot()).thenReturn(fewshotProps);
    }

    /**
     * 测试示例匹配相关功能的嵌套测试类
     * 
     * <p>
     * 包含对FewShotManager中检索示例功能的测试，包括：
     * 1. 精确匹配测试
     * 2. 部分匹配测试
     * 3. 排名结果测试
     * </p>
     */
    @Nested
    @DisplayName("retrieveExamples - matching examples")
    class MatchingExamples {

        /**
         * 测试完全匹配的情况
         * 
         * <p>
         * 验证当查询问题与存储示例完全一致时：
         * 1. 应该返回相应的SQL示例
         * 2. 相似度分数应该接近1.0
         * 3. 返回结果列表应该包含该示例
         * </p>
         */
        @Test
        @DisplayName("exact match returns example with high score")
        void exactMatch() {
            fewShotManager.addExample("db1", "how many users", "SELECT COUNT(*) FROM users", "", 1.0);

            List<FewShotManager.FewShotExample> results = fewShotManager.retrieveExamples("db1", "how many users", "",
                    5);

            assertEquals(1, results.size());
            assertEquals("SELECT COUNT(*) FROM users", results.get(0).getSql());
            assertEquals(1.0, results.get(0).getScore(), 0.01);
        }

        @Test
        @DisplayName("partial word overlap returns matching example")
        void partialMatch() {
            fewShotManager.addExample("db1", "how many users", "SELECT COUNT(*) FROM users", "", 1.0);

            // "how many orders" shares "how" and "many" with "how many users"
            List<FewShotManager.FewShotExample> results = fewShotManager.retrieveExamples("db1", "how many orders", "",
                    5);

            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("returns top K results sorted by similarity")
        void topKResults() {
            fewShotManager.addExample("db1", "count users active", "SELECT COUNT(*) FROM users WHERE active=1", "",
                    1.0);
            fewShotManager.addExample("db1", "count users total", "SELECT COUNT(*) FROM users", "", 1.0);
            fewShotManager.addExample("db1", "show product list", "SELECT * FROM products", "", 1.0);

            // "count users active" shares 2/4 words with "count users total" => Jaccard
            // ~0.5
            List<FewShotManager.FewShotExample> results = fewShotManager.retrieveExamples("db1", "count users active",
                    "", 2);

            assertEquals(2, results.size());
            // First result should be exact match
            assertEquals("count users active", results.get(0).getQuestion());
        }
    }

    /**
     * 测试无匹配情况的嵌套测试类
     * 
     * <p>
     * 包含对FewShotManager在没有匹配示例时的行为测试，包括：
     * 1. 不存在数据库的情况
     * 2. 查询问题与示例完全不同（低于阈值）的情况
     * </p>
     */
    @Nested
    @DisplayName("retrieveExamples - no match")
    class NoMatch {

        @Test
        @DisplayName("no examples for database returns empty")
        void noExamplesForDb() {
            List<FewShotManager.FewShotExample> results = fewShotManager.retrieveExamples("db_unknown",
                    "how many users", "", 5);

            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("totally different question returns empty (below threshold)")
        void totallyDifferent() {
            // Set high threshold
            Text2SqlProperties.FewShotProperties fewshotProps = new Text2SqlProperties.FewShotProperties();
            fewshotProps.setMaxExamples(50);
            fewshotProps.setSimilarityThreshold(0.99);
            when(text2SqlProperties.getFewshot()).thenReturn(fewshotProps);

            fewShotManager.addExample("db1", "how many users", "SELECT COUNT(*) FROM users", "", 1.0);

            // "delete all orders" shares no meaningful words with "how many users"
            List<FewShotManager.FewShotExample> results = fewShotManager.retrieveExamples("db1", "delete all orders",
                    "", 5);

            assertTrue(results.isEmpty());
        }
    }

    /**
     * 测试最大示例数量限制的嵌套测试类
     * 
     * <p>
     * 验证FewShotManager在达到最大示例数量限制时的行为，包括：
     * 1. 添加超过最大限制时移除最旧示例
     * 2. 确保示例数量不超过配置的最大值
     * </p>
     */
    @Nested
    @DisplayName("addExample - max examples limit")
    class MaxExamples {

        @Test
        @DisplayName("adding beyond maxExamples removes oldest")
        void addsBeyondMax() {
            Text2SqlProperties.FewShotProperties fewshotProps = new Text2SqlProperties.FewShotProperties();
            fewshotProps.setMaxExamples(3);
            fewshotProps.setSimilarityThreshold(0.3);
            when(text2SqlProperties.getFewshot()).thenReturn(fewshotProps);

            fewShotManager.addExample("db1", "q1", "s1", "", 1.0);
            fewShotManager.addExample("db1", "q2", "s2", "", 1.0);
            fewShotManager.addExample("db1", "q3", "s3", "", 1.0);
            fewShotManager.addExample("db1", "q4", "s4", "", 1.0);

            assertEquals(3, fewShotManager.getExampleCount("db1"));
        }
    }

    /**
     * 测试示例清理功能的嵌套测试类
     * 
     * <p>
     * 验证FewShotManager的clearExamples方法功能，包括：
     * 1. 清理特定数据库的所有示例
     * 2. 清理操作不影响其他数据库的示例
     * </p>
     */
    @Nested
    @DisplayName("clearExamples")
    class ClearExamples {

        @Test
        @DisplayName("clear removes all examples for database")
        void clearRemovesAll() {
            fewShotManager.addExample("db1", "q1", "s1", "", 1.0);
            fewShotManager.addExample("db1", "q2", "s2", "", 1.0);
            assertEquals(2, fewShotManager.getExampleCount("db1"));

            fewShotManager.clearExamples("db1");
            assertEquals(0, fewShotManager.getExampleCount("db1"));
        }

        @Test
        @DisplayName("clear for one db does not affect another")
        void clearOneDbDoesNotAffectAnother() {
            fewShotManager.addExample("db1", "q1", "s1", "", 1.0);
            fewShotManager.addExample("db2", "q2", "s2", "", 1.0);

            fewShotManager.clearExamples("db1");
            assertEquals(0, fewShotManager.getExampleCount("db1"));
            assertEquals(1, fewShotManager.getExampleCount("db2"));
        }
    }

    /**
     * 测试示例格式化功能的嵌套测试类
     * 
     * <p>
     * 验证FewShotManager的formatExamplesToPrompt方法功能，包括：
     * 1. 空列表或null输入的处理
     * 2. 正确格式化Q/A对为prompt字符串
     * </p>
     */
    @Nested
    @DisplayName("formatExamplesToPrompt")
    class FormatExamples {

        @Test
        @DisplayName("null list returns empty string")
        void nullList() {
            assertEquals("", fewShotManager.formatExamplesToPrompt(null));
        }

        @Test
        @DisplayName("empty list returns empty string")
        void emptyList() {
            assertEquals("", fewShotManager.formatExamplesToPrompt(List.of()));
        }

        @Test
        @DisplayName("formats examples as Q/A pairs")
        void formatsCorrectly() {
            FewShotManager.FewShotExample ex1 = new FewShotManager.FewShotExample(
                    "how many users", "SELECT COUNT(*) FROM users", "", 0.9);
            FewShotManager.FewShotExample ex2 = new FewShotManager.FewShotExample(
                    "list orders", "SELECT * FROM orders", "", 0.8);

            String prompt = fewShotManager.formatExamplesToPrompt(List.of(ex1, ex2));

            assertTrue(prompt.contains("Q: how many users"));
            assertTrue(prompt.contains("A: SELECT COUNT(*) FROM users"));
            assertTrue(prompt.contains("Q: list orders"));
            assertTrue(prompt.contains("A: SELECT * FROM orders"));
        }
    }

    /**
     * 测试批量添加示例功能的嵌套测试类
     * 
     * <p>
     * 验证FewShotManager的addExamples方法（批量添加）功能，包括：
     * 1. 空列表输入的处理
     * 2. 批量添加多个示例的正确性
     * </p>
     */
    @Nested
    @DisplayName("addExamples - batch")
    class BatchAdd {

        @Test
        @DisplayName("null list does nothing")
        void nullList() {
            fewShotManager.addExamples("db1", null);
            assertEquals(0, fewShotManager.getExampleCount("db1"));
        }

        @Test
        @DisplayName("adds multiple examples at once")
        void addsMultiple() {
            List<FewShotManager.FewShotExample> examples = List.of(
                    new FewShotManager.FewShotExample("q1", "s1", "", 0.9),
                    new FewShotManager.FewShotExample("q2", "s2", "", 0.8));
            fewShotManager.addExamples("db1", examples);
            assertEquals(2, fewShotManager.getExampleCount("db1"));
        }
    }

    /**
     * 测试FewShotExample内部类的嵌套测试类
     * 
     * <p>
     * 验证FewShotExample类的构造函数和字段设置功能，包括：
     * 1. 构造函数正确设置各字段值
     * 2. 时间戳的自动生成
     * </p>
     */
    @Nested
    @DisplayName("FewShotExample")
    class FewShotExampleTest {

        @Test
        @DisplayName("constructor sets fields and timestamp")
        void constructorSetsFields() {
            FewShotManager.FewShotExample ex = new FewShotManager.FewShotExample(
                    "question", "sql", "schema", 0.95);
            assertEquals("question", ex.getQuestion());
            assertEquals("sql", ex.getSql());
            assertEquals("schema", ex.getSchema());
            assertEquals(0.95, ex.getScore(), 0.01);
            assertTrue(ex.getTimestamp() > 0);
        }
    }
}
