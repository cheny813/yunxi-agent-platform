package io.yunxi.platform.spi.text2sql;

import java.util.List;

/**
 * 数据库客户端 SPI 接口
 *
 * <p>为 agent-text2sql 模块提供数据库访问能力的抽象接口。
 * 由 agent-core 或其他模块提供具体实现。</p>
 *
 * @author yunxi-agent-platform
 * @version 1.0.0
 */
public interface DatabaseClient {

    /**
     * 执行 SQL 查询
     *
     * @param sql SQL 查询语句
     * @return 查询结果
     */
    QueryResult query(String sql);

    /**
     * 列出数据库中的所有表
     *
     * @return 表名列表
     */
    List<String> listTables();

    /**
     * 获取表结构信息
     *
     * @param tableName 表名
     * @return 表结构信息
     */
    TableSchema describeTable(String tableName);

    /**
     * 检查数据库客户端是否可用
     *
     * @return true 如果客户端已配置且可用
     */
    boolean isAvailable();

    /**
     * SQL查询结果封装类
     *
     * <p>
     * 用于统一封装数据库查询操作的执行结果，
     * 包含成功状态、查询内容和错误信息
     * </p>
     */
    class QueryResult {
        /** 查询执行是否成功 */
        private final boolean success;
        
        /** 查询结果内容（成功时返回） */
        private final String content;
        
        /** 错误信息（失败时返回） */
        private final String errorMessage;

        /**
         * 构造函数
         *
         * @param success 查询是否成功
         * @param content 查询结果内容（成功时）
         * @param errorMessage 错误信息（失败时）
         */
        public QueryResult(boolean success, String content, String errorMessage) {
            this.success = success;
            this.content = content;
            this.errorMessage = errorMessage;
        }

        /**
         * 创建成功结果
         *
         * @param content 成功的查询结果内容
         * @return 包含成功结果的QueryResult实例
         */
        public static QueryResult success(String content) {
            return new QueryResult(true, content, null);
        }

        /**
         * 创建失败结果
         *
         * @param errorMessage 失败的错误信息
         * @return 包含失败结果的QueryResult实例
         */
        public static QueryResult error(String errorMessage) {
            return new QueryResult(false, null, errorMessage);
        }

        /**
         * 判断查询是否成功
         *
         * @return true表示查询成功，false表示失败
         */
        public boolean isSuccess() {
            return success;
        }

        /**
         * 获取查询结果内容
         *
         * @return 查询结果字符串，失败时为null
         */
        public String getContent() {
            return content;
        }

        /**
         * 获取错误信息
         *
         * @return 错误信息字符串，成功时为null
         */
        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * 数据库表结构信息封装类
     *
     * <p>
     * 用于封装数据库表的元数据信息，包括表名和列定义列表
     * 为text-to-SQL功能提供表结构认知能力
     * </p>
     */
    class TableSchema {
        /** 数据库表名称 */
        private final String tableName;
        
        /** 表列定义列表 */
        private final List<ColumnInfo> columns;

        /**
         * 构造函数
         *
         * @param tableName 数据库表名
         * @param columns 包含所有列定义的列表，不能为null
         */
        public TableSchema(String tableName, List<ColumnInfo> columns) {
            this.tableName = tableName;
            this.columns = columns;
        }

        /**
         * 获取表名
         *
         * @return 数据库表名称
         */
        public String getTableName() {
            return tableName;
        }

        /**
         * 获取表的所有列定义
         *
         * @return 列定义列表，包含每个列的详细信息
         */
        public List<ColumnInfo> getColumns() {
            return columns;
        }
    }

    /**
     * 数据库表列信息封装类
     *
     * <p>
     * 用于封装数据库中单个列的定义信息，
     * 包括列名、数据类型和可空性约束
     * </p>
     */
    class ColumnInfo {
        /** 列名称 */
        private final String name;
        
        /** 列数据类型（如VARCHAR, INT, DATETIME等） */
        private final String type;
        
        /** 列是否允许为NULL */
        private final boolean nullable;

        /**
         * 构造函数
         *
         * @param name 列名，不能为null或空
         * @param type 列的数据类型，不能为null
         * @param nullable 列是否允许为NULL
         */
        public ColumnInfo(String name, String type, boolean nullable) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
        }

        /**
         * 获取列名
         *
         * @return 列名称
         */
        public String getName() {
            return name;
        }

        /**
         * 获取列的数据类型
         *
         * @return 数据类型名称（如VARCHAR, INT, DATETIME等）
         */
        public String getType() {
            return type;
        }

        /**
         * 判断列是否允许为NULL
         *
         * @return true表示允许NULL值，false表示不允许
         */
        public boolean isNullable() {
            return nullable;
        }
    }
}
