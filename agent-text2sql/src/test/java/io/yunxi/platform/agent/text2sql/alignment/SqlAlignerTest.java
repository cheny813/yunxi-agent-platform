package io.yunxi.platform.agent.text2sql.alignment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlAlignerTest {

    /** SQL对齐器实例，用于执行各种SQL对齐测试 */
    private SqlAligner aligner;

    /**
     * 初始化测试环境
     * 
     * <p>
     * 在每个测试方法执行前创建一个新的SqlAligner实例，
     * 确保测试之间相互独立，避免状态污染
     * </p>
     */
    @BeforeEach
    void setUp() {
        aligner = new SqlAligner();
    }

    /**
     * 空输入处理测试类
     * 
     * <p>
     * 验证SqlAligner对空值、空字符串和空白字符串的处理能力，
     * 确保边界情况不会导致程序异常
     * </p>
     */
    @Nested
    @DisplayName("alignSql - null/empty input")
    class NullEmptyInput {

        /**
         * null输入返回null的测试
         * 
         * <p>
         * 验证当输入为null时，SqlAligner应该返回null，
         * 而不是抛出异常或返回空字符串
         * </p>
         */
        @Test
        @DisplayName("null input returns null")
        void nullInput() {
            assertNull(aligner.alignSql(null, null, null));
        }

        /**
         * 空字符串输入返回空字符串的测试
         * 
         * <p>
         * 验证当输入为空字符串时，SqlAligner应原样返回空字符串，
         * 不进行任何转换或修改
         * </p>
         */
        @Test
        @DisplayName("empty string returns empty")
        void emptyInput() {
            assertEquals("", aligner.alignSql("", null, null));
        }

        /**
         * 仅包含空格字符串的处理测试
         * 
         * <p>
         * 验证只包含空格的输入字符串应原样返回，
         * 保持空格的原始格式
         * </p>
         */
        @Test
        @DisplayName("whitespace-only string returns as-is")
        void whitespaceInput() {
            String input = "   ";
            assertEquals(input, aligner.alignSql(input, null, null));
        }
    }

    /**
     * 列名修复测试类
     * 
     * <p>
     * 验证SqlAligner自动修复常见列名格式的功能，
     * 将数据库字段名转换为更规范或简化的格式
     * </p>
     */
    @Nested
    @DisplayName("alignSql - fix column names")
    class FixColumnNames {

        /**
         * is_deleted列名修复测试
         * 
         * <p>
         * 验证SqlAligner将"is_deleted"列名修复为"deleted"，
         * 在SELECT语句和WHERE条件中都应该被修复
         * </p>
         */
        @Test
        @DisplayName("is_deleted -> deleted")
        void fixIsDeleted() {
            String sql = "SELECT is_deleted FROM users WHERE is_deleted = 1";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("deleted"));
            assertFalse(result.contains("is_deleted"));
        }

        /**
         * parent_id列名修复测试
         * 
         * <p>
         * 验证SqlAligner将"parent_id"列名修复为"pid"，
         * 简化常用的外键字段名
         * </p>
         */
        @Test
        @DisplayName("parent_id -> pid")
        void fixParentId() {
            String sql = "SELECT parent_id FROM categories WHERE parent_id = 0";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("pid"));
            assertFalse(result.contains("parent_id"));
        }

        /**
         * create_time列名修复测试
         * 
         * <p>
         * 验证SqlAligner将"create_time"转换为驼峰命名"createTime"，
         * 适配Java对象属性命名规范
         * </p>
         */
        @Test
        @DisplayName("create_time -> createTime")
        void fixCreateTime() {
            String sql = "SELECT create_time FROM orders WHERE create_time > '2024-01-01'";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("createTime"));
            assertFalse(result.contains("create_time"));
        }

        /**
         * update_time列名修复测试
         * 
         * <p>
         * 验证SqlAligner将"update_time"转换为驼峰命名"updateTime"，
         * 与create_time保持一致的命名转换规则
         * </p>
         */
        @Test
        @DisplayName("update_time -> updateTime")
        void fixUpdateTime() {
            String sql = "SELECT update_time FROM orders WHERE update_time > '2024-01-01'";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("updateTime"));
            assertFalse(result.contains("update_time"));
        }

        /**
         * isdeleted列名修复测试
         * 
         * <p>
         * 验证SqlAligner将"isdeleted"（非标准下划线格式）也修复为"deleted"，
         * 支持多种常见的列名格式变体
         * </p>
         */
        @Test
        @DisplayName("isdeleted -> deleted")
        void fixIsdeleted() {
            String sql = "SELECT isdeleted FROM users WHERE isdeleted = 1";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("deleted"));
            assertFalse(result.contains("isdeleted"));
        }
    }

    /**
     * LIMIT子句添加测试类
     * 
     * <p>
     * 验证SqlAligner自动为SELECT语句添加LIMIT子句的功能，
     * 防止无限制查询导致数据库性能问题，同时确保不对非SELECT语句添加LIMIT
     * </p>
     */
    @Nested
    @DisplayName("alignSql - add missing LIMIT")
    class AddMissingLimit {

        /**
         * 为缺少LIMIT的SELECT语句添加默认LIMIT测试
         * 
         * <p>
         * 验证当SELECT语句没有LIMIT子句时，SqlAligner自动添加LIMIT 1000，
         * 这是默认的安全限制，防止查询返回过多数据
         * </p>
         */
        @Test
        @DisplayName("SELECT without LIMIT gets LIMIT 1000")
        void selectWithoutLimit() {
            String sql = "SELECT * FROM users";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.endsWith("LIMIT 1000"));
        }

        /**
         * 已有LIMIT的SELECT语句保持不变测试
         * 
         * <p>
         * 验证当SELECT语句已有LIMIT子句时，SqlAligner维持原样不变，
         * 不会重复添加或修改已存在的LIMIT值
         * </p>
         */
        @Test
        @DisplayName("SELECT with existing LIMIT is unchanged")
        void selectWithLimit() {
            String sql = "SELECT * FROM users LIMIT 10";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("LIMIT 10"));
            assertFalse(result.contains("LIMIT 1000"));
        }

        /**
         * 非SELECT语句不添加LIMIT测试
         * 
         * <p>
         * 验证INSERT、UPDATE等非SELECT语句不会添加LIMIT子句，
         * 因为LIMIT仅对SELECT查询有意义
         * </p>
         */
        @Test
        @DisplayName("non-SELECT statement does not get LIMIT")
        void nonSelectStatement() {
            String sql = "INSERT INTO users (name) VALUES ('test')";
            String result = aligner.alignSql(sql, null, null);
            assertFalse(result.contains("LIMIT"));
        }
    }

    /**
     * 合法SQL处理测试类
     * 
     * <p>
     * 验证SqlAligner对语法正确的SQL语句仅进行必需的LIMIT添加，
     * 而不修改其他已经正确的部分，确保功能的非侵入性
     * </p>
     */
    @Nested
    @DisplayName("alignSql - valid SQL unchanged (except LIMIT)")
    class ValidSql {

        /**
         * 正确SQL语句仅添加LIMIT测试
         * 
         * <p>
         * 验证语法正确且列名规范的SQL语句，SqlAligner只添加必要的LIMIT子句，
         * 保持原始SQL的结构和内容不变，仅增加安全限制
         * </p>
         */
        @Test
        @DisplayName("correct SQL only gets LIMIT added")
        void correctSqlGetsLimitOnly() {
            String sql = "SELECT id, name FROM users";
            String result = aligner.alignSql(sql, null, null);
            assertEquals("SELECT id, name FROM users LIMIT 1000", result);
        }
    }

    /**
     * 组合转换功能测试类
     * 
     * <p>
     * 验证SqlAligner同时应用多个转换规则的能力，
     * 确保列名修复和LIMIT添加等功能能够协同工作
     * </p>
     */
    @Nested
    @DisplayName("alignSql - combined transformations")
    class CombinedTransformations {

        /**
         * 列名修复和LIMIT添加同时生效测试
         * 
         * <p>
         * 验证SqlAligner可以同时执行列名格式修复和LIMIT子句添加，
         * 确保复杂SQL语句的多项优化能够正确组合应用
         * </p>
         */
        @Test
        @DisplayName("fix column names AND add LIMIT")
        void fixColumnsAndAddLimit() {
            String sql = "SELECT is_deleted FROM users WHERE is_deleted = 0";
            String result = aligner.alignSql(sql, null, null);
            assertTrue(result.contains("deleted"));
            assertFalse(result.contains("is_deleted"));
            assertTrue(result.contains("LIMIT 1000"));
        }
    }
}
